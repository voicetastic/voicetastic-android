package re.chasam.voicetastic.voice

import uniffi.voicetastic.VoiceCodec
import java.io.File

/**
 * Test seam over [VoicePlayer].
 *
 * The concrete class wraps Android's [android.media.MediaPlayer] and
 * [android.media.AudioTrack]; the cleanup race fixed in sprint 1 is
 * the kind of contract that wants its own tests, and those tests need
 * to be able to drive completion / error callbacks without spinning
 * up real audio hardware.
 */
interface VoicePlayerApi {
    val isPlaying: Boolean

    /**
     * Play [audioData]. [onComplete] fires exactly once per call for
     * framework-driven endings (natural completion, error, marker reached, or
     * a setup failure). It is NOT invoked for caller-initiated [stop], nor when
     * this playback is superseded by a later [play] — so a stale callback can
     * never tear down a newer playback. May fire on a framework thread.
     */
    fun play(
        audioData: ByteArray,
        cacheDir: File,
        codec: VoiceCodec,
        codecParam: Int = 0,
        onComplete: (() -> Unit)? = null,
    )

    fun stop()
    fun release()
}
