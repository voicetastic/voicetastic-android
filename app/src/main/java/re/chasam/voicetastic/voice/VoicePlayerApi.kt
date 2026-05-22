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
     * Invoked exactly once per playback for framework-driven endings
     * (natural completion, error, marker reached). NOT invoked for
     * caller-initiated [stop] — that path is silent on purpose so the
     * UI can clear its `isPlaying` flag once at the stop call site.
     */
    var onCompletion: (() -> Unit)?

    fun play(audioData: ByteArray, cacheDir: File, codec: VoiceCodec, codecParam: Int = 0)
    fun stop()
    fun release()
}
