package re.chasam.voicetastic.model

/**
 * Voice codec choice for recording.
 */
enum class VoiceCodecChoice {
    AmrNb, Opus, Codec2
}

/**
 * Configuration for voice messages.
 *
 * @param codec Codec to use (AMR-NB, Opus, or Codec2)
 * @param bitrate AMR-NB bitrate (use MediaRecorder.AudioEncoder AMR_NB bitrate values); used when codec=AmrNb
 * @param opusBitrateKbps Opus encoder bitrate in kbps (6–16 range); used when codec=Opus
 * @param codec2Mode Codec2 mode (0=3200, 1=2400, 2=1600, 3=1400, 4=1300, 5=1200); used when codec=Codec2
 * @param maxDurationSeconds Maximum recording duration in seconds (1–60)
 * @param chunkTimeoutSeconds Timeout for receiving all chunks of a voice message
 */
data class VoiceConfig(
    val codec: VoiceCodecChoice = VoiceCodecChoice.AmrNb,
    val bitrate: AmrNbBitrate = AmrNbBitrate.MR795,
    val opusBitrateKbps: Int = 12,
    val codec2Mode: Codec2Mode = Codec2Mode.MODE_3200,
    val maxDurationSeconds: Int = 20,
    val chunkTimeoutSeconds: Int = 30,
    val partialPlayOnTimeout: Boolean = true
)

/**
 * Codec2 bitrate modes. The ordinal maps 1:1 to the Rust bridge's mode byte
 * (0..5). All modes are 8 kHz mono.
 */
enum class Codec2Mode(val bps: Int, val label: String, val samplesPerFrame: Int, val bytesPerFrame: Int) {
    MODE_3200(3200, "3.2 kbps", 160, 8),
    MODE_2400(2400, "2.4 kbps", 160, 6),
    MODE_1600(1600, "1.6 kbps", 320, 8),
    MODE_1400(1400, "1.4 kbps", 320, 7),
    MODE_1300(1300, "1.3 kbps", 320, 7),
    MODE_1200(1200, "1.2 kbps", 320, 6);

    val bytesPerSecond: Int get() = bps / 8
}

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
