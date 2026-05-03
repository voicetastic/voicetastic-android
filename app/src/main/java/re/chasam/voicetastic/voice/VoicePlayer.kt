package re.chasam.voicetastic.voice

import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Plays back AMR-NB audio data from byte arrays or files.
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
     * Play AMR-NB audio from a byte array.
     * Writes to a temporary file and plays via MediaPlayer.
     *
     * @param audioData AMR-NB file bytes (must include AMR header)
     * @param cacheDir directory for temporary file storage
     */
    fun play(audioData: ByteArray, cacheDir: File) {
        stop()

        try {
            // Write audio data to temp file
            val file = File(cacheDir, "playback_${System.currentTimeMillis()}.amr")
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
            Log.i(TAG, "Playback started: ${audioData.size} bytes")
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
