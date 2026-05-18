package re.chasam.voicetastic.model

/**
 * Voice codec choice for recording.
 */
enum class VoiceCodecChoice {
    AmrNb, Opus
}

/**
 * Configuration for voice messages.
 *
 * @param codec Codec to use (AMR-NB or Opus)
 * @param bitrate AMR-NB bitrate (use MediaRecorder.AudioEncoder AMR_NB bitrate values); used when codec=AmrNb
 * @param opusBitrateKbps Opus encoder bitrate in kbps (6–16 range); used when codec=Opus
 * @param maxDurationSeconds Maximum recording duration in seconds (1–60)
 * @param chunkTimeoutSeconds Timeout for receiving all chunks of a voice message
 */
data class VoiceConfig(
    val codec: VoiceCodecChoice = VoiceCodecChoice.AmrNb,
    val bitrate: AmrNbBitrate = AmrNbBitrate.MR795,
    val opusBitrateKbps: Int = 12,
    val maxDurationSeconds: Int = 20,
    val chunkTimeoutSeconds: Int = 30,
    val partialPlayOnTimeout: Boolean = true
)

/**
 * AMR-NB bitrate modes.
 * The actual bitrate in bits/s and approximate bytes/second are listed.
 * Each 20 ms frame has a fixed size (including 1-byte frame header) per mode.
 */
enum class AmrNbBitrate(val bps: Int, val label: String, val frameSizeBytes: Int) {
    MR475(4750, "4.75 kbps", 13),
    MR515(5150, "5.15 kbps", 14),
    MR59(5900, "5.9 kbps", 16),
    MR67(6700, "6.7 kbps", 18),
    MR74(7400, "7.4 kbps", 20),
    MR795(7950, "7.95 kbps", 21),
    MR102(10200, "10.2 kbps", 27),
    MR122(12200, "12.2 kbps", 32);

    /** Approximate bytes per second of encoded audio */
    val bytesPerSecond: Int get() = bps / 8

    /** Number of 20 ms frames that fit in a given number of bytes */
    fun framesIn(bytes: Int): Int = if (frameSizeBytes > 0) bytes / frameSizeBytes else 0
}
