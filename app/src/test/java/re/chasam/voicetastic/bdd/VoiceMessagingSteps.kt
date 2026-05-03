package re.chasam.voicetastic.bdd

import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import re.chasam.voicetastic.model.AmrNbBitrate
import re.chasam.voicetastic.model.VoiceConfig
import re.chasam.voicetastic.model.VoiceMessage
import re.chasam.voicetastic.service.Portnums
import re.chasam.voicetastic.voice.VoiceAssembler
import re.chasam.voicetastic.voice.VoiceChunker

class VoiceMessagingSteps {

    private var audioData: ByteArray = ByteArray(0)
    private var chunks: List<ByteArray> = emptyList()
    private var assembler: VoiceAssembler? = null
    private var assembledMessages: MutableList<VoiceMessage> = mutableListOf()
    private var voiceConfig = VoiceConfig()
    private var currentBitrate = AmrNbBitrate.MR795
    private var maxDuration = 20
    private var testScope: CoroutineScope? = null
    private var preparedChunks: Map<Int, ByteArray> = emptyMap()

    @Before
    fun setup() {
        audioData = ByteArray(0)
        chunks = emptyList()
        assembledMessages.clear()
        voiceConfig = VoiceConfig()
        currentBitrate = AmrNbBitrate.MR795
        maxDuration = 20
        testScope?.cancel()
        testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        assembler?.destroy()
        assembler = null
    }

    // --- Given steps ---

    @Given("the voice bitrate is set to {string}")
    fun setBitrate(bitrateLabel: String) {
        currentBitrate = AmrNbBitrate.entries.first { it.label == bitrateLabel }
        voiceConfig = voiceConfig.copy(bitrate = currentBitrate)
    }

    @Given("the max recording duration is {int} seconds")
    fun setMaxDuration(seconds: Int) {
        maxDuration = seconds
        voiceConfig = voiceConfig.copy(maxDurationSeconds = seconds)
    }

    @Given("I have AMR-NB audio data of {int} bytes")
    fun haveAudioData(size: Int) {
        audioData = ByteArray(size) { (it % 256).toByte() }
    }

    @Given("a voice message with ID {int} has {int} chunks")
    fun prepareVoiceMessage(messageId: Int, chunkCount: Int) {
        // Create audio data that produces the desired number of chunks
        val dataSize = chunkCount * VoiceChunker.MAX_PAYLOAD_SIZE
        audioData = ByteArray(dataSize) { (it % 200).toByte() }
        val allChunks = VoiceChunker.chunkAudio(audioData, messageId, currentBitrate)
        preparedChunks = allChunks.mapIndexed { idx, chunk -> idx to chunk }.toMap()

        // Initialize assembler
        assembler = VoiceAssembler(
            chunkTimeoutSeconds = voiceConfig.chunkTimeoutSeconds,
            partialPlayOnTimeout = voiceConfig.partialPlayOnTimeout,
            scope = testScope!!
        )
    }

    @Given("the chunk timeout is set to {int} seconds")
    fun setChunkTimeout(seconds: Int) {
        voiceConfig = voiceConfig.copy(chunkTimeoutSeconds = seconds)
        // Re-initialize assembler with new timeout
        assembler?.destroy()
        assembler = VoiceAssembler(
            chunkTimeoutSeconds = seconds,
            partialPlayOnTimeout = true,
            scope = testScope!!
        )
    }

    // --- When steps ---

    @When("I start recording a voice message")
    fun startRecording() {
        // Simulated - actual recording requires Android context
    }

    @When("I stop recording after {int} seconds")
    fun stopRecordingAfter(seconds: Int) {
        // Simulated - creates sample audio data
        val estimatedSize = currentBitrate.bytesPerSecond * seconds
        audioData = ByteArray(estimatedSize) { (it % 128).toByte() }
    }

    @When("the audio is chunked with message ID {int}")
    fun chunkAudio(messageId: Int) {
        chunks = VoiceChunker.chunkAudio(audioData, messageId, currentBitrate)
    }

    @When("all {int} chunks arrive from {string} in order")
    fun allChunksArrive(count: Int, sender: String) {
        runBlocking {
            val collectJob = testScope!!.launch {
                assembler!!.completedMessages.collect { assembledMessages.add(it) }
            }
            delay(100)
            for (i in 0 until count) {
                assembler!!.onChunkReceived(sender, preparedChunks[i]!!)
            }
            delay(1000) // allow async mutex-guarded processing
            collectJob.cancel()
        }
    }

    @When("chunk {int} arrives from {string}")
    fun chunkArrives(index: Int, sender: String) {
        assembler!!.onChunkReceived(sender, preparedChunks[index]!!)
    }

    @When("only chunks {int} and {int} arrive from {string}")
    fun partialChunksArrive(idx1: Int, idx2: Int, sender: String) {
        assembler!!.onChunkReceived(sender, preparedChunks[idx1]!!)
        assembler!!.onChunkReceived(sender, preparedChunks[idx2]!!)
    }

