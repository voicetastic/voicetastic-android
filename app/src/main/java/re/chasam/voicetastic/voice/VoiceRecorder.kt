package re.chasam.voicetastic.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import re.chasam.voicetastic.model.VoiceConfig
import re.chasam.voicetastic.model.VoiceCodecChoice
import uniffi.voicetastic.Codec2Encoder
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Records audio in AMR-NB, Opus, or Codec2 format.
 *
 * AMR-NB and Opus use Android's `MediaRecorder` (container files); Codec2
 * captures raw PCM via [AudioRecord] and feeds it through the Rust
 * [Codec2Encoder] one frame at a time, writing packed Codec2 bytes to disk.
 */
class VoiceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceRecorder"
        /** Codec2 always operates on 8 kHz mono 16-bit PCM. */
        private const val CODEC2_SAMPLE_RATE = 8000
        /** Max time to wait for the Codec2 worker to flush on stop. */
        private const val CODEC2_DRAIN_TIMEOUT_MS = 5_000L
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    /** Serialises stopRecording / cleanup so the max-duration callback can't race the user. */
    private val stopLock = Any()

    // Codec2 path state — only one of (recorder, codec2State) is live at once.
    private var codec2State: Codec2RecordingState? = null

    private class Codec2RecordingState(
        val audioRecord: AudioRecord,
        val encoder: Codec2Encoder,
        val output: FileOutputStream,
        val file: File,
        val noiseSuppressor: NoiseSuppressor?,
        val gainControl: AutomaticGainControl?,
        @Volatile var keepRunning: Boolean = true,
        var workerThread: Thread? = null,
    )

    /**
     * Start recording audio (AMR-NB, Opus, or Codec2 depending on config).
     * @param config voice configuration (codec, bitrate, max duration)
     * @return the output file where audio will be saved, or null on failure
     */
    fun startRecording(config: VoiceConfig): File? {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return outputFile
        }

        return when (config.codec) {
            VoiceCodecChoice.AmrNb -> startRecordingAmrNb(config)
            VoiceCodecChoice.Opus -> startRecordingOpus(config)
            VoiceCodecChoice.Codec2 -> startRecordingCodec2(config)
        }
    }

    private fun startRecordingAmrNb(config: VoiceConfig): File? {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.amr")
        outputFile = file

        try {
            recorder = createMediaRecorder().apply {
                // VOICE_COMMUNICATION routes through the OS VoIP capture
                // pipeline — most devices apply hardware NS / AGC on this
                // source. Honour the user's noise-suppression preference;
                // when disabled, fall back to raw MIC.
                setAudioSource(audioSourceFor(config))
                setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setAudioEncodingBitRate(config.bitrate.bps)
                setAudioSamplingRate(8000)
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
            Log.i(TAG, "Recording started: AMR-NB ${config.bitrate.label}, max ${config.maxDurationSeconds}s")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AMR-NB recording", e)
            synchronized(stopLock) { cleanupLocked() }
            return null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingCodec2(config: VoiceConfig): File? {
        val mode = config.codec2Mode
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.c2")
        outputFile = file

        // AudioRecord buffer: pick the larger of the framework minimum and the
        // codec's frame size — we read in whole-frame chunks so the encoder
        // never sees a partial frame.
        val minBuf = AudioRecord.getMinBufferSize(
            CODEC2_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "Codec2: AudioRecord.getMinBufferSize returned $minBuf")
            return null
        }
        val frameSamples = mode.samplesPerFrame
        // Use a multi-frame buffer so reads are efficient. Round up.
        val framesPerRead = maxOf(1, minBuf / 2 / frameSamples)
        val samplesPerRead = framesPerRead * frameSamples
        val bytesPerRead = samplesPerRead * 2 // i16 = 2 bytes
        val audioRecordBufSize = maxOf(minBuf, bytesPerRead * 4)

        val audioRecord = try {
            AudioRecord(
                // VOICE_COMMUNICATION engages the OS VoIP capture pipeline
                // (hardware NS / AGC on most devices). Honour the user's
                // toggle; when off, capture raw mic.
                audioSourceFor(config),
                CODEC2_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                audioRecordBufSize,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Codec2: AudioRecord construction failed", e)
            return null
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Codec2: AudioRecord uninitialized (state=${audioRecord.state})")
            audioRecord.release()
            return null
        }

        // Belt-and-suspenders: attach NoiseSuppressor and AutomaticGainControl
        // to the capture session. Only when the user hasn't opted out — if
        // they want raw audio, we want the effect handles to stay null so
        // nothing is processed at all. `isAvailable()` guards against
        // devices that don't expose the effect.
        val noiseSuppressionOn = config.noiseSuppressionEnabled
        val sessionId = audioRecord.audioSessionId
        val noiseSuppressor = if (noiseSuppressionOn && NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }
                .onFailure { Log.w(TAG, "Codec2: NoiseSuppressor attach failed", it) }
                .getOrNull()
        } else {
            if (noiseSuppressionOn) Log.d(TAG, "Codec2: NoiseSuppressor not available on this device")
            null
        }
        val gainControl = if (noiseSuppressionOn && AutomaticGainControl.isAvailable()) {
            runCatching { AutomaticGainControl.create(sessionId)?.apply { enabled = true } }
                .onFailure { Log.w(TAG, "Codec2: AutomaticGainControl attach failed", it) }
                .getOrNull()
        } else {
            if (noiseSuppressionOn) Log.d(TAG, "Codec2: AutomaticGainControl not available on this device")
            null
        }

        val encoder = try {
            Codec2Encoder(mode.ordinal.toUByte())
        } catch (e: Exception) {
            Log.e(TAG, "Codec2: encoder construction failed", e)
            noiseSuppressor?.release()
            gainControl?.release()
            audioRecord.release()
            return null
        }

        val out = try {
            FileOutputStream(file)
        } catch (e: Exception) {
            Log.e(TAG, "Codec2: cannot open output file", e)
            noiseSuppressor?.release()
            gainControl?.release()
            audioRecord.release()
            encoder.close()
            return null
        }

        val state = Codec2RecordingState(
            audioRecord = audioRecord,
            encoder = encoder,
            output = out,
            file = file,
            noiseSuppressor = noiseSuppressor,
            gainControl = gainControl,
        )
        codec2State = state

        try {
            audioRecord.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Codec2: AudioRecord.startRecording failed", e)
            codec2State = null
            out.close()
            noiseSuppressor?.release()
            gainControl?.release()
            audioRecord.release()
            encoder.close()
            return null
        }

        isRecording = true

        // Worker thread: read PCM in whole-frame chunks, encode, write to file.
        // Self-terminates on max duration so the user doesn't have to poll.
        //
        // The worker owns the FileOutputStream lifetime: it closes the stream
        // in its own `finally` so stopRecording() just has to join() and then
        // the file is guaranteed to be fully flushed. Closing the stream from
        // the caller thread (as we used to) raced with the worker still
        // writing on the encoder thread → truncated voice files.
        val maxDurationMs = config.maxDurationSeconds * 1000L
        state.workerThread = thread(name = "Codec2RecorderWorker", start = true) {
            val pcmBuf = ShortArray(samplesPerRead)
            val startMs = System.currentTimeMillis()
            try {
                while (state.keepRunning) {
                    val read = audioRecord.read(pcmBuf, 0, samplesPerRead, AudioRecord.READ_BLOCKING)
                    if (read <= 0) {
                        if (read != 0) Log.w(TAG, "Codec2: AudioRecord.read returned $read")
                        break
                    }
                    // Trim to whole frames. AudioRecord may short-read, drop the tail.
                    val wholeFrames = read / frameSamples
                    if (wholeFrames == 0) continue
                    val usable = wholeFrames * frameSamples
                    val pcmList = pcmBuf.copyOf(usable).asList()
                    try {
                        val encoded = encoder.encode(pcmList)
                        out.write(encoded)
                    } catch (e: Exception) {
                        Log.e(TAG, "Codec2: encode/write failed", e)
                        break
                    }
                    if (System.currentTimeMillis() - startMs >= maxDurationMs) {
                        Log.i(TAG, "Codec2: max duration reached")
                        break
                    }
                }
            } finally {
                try { out.flush() } catch (_: Exception) {}
                try { out.close() } catch (_: Exception) {}
            }
        }

        Log.i(TAG, "Recording started: Codec2 ${mode.label}, max ${config.maxDurationSeconds}s")
        return file
    }

    private fun startRecordingOpus(config: VoiceConfig): File? {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.ogg")
        outputFile = file

        try {
            recorder = createMediaRecorder().apply {
                // Same audio source as AMR-NB (VOICE_COMMUNICATION when
                // noise suppression is on, raw MIC otherwise).
                setAudioSource(audioSourceFor(config))
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioEncodingBitRate(config.opusBitrateKbps * 1000)
                setAudioSamplingRate(8000)
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
            Log.i(TAG, "Recording started: Opus narrowband ${config.opusBitrateKbps} kbps, max ${config.maxDurationSeconds}s")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Opus recording", e)
            synchronized(stopLock) { cleanupLocked() }
            return null
        }
    }

    /**
     * Stop recording and return the recorded audio file.
     *
     * Blocks until the encoder has flushed the last frame to disk, so the
     * caller can safely read the file as soon as this returns. For the
     * Codec2 path that means joining the worker thread (which owns the
     * output stream); for AMR-NB / Opus, `MediaRecorder.stop()` is
     * synchronous by contract.
     *
     * Idempotent: a second call (e.g. user-stop racing the max-duration
     * callback on the MediaRecorder thread) returns null without touching
     * any framework objects.
     *
     * @return the recorded audio file, or null if not currently recording
     */
    fun stopRecording(): File? = synchronized(stopLock) {
        if (!isRecording) return null
        isRecording = false

        // Codec2 path: signal worker to stop, join (worker closes its own
        // output stream in finally), then release framework resources.
        codec2State?.let { state ->
            state.keepRunning = false
            try {
                state.workerThread?.join(CODEC2_DRAIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {}
            if (state.workerThread?.isAlive == true) {
                // Worker stuck in AudioRecord.read or encode; file may be
                // truncated. Surface this so the caller can decide.
                Log.w(TAG, "Codec2: worker did not finish within ${CODEC2_DRAIN_TIMEOUT_MS}ms")
            }
            try { state.audioRecord.stop() } catch (_: Exception) {}
            // Release audiofx effects before the AudioRecord — they hold
            // references to its session, and releasing AudioRecord first
            // can race with the effects' internal teardown on some OEMs.
            try { state.noiseSuppressor?.release() } catch (_: Exception) {}
            try { state.gainControl?.release() } catch (_: Exception) {}
            try { state.audioRecord.release() } catch (_: Exception) {}
            try { state.encoder.close() } catch (_: Exception) {}
            codec2State = null
            Log.i(TAG, "Recording stopped (Codec2): ${state.file.length()} bytes")
            return state.file
        }

        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            Log.i(TAG, "Recording stopped: ${outputFile?.length() ?: 0} bytes")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
            cleanupLocked()
            null
        }
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    /**
     * Best-effort teardown used only on error paths. Caller must hold
     * [stopLock] (or be running before any worker was spawned).
     *
     * Does not join the Codec2 worker — error paths typically happen
     * before the worker is even started, and on later errors we accept
     * that the worker may still be exiting while we tear down its
     * dependencies (the worker's own try/finally closes its file).
     */
    private fun cleanupLocked() {
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        codec2State?.let { state ->
            state.keepRunning = false
            try { state.noiseSuppressor?.release() } catch (_: Exception) {}
            try { state.gainControl?.release() } catch (_: Exception) {}
            try { state.audioRecord.release() } catch (_: Exception) {}
            try { state.encoder.close() } catch (_: Exception) {}
        }
        codec2State = null
        isRecording = false
    }

    /**
     * Returns the AudioSource to use for capture given the user's noise
     * suppression preference. `VOICE_COMMUNICATION` engages the OS VoIP
     * capture pipeline (HAL-level NS / AGC on most devices); `MIC` is
     * the raw signal.
     */
    private fun audioSourceFor(config: VoiceConfig): Int =
        if (config.noiseSuppressionEnabled) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
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

