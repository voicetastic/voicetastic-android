package re.chasam.voicetastic.core

/**
 * Voice-protocol constants shared by builder and assembler.
 *
 * Mirrors `voicetastic-core/src/voice/consts.rs` from
 * <https://git.cha-sam.re/acarteron/voicetastic-desktop>.
 *
 * The Android codebase now delegates the wire protocol entirely to the
 * Rust core (UniFFI-bound via the `voicetastic-android-bridge` crate);
 * these constants are retained as a convenient Kotlin-side reference for
 * higher layers (e.g. capacity planning, UI hints) and to assert
 * cross-language agreement in tests. **Source of truth lives in Rust.**
 */
object VoiceProtocol {

    /**
     * Protocol version byte placed at offset 0 of every PRIVATE_APP payload.
     *
     * Receivers MUST drop any frame whose first byte is not [PROTOCOL_VERSION].
     */
    const val PROTOCOL_VERSION: Int = 0x01

    // ---- v1 wire (current Android implementation) --------------------------

    /** v1 header size in bytes. */
    const val V1_HEADER_SIZE: Int = 6

    /** v1 maximum chunk size (header + payload) that fits in one Meshtastic packet. */
    const val V1_MAX_PACKET_SIZE: Int = 231

    /** v1 maximum payload bytes per chunk = [V1_MAX_PACKET_SIZE] − [V1_HEADER_SIZE]. */
    const val V1_MAX_PAYLOAD_SIZE: Int = V1_MAX_PACKET_SIZE - V1_HEADER_SIZE

    // ---- v2 wire (Rust core integration target) ---------------------------

    /** v2 header size in bytes. */
    const val V2_HEADER_SIZE: Int = 12

    /** v2 absolute maximum packet size (Meshtastic LoRa MTU). */
    const val V2_MAX_PACKET_SIZE: Int = 231

    /** v2 GCM nonce length, bytes. */
    const val V2_GCM_NONCE_LEN: Int = 12

    /** v2 GCM auth-tag length, bytes. */
    const val V2_GCM_TAG_LEN: Int = 16

    /** v2 minimum chunk size, bytes. */
    const val V2_MIN_CHUNK_SIZE: Int = 16

    /** v2 maximum body bytes per frame ([V2_MAX_PACKET_SIZE] − [V2_HEADER_SIZE]). */
    const val V2_MAX_BODY_SIZE: Int = V2_MAX_PACKET_SIZE - V2_HEADER_SIZE

    /** v2 hard cap on data chunks per message (`total_data` field is `u8`). */
    const val V2_MAX_CHUNKS_PER_MESSAGE: Int = 255

    /** v2 hard cap on parity (Reed-Solomon) chunks per message. */
    const val V2_MAX_PARITY_PER_MESSAGE: Int = 128

    /** v2 hard cap on total reassembled audio size, bytes. */
    const val V2_MAX_MESSAGE_BYTES: Int = V2_MAX_CHUNKS_PER_MESSAGE * V2_MAX_BODY_SIZE

    /** v2 maximum in-flight assemblies per sender. */
    const val V2_MAX_IN_PROGRESS_PER_SENDER: Int = 4

    /** v2 maximum in-flight assemblies across all senders. */
    const val V2_MAX_IN_PROGRESS_GLOBAL: Int = 64

    /** v2 completed-message blacklist TTL (ms). */
    const val V2_BLACKLIST_TTL_MS: Long = 600_000L

    /** v2 completed-message blacklist max size. */
    const val V2_BLACKLIST_MAX: Int = 100

    /** v2 NACK quiet-window before re-requesting missing chunks (ms). */
    const val V2_NACK_WINDOW_MS: Long = 1_500L

    /** v2 max NACK rounds before giving up on a message. */
    const val V2_NACK_MAX_ROUNDS: Int = 32

    /**
     * Returns the protocol version byte of a PRIVATE_APP payload, or `null`
     * if the buffer is empty. Mirrors Rust `voice::detect_version`.
     */
    fun detectVersion(bytes: ByteArray): Int? =
        if (bytes.isEmpty()) null else bytes[0].toInt() and 0xFF
}
