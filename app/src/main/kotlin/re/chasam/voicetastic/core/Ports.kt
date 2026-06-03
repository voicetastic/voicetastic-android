package re.chasam.voicetastic.core

/**
 * Meshtastic application port numbers used by Voicetastic.
 *
 * Wire-compatible with the upstream Rust core
 * (`crates/voicetastic-core/src/ports.rs`).
 */
object Ports {
    /** Plain UTF-8 text chat. */
    const val TEXT_MESSAGE_APP: Int = 1

    /** Position broadcast (read-only). */
    const val POSITION_APP: Int = 3

    /** Node info beacons (read-only). */
    const val NODEINFO_APP: Int = 4

    /** Config / channel / owner writes & device actions. */
    const val ADMIN_APP: Int = 6

    /** Voice chunks. See [VoiceProtocol]. */
    const val PRIVATE_APP: Int = 256

    /**
     * Maximum accepted UTF-8 text payload size (bytes).
     *
     * Meshtastic firmware caps text messages around 237 bytes; we accept a
     * bit more to tolerate future bumps but reject anything obviously
     * oversized to bound memory use. Used by both the inbound decoder and
     * the outbound `sendText` guard.
     */
    const val MAX_TEXT_BYTES: Int = 1024
}

