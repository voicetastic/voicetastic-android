package re.chasam.voicetastic.voice

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import re.chasam.voicetastic.model.AmrNbBitrate

class VoiceChunkerTest : FunSpec({

    test("empty audio data returns no chunks") {
        val chunks = VoiceChunker.chunkAudio(ByteArray(0), messageId = 1, bitrate = AmrNbBitrate.MR795)
        chunks shouldHaveSize 0
    }

    test("AMR-header-only data returns no chunks") {
        // Only the 6-byte AMR file header, no actual frames
        val chunks = VoiceChunker.chunkAudio(VoiceChunker.AMR_FILE_HEADER.copyOf(), messageId = 1, bitrate = AmrNbBitrate.MR795)
        chunks shouldHaveSize 0
    }

    test("small audio data fits in a single chunk") {
        val audio = ByteArray(100) { it.toByte() }
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 42, bitrate = AmrNbBitrate.MR475)

        chunks shouldHaveSize 1
        chunks[0].size shouldBe VoiceChunker.HEADER_SIZE + 100

        val header = VoiceChunker.parseHeader(chunks[0])!!
        header.version shouldBe VoiceChunker.PROTOCOL_VERSION
        header.messageId shouldBe 42
        header.chunkIndex shouldBe 0
        header.totalChunks shouldBe 1
        header.bitrateIndex shouldBe AmrNbBitrate.MR475.ordinal
    }

    test("AMR file header is stripped before chunking") {
        // 6-byte AMR header + 100 bytes of frame data
        val amrFile = VoiceChunker.AMR_FILE_HEADER + ByteArray(100) { it.toByte() }
        val chunks = VoiceChunker.chunkAudio(amrFile, messageId = 1, bitrate = AmrNbBitrate.MR795)

        chunks shouldHaveSize 1
        // Payload should be 100 bytes (AMR header stripped), not 106
        val payload = VoiceChunker.extractPayload(chunks[0])
        payload.size shouldBe 100
    }

    test("audio data splits into correct number of chunks") {
        // 500 bytes of audio -> ceil(500/225) = 3 chunks
        val audio = ByteArray(500) { (it % 256).toByte() }
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 7, bitrate = AmrNbBitrate.MR795)

        chunks shouldHaveSize 3

        // First two chunks are full
        chunks[0].size shouldBe VoiceChunker.MAX_PACKET_SIZE // 231
        chunks[1].size shouldBe VoiceChunker.MAX_PACKET_SIZE

        // Last chunk has remainder: 500 - 2*225 = 50 + header
        chunks[2].size shouldBe VoiceChunker.HEADER_SIZE + 50
    }

    test("chunk headers are sequential and consistent") {
        val audio = ByteArray(700) { 0x42 }
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 999, bitrate = AmrNbBitrate.MR122)

        val totalExpected = (700 + VoiceChunker.MAX_PAYLOAD_SIZE - 1) / VoiceChunker.MAX_PAYLOAD_SIZE

        chunks.forEachIndexed { idx, chunk ->
            val header = VoiceChunker.parseHeader(chunk)!!
            header.version shouldBe VoiceChunker.PROTOCOL_VERSION
            header.messageId shouldBe (999 and 0xFFFF)
            header.chunkIndex shouldBe idx
            header.totalChunks shouldBe totalExpected
            header.bitrateIndex shouldBe AmrNbBitrate.MR122.ordinal
        }
    }

    test("extractPayload returns audio data without header") {
        val audio = byteArrayOf(1, 2, 3, 4, 5)
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 1, bitrate = AmrNbBitrate.MR795)

        val payload = VoiceChunker.extractPayload(chunks[0])
        payload shouldBe audio
    }

    test("reassembled payloads match original audio (no AMR file header)") {
        val audio = ByteArray(600) { (it * 3).toByte() }
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 55, bitrate = AmrNbBitrate.MR67)

        // Reassemble
        val reassembled = chunks.flatMap { VoiceChunker.extractPayload(it).toList() }.toByteArray()
        reassembled shouldBe audio
    }

    test("reassembled payloads match original frames when AMR header is present") {
        val frames = ByteArray(600) { (it * 3).toByte() }
        val amrFile = VoiceChunker.AMR_FILE_HEADER + frames
        val chunks = VoiceChunker.chunkAudio(amrFile, messageId = 55, bitrate = AmrNbBitrate.MR67)

        val reassembled = chunks.flatMap { VoiceChunker.extractPayload(it).toList() }.toByteArray()
        reassembled shouldBe frames
    }

    test("messageId wraps to UInt16") {
        val audio = ByteArray(10)
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 70000, bitrate = AmrNbBitrate.MR795)

        val header = VoiceChunker.parseHeader(chunks[0])!!
        header.messageId shouldBe (70000 and 0xFFFF)
    }

    test("parseHeader returns null for data shorter than header") {
        VoiceChunker.parseHeader(ByteArray(3)) shouldBe null
        VoiceChunker.parseHeader(ByteArray(5)) shouldBe null
    }

    test("parseHeader returns null for wrong version") {
        val chunk = ByteArray(VoiceChunker.HEADER_SIZE)
        chunk[0] = 99.toByte() // wrong version
        VoiceChunker.parseHeader(chunk) shouldBe null
    }

    test("parseHeader succeeds for exactly header-sized data with correct version") {
        val headerOnly = ByteArray(VoiceChunker.HEADER_SIZE)
        headerOnly[0] = VoiceChunker.PROTOCOL_VERSION.toByte()
        VoiceChunker.parseHeader(headerOnly) shouldNotBe null
    }

    test("extractPayload on header-only data returns empty") {
        val headerOnly = ByteArray(VoiceChunker.HEADER_SIZE)
        VoiceChunker.extractPayload(headerOnly) shouldBe ByteArray(0)
    }

    test("too large audio data throws IllegalArgumentException") {
        // 256 * 225 bytes -> 256 chunks -> exceeds max 255
        val audio = ByteArray(256 * VoiceChunker.MAX_PAYLOAD_SIZE)
        shouldThrow<IllegalArgumentException> {
            VoiceChunker.chunkAudio(audio, messageId = 1, bitrate = AmrNbBitrate.MR795)
        }
    }

    test("exactly 255 chunks is allowed") {
        val audio = ByteArray(255 * VoiceChunker.MAX_PAYLOAD_SIZE)
        val chunks = VoiceChunker.chunkAudio(audio, messageId = 1, bitrate = AmrNbBitrate.MR795)
        chunks shouldHaveSize 255
    }

    test("bitrate is correctly encoded and decoded via header") {
        AmrNbBitrate.entries.forEach { bitrate ->
            val audio = ByteArray(10)
            val chunks = VoiceChunker.chunkAudio(audio, messageId = 1, bitrate = bitrate)
            val header = VoiceChunker.parseHeader(chunks[0])!!
            header.bitrate shouldBe bitrate
        }
    }

    test("header size constant is 6") {
        VoiceChunker.HEADER_SIZE shouldBe 6
    }

    test("max payload size is 225") {
        VoiceChunker.MAX_PAYLOAD_SIZE shouldBe 225
    }

    test("protocol version is 1") {
        VoiceChunker.PROTOCOL_VERSION shouldBe 1
    }

    test("stripAmrHeader removes AMR file header") {
        val frames = byteArrayOf(1, 2, 3, 4)
        val withHeader = VoiceChunker.AMR_FILE_HEADER + frames
        VoiceChunker.stripAmrHeader(withHeader) shouldBe frames
    }

    test("stripAmrHeader returns data unchanged when no AMR header") {
        val data = byteArrayOf(1, 2, 3, 4)
        VoiceChunker.stripAmrHeader(data) shouldBe data
    }
})
