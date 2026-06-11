package re.chasam.voicetastic.ui.chat

import android.content.Context
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import re.chasam.voicetastic.model.VoiceConfig
import re.chasam.voicetastic.service.IncomingData
import re.chasam.voicetastic.service.IncomingText
import re.chasam.voicetastic.service.MeshAckEvent
import re.chasam.voicetastic.service.MeshFacade
import re.chasam.voicetastic.voice.VoiceAssemblerApi
import re.chasam.voicetastic.voice.VoicePlayerApi
import re.chasam.voicetastic.voice.VoiceRecorderApi
import uniffi.voicetastic.AssemblerConfig
import uniffi.voicetastic.AssemblyEvent
import uniffi.voicetastic.TickOutput
import uniffi.voicetastic.VoiceCodec
import java.io.File

/**
 * Unit tests for [MessagingViewModel] using fakes for the voice components.
 *
 * Deliberately NOT using `runTest`: the VM's assembler tick loop is an
 * infinite `delay`-loop on `viewModelScope` (Dispatchers.Main = our test
 * dispatcher). `runTest` auto-advances virtual time to idle at the end of the
 * body, which would spin that loop forever. Instead we drive a manual
 * [TestCoroutineScheduler] with `runCurrent()` — it runs the ready tasks at the
 * current virtual time without advancing the clock, so the tick loop stays
 * parked at its first `delay()` and never fires.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagingViewModelTest : FunSpec({

    // --- Fakes -------------------------------------------------------------

    class FakeRecorder : VoiceRecorderApi {
        var startFile: File? = null
        private var recording = false
        override var onMaxDurationReached: ((File) -> Unit)? = null
        override fun startRecording(config: VoiceConfig): File? {
            recording = startFile != null
            return startFile
        }
        override fun stopRecording(): File? { recording = false; return startFile }
        override fun isCurrentlyRecording(): Boolean = recording
    }

    class FakePlayer : VoicePlayerApi {
        @Volatile override var isPlaying: Boolean = false
            private set
        var lastOnComplete: (() -> Unit)? = null
        var playCount = 0
        var stopCount = 0
        override fun play(
            audioData: ByteArray,
            cacheDir: File,
            codec: VoiceCodec,
            codecParam: Int,
            onComplete: (() -> Unit)?,
        ) {
            playCount++
            isPlaying = true
            lastOnComplete = onComplete
        }
        override fun stop() { stopCount++; isPlaying = false }
        override fun release() { isPlaying = false }
    }

    class FakeAssembler : VoiceAssemblerApi {
        val configs = mutableListOf<AssemblerConfig>()
        override fun accept(from: String, broadcast: Boolean, toNode: UInt, channel: UInt, frame: ByteArray): AssemblyEvent =
            AssemblyEvent.Duplicate
        override fun setConfig(cfg: AssemblerConfig) { configs.add(cfg) }
        override fun tick(): TickOutput = TickOutput(emptyList(), emptyList())
        override fun close() {}
    }

    fun relaxedService(
        incomingText: MutableSharedFlow<IncomingText>,
        incomingData: MutableSharedFlow<IncomingData>,
        ackEvents: MutableSharedFlow<MeshAckEvent>,
    ): MeshFacade {
        val svc = mockk<MeshFacade>(relaxed = true)
        every { svc.incomingTextMessages } returns incomingText
        every { svc.incomingDataMessages } returns incomingData
        every { svc.ackEvents } returns ackEvents
        every { svc.nodes } returns MutableStateFlow(emptyList())
        every { svc.myNodeId } returns MutableStateFlow("!12345678")
        every { svc.selfNode } returns MutableStateFlow(null)
        every { svc.nodeHistory } returns MutableStateFlow(emptyMap())
        every { svc.connectionState } returns MutableStateFlow("CONNECTED")
        every { svc.channels } returns MutableStateFlow(emptyList())
        return svc
    }

    val ctx = mockk<Context>(relaxed = true)
    lateinit var scheduler: TestCoroutineScheduler

    beforeTest {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }
    afterTest { Dispatchers.resetMain() }

    test("max-duration auto-stop transitions to preview instead of dropping the clip") {
        val recorder = FakeRecorder()
        val clip = File.createTempFile("voice", ".amr").apply { writeBytes(ByteArray(64) { 1 }) }
        recorder.startFile = clip

        val vm = MessagingViewModel(
            meshService = relaxedService(MutableSharedFlow(), MutableSharedFlow(), MutableSharedFlow()),
            context = ctx,
            voiceConfig = MutableStateFlow(VoiceConfig()),
            recorder = recorder,
            player = FakePlayer(),
            assemblerFactory = { FakeAssembler() },
        )
        scheduler.runCurrent()

        vm.startRecording()
        vm.isRecording.value.shouldBeTrue()

        // Recorder stops itself at max duration and hands back the file.
        recorder.onMaxDurationReached.shouldNotBeNull()
        recorder.onMaxDurationReached!!.invoke(clip)
        scheduler.runCurrent()

        vm.isRecording.value.shouldBeFalse()
        vm.previewFile.value shouldBe clip
        clip.delete()
    }

    test("live chunk-timeout setting is pushed into the running assembler") {
        val assembler = FakeAssembler()
        val voiceConfig = MutableStateFlow(VoiceConfig(chunkTimeoutSeconds = 30))
        MessagingViewModel(
            meshService = relaxedService(MutableSharedFlow(), MutableSharedFlow(), MutableSharedFlow()),
            context = ctx,
            voiceConfig = voiceConfig,
            recorder = FakeRecorder(),
            player = FakePlayer(),
            assemblerFactory = { assembler },
        )
        scheduler.runCurrent()
        assembler.configs.clear() // ignore the construction-time config

        voiceConfig.value = voiceConfig.value.copy(chunkTimeoutSeconds = 99)
        scheduler.runCurrent()

        assembler.configs shouldHaveSize 1
        assembler.configs.first().messageTimeoutMs shouldBe 99_000uL
    }

    test("appended chat items get unique ids") {
        val incomingText = MutableSharedFlow<IncomingText>(extraBufferCapacity = 32)
        val vm = MessagingViewModel(
            meshService = relaxedService(incomingText, MutableSharedFlow(), MutableSharedFlow()),
            context = ctx,
            voiceConfig = MutableStateFlow(VoiceConfig()),
            recorder = FakeRecorder(),
            player = FakePlayer(),
            assemblerFactory = { FakeAssembler() },
        )
        scheduler.runCurrent()

        repeat(20) { i ->
            incomingText.tryEmit(IncomingText(from = "!aaaa$i", to = "broadcast", text = "m$i", channel = 0))
        }
        scheduler.runCurrent()

        val items = vm.chatItems.value
        items shouldHaveSize 20
        items.map { it.id }.toSet() shouldHaveSize 20
    }
})
