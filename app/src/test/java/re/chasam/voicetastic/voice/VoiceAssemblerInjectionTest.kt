package re.chasam.voicetastic.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import re.chasam.voicetastic.core.FakeClock
import re.chasam.voicetastic.core.RecordingLogger
import re.chasam.voicetastic.model.AmrNbBitrate
import re.chasam.voicetastic.model.VoiceMessage

/**
 * Verifies the `Clock` / `Logger` injection seam added in preparation for
 * the Rust `voicetastic-core` integration (see `INTEGRATION.md`). The
 * existing behavioural assertions live in [VoiceAssemblerTest]; this file
 * only exercises the new abstractions so the seam isn't accidentally
 * removed by a future refactor.
 */
class VoiceAssemblerInjectionTest : FunSpec({

    test("startTime on a completed message comes from the injected Clock") {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val clock = FakeClock(initialMs = 42_000L)
        val logger = RecordingLogger()
        val assembler = VoiceAssembler(
            chunkTimeoutSeconds = 5,
            scope = scope,
            clock = clock,
            logger = logger,
        )
        val results = mutableListOf<VoiceMessage>()
        val job = scope.launch { assembler.completedMessages.collect { results += it } }
        delay(100)

        val audio = ByteArray(100) { 0x10 }
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 7, bitrate = AmrNbBitrate.MR795)
        chunks.forEach { assembler.onChunkReceived("!sender", it) }

        delay(500)
        job.cancel()

        results.size shouldBe 1
        results[0].timestamp shouldBe 42_000L
        // We logged at least the per-chunk debug line and the completion info line.
        logger.entries.shouldNotBeEmpty()
        logger.entries.count { it.level == "I" } shouldBeGreaterThanOrEqual 1

        assembler.destroy()
        scope.cancel()
    }

    test("invalid chunks emit a warning through the injected Logger, not android.util.Log") {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val logger = RecordingLogger()
        val assembler = VoiceAssembler(scope = scope, clock = FakeClock(), logger = logger)

        assembler.onChunkReceived("!bad", ByteArray(3)) // too short → invalid header
        delay(200)

        logger.entries.any { it.level == "W" && it.msg.contains("Invalid chunk header") } shouldBe true
        assembler.pendingCount() shouldBe 0

        assembler.destroy()
        scope.cancel()
    }
})

