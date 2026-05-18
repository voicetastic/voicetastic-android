package re.chasam.voicetastic.voice

import android.media.MediaPlayer
import android.util.Log
import uniffi.voicetastic.VoiceCodec
import java.io.File
import java.io.FileOutputStream

/**
 * Plays back audio data (AMR-NB or Opus) from byte arrays or files.
 */
class VoicePlayer {

    companion object {
        private const val TAG = "VoicePlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: File? = null

    var isPlaying: Boolean = false
        private set

    var onCompletion: (() -> Unit)? = null

    /**
     * Play audio from a byte array.
     * Writes to a temporary file and plays via MediaPlayer.
     *
     * @param audioData codec-encoded file bytes (AMR with header, OGG for Opus, etc.)
     * @param cacheDir directory for temporary file storage
     * @param codec the codec used for encoding (determines temp file extension)
     */
    fun play(audioData: ByteArray, cacheDir: File, codec: VoiceCodec) {
        stop()

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
                this.isPlaying = false
                onCompletion?.invoke()
                cleanup()
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Playback error: what=$what extra=$extra")
                this.isPlaying = false
                cleanup()
                true
            }
            player.prepare()
            player.start()
            mediaPlayer = player
            isPlaying = true
            Log.i(TAG, "Playback started ($codec): ${audioData.size} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio", e)
            cleanup()
        }
    }

    /**
     * Play AMR-NB audio from a file.
     */
    fun playFile(file: File) {
        stop()

        try {
            val player = MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                this.isPlaying = false
                onCompletion?.invoke()
                cleanup()
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Playback error: what=$what extra=$extra")
                this.isPlaying = false
                cleanup()
                true
            }
            player.prepare()
            player.start()
            mediaPlayer = player
            isPlaying = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play file", e)
            cleanup()
        }
    }

    /**
     * Stop playback if currently playing.
     */
    fun stop() {
        if (isPlaying) {
            try {
                mediaPlayer?.stop()
            } catch (_: Exception) {}
        }
        cleanup()
        isPlaying = false
    }

    /**
     * Release all resources.
     */
    fun release() {
        stop()
    }

    private fun cleanup() {
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        try {
            tempFile?.delete()
        } catch (_: Exception) {}
        tempFile = null
    }
}
