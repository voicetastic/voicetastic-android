package re.chasam.voicetastic.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import re.chasam.voicetastic.model.VoiceConfig
import java.io.File

/**
 * Records audio in AMR-NB format with configurable bitrate and max duration.
 */
class VoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false

    /**
     * Start recording audio in AMR-NB format.
     * @param config voice configuration (bitrate, max duration)
     * @return the output file where audio will be saved, or null on failure
     */
    fun startRecording(config: VoiceConfig): File? {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return outputFile
        }

        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.amr")
        outputFile = file

        try {
            recorder = createMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setAudioEncodingBitRate(config.bitrate.bps)
                setAudioSamplingRate(8000) // AMR-NB always uses 8kHz
                setMaxDuration(config.maxDurationSeconds * 1000)
                setOutputFile(file.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Log.i(TAG, "Max duration reached, stopping recording")
                        stopRecording()
                    }
                }
                prepare()
                start()
            }
            isRecording = true
            Log.i(TAG, "Recording started: ${config.bitrate.label}, max ${config.maxDurationSeconds}s")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            return null
        }
    }

    /**
     * Stop recording and return the recorded audio file.
     * @return the recorded AMR-NB file, or null if not recording
     */
    fun stopRecording(): File? {
        if (!isRecording) return null

        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            isRecording = false
            Log.i(TAG, "Recording stopped: ${outputFile?.length() ?: 0} bytes")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            cleanup()
            null
        }
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    private fun cleanup() {
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        isRecording = false
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }
}

