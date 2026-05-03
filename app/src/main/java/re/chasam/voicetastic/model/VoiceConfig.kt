package re.chasam.voicetastic.model

import android.media.MediaRecorder

/**
 * Configuration for voice messages.
 *
 * @param bitrate AMR-NB bitrate (use MediaRecorder.AudioEncoder AMR_NB bitrate values)
 * @param maxDurationSeconds Maximum recording duration in seconds (1–60)
 * @param chunkTimeoutSeconds Timeout for receiving all chunks of a voice message
 */
data class VoiceConfig(
    val bitrate: AmrNbBitrate = AmrNbBitrate.MR795,
    val maxDurationSeconds: Int = 20,
    val chunkTimeoutSeconds: Int = 30,
    val partialPlayOnTimeout: Boolean = true
)

/**
 * AMR-NB bitrate modes.
 * The actual bitrate in bits/s and approximate bytes/second are listed.
 */
enum class AmrNbBitrate(val bps: Int, val label: String) {
    MR475(4750, "4.75 kbps"),
    MR515(5150, "5.15 kbps"),
    MR59(5900, "5.9 kbps"),
    MR67(6700, "6.7 kbps"),
    MR74(7400, "7.4 kbps"),
    MR795(7950, "7.95 kbps"),
    MR102(10200, "10.2 kbps"),
    MR122(12200, "12.2 kbps");

    /** Approximate bytes per second of encoded audio */
    val bytesPerSecond: Int get() = bps / 8
}