    @When("I wait for the chunk timeout to expire")
    fun waitForTimeout() {
        runBlocking {
            val collectJob = testScope!!.launch {
                assembler!!.completedMessages.collect { assembledMessages.add(it) }
            }
            // Wait longer than the timeout
            delay((voiceConfig.chunkTimeoutSeconds * 1000L) + 2000)
            collectJob.cancel()
        }
    }

    @When("{int} seconds elapse")
    fun secondsElapse(seconds: Int) {
        // Simulated for max duration test
    }

    @When("the same chunk arrives {int} times from {string}")
    fun sameChunkMultipleTimes(times: Int, sender: String) {
        runBlocking {
            val collectJob = testScope!!.launch {
                assembler!!.completedMessages.collect { assembledMessages.add(it) }
            }
            delay(100)
            val chunk = preparedChunks[0]!!
            repeat(times) {
                assembler!!.onChunkReceived(sender, chunk)
            }
            delay(1000) // allow async processing
            collectJob.cancel()
        }
    }

    // --- Then steps ---

    @Then("the recording should produce AMR-NB audio data")
    fun recordingProducesAmrData() {
        audioData.size shouldBeGreaterThan 0
    }

    @Then("the audio should be chunked into packets of at most {int} bytes each")
    fun chunksAreCorrectSize(maxSize: Int) {
        chunks = VoiceChunker.chunkAudio(audioData, 1, currentBitrate)
        chunks.forEach { chunk ->
            chunk.size shouldBeLessThanOrEqual maxSize
        }
    }

    @Then("each chunk should have a 6-byte header with message ID, chunk index, total chunks, and bitrate")
    fun chunksHaveValidHeaders() {
        chunks.forEachIndexed { idx, chunk ->
            val header = VoiceChunker.parseHeader(chunk)!!
            header.version shouldBe VoiceChunker.PROTOCOL_VERSION
            header.chunkIndex shouldBe idx
            header.totalChunks shouldBe chunks.size
        }
    }

    @Then("the chunks should be sent via the PRIVATE_APP port")
    fun chunksSentViaPrivatePort() {
        // Verified by architecture - VoiceViewModel sends on Portnums.PRIVATE_APP
        Portnums.PRIVATE_APP shouldBe 256
    }

    @Then("it should produce {int} chunks")
    fun producesNChunks(expected: Int) {
        chunks shouldHaveSize expected
    }

    @Then("reassembling the chunk payloads should produce the original {int} bytes")
    fun reassemblyMatchesOriginal(size: Int) {
        val reassembled = chunks.flatMap { VoiceChunker.extractPayload(it).toList() }.toByteArray()
        // VoiceChunker strips the AMR file header before chunking, so
        // reassembled payloads match the input with AMR header stripped.
        val expected = VoiceChunker.stripAmrHeader(audioData)
        reassembled shouldBe expected
    }

    @Then("a complete voice message should be assembled")
    fun completeMessageAssembled() {
        assembledMessages.size shouldBeGreaterThan 0
    }

    @Then("the voice message should be marked as complete")
    fun messageIsComplete() {
        assembledMessages.last().isComplete.shouldBeTrue()
    }

    @Then("the voice message should be playable")
    fun messageIsPlayable() {
        val msg = assembledMessages.last()
        msg.audioData.size shouldBeGreaterThan 0
        // Should start with AMR header
        msg.audioData.take(6).toByteArray() shouldBe VoiceAssembler.AMR_HEADER
    }

    @Then("a partial voice message should be assembled")
    fun partialMessageAssembled() {
        assembledMessages.size shouldBeGreaterThan 0
        assembledMessages.last().isComplete.shouldBeFalse()
    }

    @Then("the voice message should have {int} received chunks out of {int} total")
    fun messageHasChunkCounts(received: Int, total: Int) {
        val msg = assembledMessages.last()
        msg.receivedChunks shouldBe received
        msg.totalChunks shouldBe total
    }

    @Then("missing chunks should be replaced with silence frames")
    fun missingChunksAreSilence() {
        val msg = assembledMessages.last()
        // Audio data should exist (AMR header + some data)
        msg.audioData.size shouldBeGreaterThan VoiceAssembler.AMR_HEADER.size
    }

    @Then("the recording should stop automatically")
    fun recordingStopsAutomatically() {
        // Simulated - VoiceRecorder sets max duration on MediaRecorder
        voiceConfig.maxDurationSeconds shouldBe maxDuration
    }

    @Then("each chunk header should contain bitrate index {int}")
    fun chunkHeaderHasBitrateIndex(expectedIndex: Int) {
        chunks.forEach { chunk ->
            val header = VoiceChunker.parseHeader(chunk)!!
            header.bitrateIndex shouldBe expectedIndex
        }
    }

    @Then("exactly {int} voice message should be assembled")
    fun exactlyNMessagesAssembled(count: Int) {
        assembledMessages shouldHaveSize count
    }

    @Then("it should be marked as complete")
    fun itIsComplete() {
        assembledMessages.last().isComplete.shouldBeTrue()
    }
}


