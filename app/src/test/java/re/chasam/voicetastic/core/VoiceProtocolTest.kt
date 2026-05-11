package re.chasam.voicetastic.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Locks in the [VoiceProtocol] constants against the Rust
 * `voice/consts.rs` and `voice/mod.rs` source of truth so that any drift
 * is caught before it reaches the wire.
 */
class VoiceProtocolTest : FunSpec({

    test("protocol version byte is 0x01") {
        VoiceProtocol.PROTOCOL_VERSION shouldBe 0x01
    }

    test("v1 wire geometry") {
        VoiceProtocol.V1_HEADER_SIZE shouldBe 6
        VoiceProtocol.V1_MAX_PACKET_SIZE shouldBe 231
        VoiceProtocol.V1_MAX_PAYLOAD_SIZE shouldBe 225
        // Sanity: payload + header == packet
        (VoiceProtocol.V1_MAX_PAYLOAD_SIZE + VoiceProtocol.V1_HEADER_SIZE) shouldBe
            VoiceProtocol.V1_MAX_PACKET_SIZE
    }

    test("v2 wire geometry mirrors the Rust core consts") {
        VoiceProtocol.V2_HEADER_SIZE shouldBe 12
        VoiceProtocol.V2_MAX_PACKET_SIZE shouldBe 231
        VoiceProtocol.V2_GCM_NONCE_LEN shouldBe 12
        VoiceProtocol.V2_GCM_TAG_LEN shouldBe 16
        VoiceProtocol.V2_MIN_CHUNK_SIZE shouldBe 16
        VoiceProtocol.V2_MAX_BODY_SIZE shouldBe 219
        VoiceProtocol.V2_MAX_CHUNKS_PER_MESSAGE shouldBe 255
        VoiceProtocol.V2_MAX_PARITY_PER_MESSAGE shouldBe 128
        VoiceProtocol.V2_MAX_MESSAGE_BYTES shouldBe 255 * 219
        VoiceProtocol.V2_MAX_IN_PROGRESS_PER_SENDER shouldBe 4
        VoiceProtocol.V2_MAX_IN_PROGRESS_GLOBAL shouldBe 64
        VoiceProtocol.V2_BLACKLIST_TTL_MS shouldBe 600_000L
        VoiceProtocol.V2_BLACKLIST_MAX shouldBe 100
        VoiceProtocol.V2_NACK_WINDOW_MS shouldBe 1_500L
        VoiceProtocol.V2_NACK_MAX_ROUNDS shouldBe 32
    }

    test("detectVersion returns first byte, null on empty") {
        VoiceProtocol.detectVersion(byteArrayOf(0x01, 0, 0)) shouldBe 0x01
        VoiceProtocol.detectVersion(byteArrayOf(0x99.toByte(), 0, 0)) shouldBe 0x99
        VoiceProtocol.detectVersion(byteArrayOf()).shouldBeNull()
    }
})
