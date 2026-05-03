package re.chasam.voicetastic.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import re.chasam.voicetastic.model.AmrNbBitrate
import re.chasam.voicetastic.model.VoiceMessage

class VoiceAssemblerTest : FunSpec({

    test("complete message is emitted when all chunks arrive in order") {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val assembler = VoiceAssembler(chunkTimeoutSeconds = 5, scope = scope)
        val results = mutableListOf<VoiceMessage>()

        val collectJob = scope.launch {
            assembler.completedMessages.collect { results.add(it) }
        }
        delay(100) // let collector start

        val audio = ByteArray(400) { (it % 128).toByte() }
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 1, bitrate = AmrNbBitrate.MR795)
        chunks.forEach { assembler.onChunkReceived("!sender1", it) }

        delay(1000) // allow mutex-guarded coroutines to process
        collectJob.cancel()

        results.size shouldBe 1
        results[0].messageId shouldBe 1
        results[0].from shouldBe "!sender1"
        results[0].isComplete.shouldBeTrue()
        results[0].totalChunks shouldBe chunks.size
        results[0].receivedChunks shouldBe chunks.size
        results[0].audioData.take(6).toByteArray() shouldBe VoiceAssembler.AMR_HEADER

        assembler.pendingCount() shouldBe 0
        assembler.destroy()
        scope.cancel()
    }

    test("complete message when chunks arrive out of order") {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val assembler = VoiceAssembler(chunkTimeoutSeconds = 5, scope = scope)
        val results = mutableListOf<VoiceMessage>()

        val collectJob = scope.launch {
            assembler.completedMessages.collect { results.add(it) }
        }
        delay(100)

        val audio = ByteArray(600) { (it % 200).toByte() }
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 42, bitrate = AmrNbBitrate.MR475)
        chunks.reversed().forEach { assembler.onChunkReceived("!node2", it) }

        delay(1000)
        collectJob.cancel()

        results.size shouldBe 1
        results[0].messageId shouldBe 42
        results[0].isComplete.shouldBeTrue()
        results[0].receivedChunks shouldBe chunks.size

        assembler.destroy()
        scope.cancel()
    }

    test("partial message emitted on timeout when partialPlayOnTimeout is true") {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val assembler = VoiceAssembler(
            chunkTimeoutSeconds = 1,
            partialPlayOnTimeout = true,
            scope = scope
        )
        val results = mutableListOf<VoiceMessage>()

        val collectJob = scope.launch {
            assembler.completedMessages.collect { results.add(it) }
        }
        delay(100)

        val audio = ByteArray(600) { 0x55 }
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 10, bitrate = AmrNbBitrate.MR795)
        assembler.onChunkReceived("!partial", chunks[0])

        // Wait for timeout + some margin
        delay(2500)
        collectJob.cancel()

        results.size shouldBe 1
        results[0].messageId shouldBe 10
        results[0].isComplete.shouldBeFalse()
        results[0].receivedChunks shouldBe 1
        results[0].totalChunks shouldBe chunks.size
        results[0].audioData.take(6).toByteArray() shouldBe VoiceAssembler.AMR_HEADER

        assembler.destroy()
        scope.cancel()
    }

    test("duplicate chunks are ignored during assembly") {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val assembler = VoiceAssembler(chunkTimeoutSeconds = 5, scope = scope)
        val results = mutableListOf<VoiceMessage>()

        val collectJob = scope.launch {
            assembler.completedMessages.collect { results.add(it) }
        }
        delay(100)

        // Use audio that produces 2 chunks
        val audio = ByteArray(300) { 0x33 }
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 5, bitrate = AmrNbBitrate.MR795)

        // Send chunk 0 three times, then chunk 1 once
        assembler.onChunkReceived("!dup", chunks[0])
        assembler.onChunkReceived("!dup", chunks[0])
        assembler.onChunkReceived("!dup", chunks[0])
        assembler.onChunkReceived("!dup", chunks[1])

        delay(1000)
        collectJob.cancel()

        results.size shouldBe 1
        results[0].receivedChunks shouldBe 2
        results[0].isComplete.shouldBeTrue()

        assembler.destroy()
        scope.cancel()
    }

    test("concurrent messages from different senders are tracked separately") {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val assembler = VoiceAssembler(chunkTimeoutSeconds = 5, scope = scope)
        val results = mutableListOf<VoiceMessage>()

        val collectJob = scope.launch {
            assembler.completedMessages.collect { results.add(it) }
        }
        delay(100)

        val audio1 = ByteArray(100) { 0x11 }
        val audio2 = ByteArray(100) { 0x22 }
        val chunks1 = VoiceChunker.chunkAudio(audio1, messageId = 1, bitrate = AmrNbBitrate.MR795)
        val chunks2 = VoiceChunker.chunkAudio(audio2, messageId = 2, bitrate = AmrNbBitrate.MR795)

        assembler.onChunkReceived("!nodeA", chunks1[0])
        assembler.onChunkReceived("!nodeB", chunks2[0])

        delay(1000)
        collectJob.cancel()

        results.size shouldBe 2
        results.any { it.from == "!nodeA" && it.messageId == 1 }.shouldBeTrue()
        results.any { it.from == "!nodeB" && it.messageId == 2 }.shouldBeTrue()

        assembler.destroy()
        scope.cancel()
    }

    test("clear removes all pending assemblies") {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val assembler = VoiceAssembler(chunkTimeoutSeconds = 30, scope = scope)

        val audio = ByteArray(500) { 0x44 }
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 99, bitrate = AmrNbBitrate.MR795)

        assembler.onChunkReceived("!node", chunks[0])
        delay(500) // allow coroutine to process
        assembler.pendingCount() shouldBe 1

        assembler.clear()
        assembler.pendingCount() shouldBe 0

        assembler.destroy()
        scope.cancel()
    }

    test("invalid chunk data is ignored") {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val assembler = VoiceAssembler(scope = scope)

        assembler.onChunkReceived("!bad", ByteArray(3))
        delay(200)
        assembler.pendingCount() shouldBe 0

        assembler.destroy()
        scope.cancel()
    }

    test("assembled audio contains silence frames for missing chunks") {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val assembler = VoiceAssembler(
            chunkTimeoutSeconds = 1,
            partialPlayOnTimeout = true,
            scope = scope
        )
        val results = mutableListOf<VoiceMessage>()

        val collectJob = scope.launch {
            assembler.completedMessages.collect { results.add(it) }
        }
        delay(100)

        val audio = ByteArray(500) { 0x66 }
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 20, bitrate = AmrNbBitrate.MR795)

        assembler.onChunkReceived("!gap", chunks[0])
        if (chunks.size > 2) {
            assembler.onChunkReceived("!gap", chunks[2])
        }

        delay(2500)
        collectJob.cancel()

        results.size shouldBe 1
        results[0].isComplete.shouldBeFalse()
        // Audio should be larger than just AMR header because silence frames
        // are now correctly sized (multiple frames per missing chunk)
        results[0].audioData.size shouldBeGreaterThan VoiceAssembler.AMR_HEADER.size

        assembler.destroy()
        scope.cancel()
    }

    test("late duplicate chunks after completion are rejected") {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val assembler = VoiceAssembler(chunkTimeoutSeconds = 5, scope = scope)
        val results = mutableListOf<VoiceMessage>()

        val collectJob = scope.launch {
            assembler.completedMessages.collect { results.add(it) }
        }
        delay(100)

        val audio = ByteArray(100) { 0x11 }
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 77, bitrate = AmrNbBitrate.MR795)
        // Complete the message
        chunks.forEach { assembler.onChunkReceived("!late", it) }
        delay(500)
        results.size shouldBe 1

        // Send a late duplicate — should be rejected, not start a new assembly
        assembler.onChunkReceived("!late", chunks[0])
        delay(500)

        results.size shouldBe 1 // still just 1
        assembler.pendingCount() shouldBe 0

        collectJob.cancel()
        assembler.destroy()
        scope.cancel()
    }

    test("AMR header constant is correct") {
        val expected = byteArrayOf(0x23, 0x21, 0x41, 0x4D, 0x52, 0x0A)
        VoiceAssembler.AMR_HEADER shouldBe expected
    }
})
