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
class VoicePlayer : VoicePlayerApi {

    companion object {
        private const val TAG = "VoicePlayer"
        private const val CODEC2_SAMPLE_RATE = 8000
    }

    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private var tempFile: File? = null
    // Per-playback completion callback, set by play() and consumed once by the
    // winning finishPlayback. Guarded by playLock.
    private var currentOnComplete: (() -> Unit)? = null

    @Volatile
    override var isPlaying: Boolean = false
        private set

    /**
     * Serialises setup and teardown so the framework callbacks (completion,
     * error, marker reached) can't race the UI calling [stop] or a second
     * [play] mid-cleanup. Without this, double-release of the MediaPlayer /
     * AudioTrack is observable, and `isPlaying` can flip inconsistently
     * relative to the actual framework state.
     */
    private val playLock = Any()

    /**
     * Play audio from a byte array.
     *
     * @param audioData codec-encoded bytes (AMR file, OGG for Opus, packed Codec2 frames)
     * @param cacheDir directory for temporary file storage (unused for Codec2)
     * @param codec the codec used for encoding — selects MediaPlayer vs AudioTrack path
     * @param codecParam codec-specific parameter (Codec2 mode for VoiceCodec.Codec2)
     * @param onComplete fired once on a framework-driven ending (see [VoicePlayerApi.play])
     */
    override fun play(
        audioData: ByteArray,
        cacheDir: File,
        codec: VoiceCodec,
        codecParam: Int,
        onComplete: (() -> Unit)?,
    ) {
        val failureCb: (() -> Unit)? = synchronized(playLock) {
            // Supersede any current playback silently, then claim the slot.
            finishPlaybackLocked(notify = false, expected = null)
            currentOnComplete = onComplete
            if (codec is VoiceCodec.Codec2) {
                startCodec2Locked(audioData, codecParam.toUByte())
            } else {
                startMediaPlayerLocked(audioData, cacheDir, codec)
            }
        }
        // Fire any setup-failure completion outside the lock so the listener
        // is free to call back into us.
        failureCb?.invoke()
    }

    /** Returns a completion callback to fire if setup failed, else null. Caller holds [playLock]. */
    private fun startMediaPlayerLocked(audioData: ByteArray, cacheDir: File, codec: VoiceCodec): (() -> Unit)? {
        return try {
            val extension = when (codec) {
                VoiceCodec.Opus -> "ogg"
                else -> "amr"
            }
            val file = File(cacheDir, "playback_${System.currentTimeMillis()}.$extension")
            FileOutputStream(file).use { it.write(audioData) }
            tempFile = file

            val player = MediaPlayer()
            // Assign before prepare() so a throw routes teardown through the
            // normal path (and so a listener firing later matches `expected`).
            mediaPlayer = player
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                Log.i(TAG, "Playback completed")
                finishPlayback(notify = true, expected = player)
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Playback error: what=$what extra=$extra")
                // Notify the VM so its isPlaying / playingItemId unstick;
                // before this fix the error path tore the player down
                // without firing onComplete, leaving the UI thinking
                // playback was still in flight forever.
                finishPlayback(notify = true, expected = player)
                true
            }
            player.prepare()
            player.start()
            isPlaying = true
            Log.i(TAG, "Playback started ($codec): ${audioData.size} bytes")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            finishPlaybackLocked(notify = true, expected = null)
        }
    }

    /** Returns a completion callback to fire if setup failed, else null. Caller holds [playLock]. */
    private fun startCodec2Locked(audioData: ByteArray, mode: UByte): (() -> Unit)? {
        val pcm: ShortArray = try {
            codec2Decode(audioData, mode).toShortArray()
        } catch (e: Exception) {
            Log.e(TAG, "Codec2 decode failed", e)
            return finishPlaybackLocked(notify = true, expected = null)
        }
        if (pcm.isEmpty()) {
            Log.w(TAG, "Codec2: decoded 0 PCM samples")
            return finishPlaybackLocked(notify = true, expected = null)
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
            return finishPlaybackLocked(notify = true, expected = null)
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
                finishPlayback(notify = true, expected = track)
            }
            override fun onPeriodicNotification(track: AudioTrack) {}
        })

        isPlaying = true
        track.play()
        Log.i(TAG, "Playback started (Codec2 mode=$mode): ${pcm.size} samples")
        return null
    }

    /**
     * Stop playback if currently playing. User-initiated, so does **not**
     * fire the completion callback — the caller already knows it asked to stop.
     */
    override fun stop() {
        finishPlayback(notify = false, expected = null)
    }

    /**
     * Release all resources.
     */
    override fun release() {
        stop()
    }

    /**
     * Acquire [playLock] and tear down, then fire the completion callback
     * (if any) outside the lock so the listener is free to call back into us.
     */
    private fun finishPlayback(notify: Boolean, expected: Any?) {
        val cb = synchronized(playLock) { finishPlaybackLocked(notify, expected) }
        cb?.invoke()
    }

    /**
     * Single point of teardown for all paths (user stop, framework completion,
     * error, marker reached, setup failure). Caller must hold [playLock].
     * Returns the completion callback to invoke (outside the lock), or null.
     *
     * @param notify if true, return the per-playback completion callback so
     *   the ViewModel can clear its state. False for user-initiated stops.
     * @param expected the MediaPlayer/AudioTrack instance the caller belongs
     *   to. If it is no longer the current instance, this is a stale callback
     *   from an already-superseded/released player — bail without touching the
     *   newer playback.
     */
    private fun finishPlaybackLocked(notify: Boolean, expected: Any?): (() -> Unit)? {
        // Stale callback from a player/track we already tore down or replaced.
        if (expected != null && expected !== mediaPlayer && expected !== audioTrack) {
            return null
        }
        // Already torn down. tempFile is checked too so a failed `play()`
        // (file written, but MediaPlayer setup threw) still gets cleaned up.
        if (!isPlaying && mediaPlayer == null && audioTrack == null && tempFile == null) {
            return null
        }
        val shouldNotify = notify && isPlaying
        isPlaying = false

        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null

        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null

        try { tempFile?.delete() } catch (_: Exception) {}
        tempFile = null

        val cb = if (shouldNotify) currentOnComplete else null
        currentOnComplete = null
        return cb
    }
}
