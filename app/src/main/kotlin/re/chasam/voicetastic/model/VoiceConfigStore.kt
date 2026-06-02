package re.chasam.voicetastic.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * SharedPreferences-backed persistence for [VoiceConfig].
 *
 * Held flat in a dedicated prefs file (`voice_config`) so the keys are
 * self-contained and easy to wipe in isolation. Loaded once at app
 * start; saved on every change to the in-memory `MutableStateFlow`.
 *
 * The Rust bridge has its own `SettingsApi` that covers most voice
 * settings via the typed `SettingKey` enum, but it doesn't have entries
 * for `partialPlayOnTimeout` or `noiseSuppressionEnabled`, and wiring
 * those would mean a cross-repo Rust change. Keeping the whole config
 * here in one place is simpler and matches the app's pattern of owning
 * voice config locally.
 */
class VoiceConfigStore(context: Context) {

    companion object {
        private const val TAG = "VoiceConfigStore"
        private const val PREFS_NAME = "voice_config"

        private const val K_CODEC = "codec"
        private const val K_AMRNB_BITRATE = "amrnb_bitrate"
        private const val K_OPUS_BITRATE_KBPS = "opus_bitrate_kbps"
        private const val K_CODEC2_MODE = "codec2_mode"
        private const val K_MAX_DURATION_SECS = "max_duration_secs"
        private const val K_CHUNK_TIMEOUT_SECS = "chunk_timeout_secs"
        private const val K_PARTIAL_PLAY_ON_TIMEOUT = "partial_play_on_timeout"
        private const val K_NOISE_SUPPRESSION_ENABLED = "noise_suppression_enabled"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Load a [VoiceConfig] from prefs, falling back to default values for
     * any missing or corrupted entries. Never throws — a bad prefs file
     * shouldn't keep the app from starting.
     */
    fun load(): VoiceConfig {
        val default = VoiceConfig()
        return try {
            VoiceConfig(
                codec = enumByNameOr(prefs.getString(K_CODEC, null), default.codec),
                bitrate = enumByNameOr(prefs.getString(K_AMRNB_BITRATE, null), default.bitrate),
                opusBitrateKbps = prefs.getInt(K_OPUS_BITRATE_KBPS, default.opusBitrateKbps),
                codec2Mode = enumByNameOr(prefs.getString(K_CODEC2_MODE, null), default.codec2Mode),
                maxDurationSeconds = prefs.getInt(K_MAX_DURATION_SECS, default.maxDurationSeconds),
                chunkTimeoutSeconds = prefs.getInt(K_CHUNK_TIMEOUT_SECS, default.chunkTimeoutSeconds),
                partialPlayOnTimeout = prefs.getBoolean(K_PARTIAL_PLAY_ON_TIMEOUT, default.partialPlayOnTimeout),
                noiseSuppressionEnabled = prefs.getBoolean(K_NOISE_SUPPRESSION_ENABLED, default.noiseSuppressionEnabled),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "load failed, falling back to defaults", t)
            default
        }
    }

    /** Persist a snapshot. Uses `apply` (async) — writes are best-effort. */
    fun save(config: VoiceConfig) {
        prefs.edit()
            .putString(K_CODEC, config.codec.name)
            .putString(K_AMRNB_BITRATE, config.bitrate.name)
            .putInt(K_OPUS_BITRATE_KBPS, config.opusBitrateKbps)
            .putString(K_CODEC2_MODE, config.codec2Mode.name)
            .putInt(K_MAX_DURATION_SECS, config.maxDurationSeconds)
            .putInt(K_CHUNK_TIMEOUT_SECS, config.chunkTimeoutSeconds)
            .putBoolean(K_PARTIAL_PLAY_ON_TIMEOUT, config.partialPlayOnTimeout)
            .putBoolean(K_NOISE_SUPPRESSION_ENABLED, config.noiseSuppressionEnabled)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumByNameOr(name: String?, default: E): E {
        if (name == null) return default
        return runCatching { enumValueOf<E>(name) }.getOrElse {
            Log.w(TAG, "unknown enum value '$name' for ${E::class.java.simpleName}; using default")
            default
        }
    }
}
