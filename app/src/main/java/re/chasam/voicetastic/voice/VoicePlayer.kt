package re.chasam.voicetastic.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import uniffi.voicetastic.VoiceCodec
import uniffi.voicetastic.codec2Decode
import java.io.File
import java.io.FileOutputStream

/**
 * Plays back audio data from byte arrays or files.
 *
 * - AMR-NB and Opus: temp file + [MediaPlayer].
 * - Codec2: decoded to 8 kHz mono PCM via the Rust bridge, then streamed
 *   through an [AudioTrack] — Android has no native codec for Codec2.
 */
class VoicePlayer {

    companion object {
        private const val TAG = "VoicePlayer"
        private const val CODEC2_SAMPLE_RATE = 8000
    }

    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private var tempFile: File? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    var onCompletion: (() -> Unit)? = null

    /**
     * Serialises teardown so the framework callbacks (onCompletion,
     * onError, onMarkerReached) can't race the UI calling [stop] or a
     * second [play] mid-cleanup. Without this, double-release of the
     * MediaPlayer / AudioTrack is observable, and `isPlaying` can flip
     * inconsistently relative to the actual framework state.
     */
    private val playLock = Any()

    /**
     * Play audio from a byte array.
     *
     * @param audioData codec-encoded bytes (AMR file, OGG for Opus, packed Codec2 frames)
     * @param cacheDir directory for temporary file storage (unused for Codec2)
     * @param codec the codec used for encoding — selects MediaPlayer vs AudioTrack path
     * @param codecParam codec-specific parameter (Codec2 mode for VoiceCodec.Codec2)
     */
    fun play(audioData: ByteArray, cacheDir: File, codec: VoiceCodec, codecParam: Int = 0) {
        stop()

        if (codec is VoiceCodec.Codec2) {
            playCodec2(audioData, codecParam.toUByte())
            return
        }

        try {
            val extension = when (codec) {
                VoiceCodec.Opus -> "ogg"
                else -> "amr"
            }
            val file = File(cacheDir, "playback_${System.currentTimeMillis()}.$extension")
            FileOutputStream(file).use { it.write(audioData) }
            tempFile = file

            val player = MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                Log.i(TAG, "Playback completed")
                finishPlayback(notify = true)
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Playback error: what=$what extra=$extra")
                // Notify the VM so its isPlaying / playingItemId unstick;
                // before this fix the error path tore the player down
                // without firing onCompletion, leaving the UI thinking
                // playback was still in flight forever.
                finishPlayback(notify = true)
                true
            }
            player.prepare()
            player.start()
            mediaPlayer = player
            isPlaying = true
            Log.i(TAG, "Playback started ($codec): ${audioData.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            finishPlayback(notify = true)
        }
    }

    private fun playCodec2(audioData: ByteArray, mode: UByte) {
        val pcm: ShortArray = try {
            codec2Decode(audioData, mode).toShortArray()
        } catch (e: Exception) {
            Log.e(TAG, "Codec2 decode failed", e)
            finishPlayback(notify = true)
            return
        }
        if (pcm.isEmpty()) {
            Log.w(TAG, "Codec2: decoded 0 PCM samples")
            finishPlayback(notify = true)
            return
        }

        // MODE_STATIC: pre-fill the entire decoded blob, then `play()`
        // streams it out at hardware pace. The previous MODE_STREAM
        // implementation released the AudioTrack as soon as `write()`
        // returned — and `write()` returns when bytes are *queued*, not
        // *played* — so any clip got cut off after ~one buffer-fill of
        // audio (often <1 s of the decoded message).
        //
        // The `setNotificationMarkerPosition` callback fires when the
        // hardware playback head actually reaches the end of the buffer.
        // That's the only safe point to release the track.
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(CODEC2_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Codec2: AudioTrack build failed", e)
            finishPlayback(notify = true)
            return
        }
        audioTrack = track

        val written = track.write(pcm, 0, pcm.size)
        if (written != pcm.size) {
            Log.w(TAG, "Codec2: short static write $written/${pcm.size}")
        }

        track.notificationMarkerPosition = pcm.size
        track.setPlaybackPositionUpdateListener(object :
            AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) {
                finishPlayback(notify = true)
            }
            override fun onPeriodicNotification(track: AudioTrack) {}
        })

        isPlaying = true
        track.play()
        Log.i(TAG, "Playback started (Codec2 mode=$mode): ${pcm.size} samples")
    }

    /**
     * Stop playback if currently playing. User-initiated, so does **not**
     * fire [onCompletion] — the caller already knows it asked to stop.
     */
    fun stop() {
        finishPlayback(notify = false)
    }

    /**
     * Release all resources.
     */
    fun release() {
        stop()
    }

    /**
     * Single point of teardown for all paths (user stop, framework
     * completion, framework error, marker reached). Synchronised so the
     * framework callback threads can't race the UI thread and cause
     * double-release of the MediaPlayer / AudioTrack.
     *
     * @param notify if true, invoke [onCompletion] exactly once (used for
     *   framework-driven completion / error / marker paths so the
     *   ViewModel can clear its `isPlaying` flag). Set false for
     *   user-initiated stops.
     */
    private fun finishPlayback(notify: Boolean) {
        val shouldNotify: Boolean
        synchronized(playLock) {
            // Only the first caller wins. If we're already torn down,
            // skip the framework calls and the notification — callbacks
            // arriving after a user-initiated stop must not re-fire
            // onCompletion behind the caller's back. tempFile is checked
            // too so a failed `play()` (file written, but MediaPlayer
            // setup threw) still gets its temp file cleaned up.
            if (!isPlaying && mediaPlayer == null && audioTrack == null && tempFile == null) {
                return
            }
            shouldNotify = notify && isPlaying
            isPlaying = false

            try { mediaPlayer?.stop() } catch (_: Exception) {}
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null

            try { audioTrack?.stop() } catch (_: Exception) {}
            try { audioTrack?.release() } catch (_: Exception) {}
            audioTrack = null

            try { tempFile?.delete() } catch (_: Exception) {}
            tempFile = null
        }
        // Fire the completion callback outside the lock so the listener
        // is free to call back into us (e.g. play the next message).
        if (shouldNotify) {
            try { onCompletion?.invoke() } catch (e: Exception) {
                Log.w(TAG, "onCompletion listener threw", e)
            }
        }
    }
}
