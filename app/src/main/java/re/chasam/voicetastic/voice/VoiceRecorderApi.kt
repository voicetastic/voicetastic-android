package re.chasam.voicetastic.voice

import re.chasam.voicetastic.model.VoiceConfig
import java.io.File

/**
 * Test seam over [VoiceRecorder].
 *
 * The concrete class wraps Android's [android.media.MediaRecorder] and
 * [android.media.AudioRecord] — both notoriously hard to mock (final
 * classes with native bindings, framework-thread callbacks). Exposing
 * just the behaviour we depend on through this interface lets the
 * ViewModel tests drive a fake recorder deterministically: simulate
 * start → max-duration callback → stop, or start → user-stop → empty
 * file, without ever touching the framework.
 *
 * Implementations must follow the contract documented on
 * [VoiceRecorder.stopRecording]: stop is synchronous w.r.t. file flush
 * — callers can read the returned file immediately.
 */
interface VoiceRecorderApi {
    fun startRecording(config: VoiceConfig): File?
    fun stopRecording(): File?
    fun isCurrentlyRecording(): Boolean
}
