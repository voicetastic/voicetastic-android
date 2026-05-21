package re.chasam.voicetastic.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class ThemePreferenceStore(context: Context) {

    companion object {
        private const val TAG = "ThemePreferenceStore"
        private const val PREFS_NAME = "ui_prefs"
        private const val K_THEME = "theme"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ThemePreference {
        val name = prefs.getString(K_THEME, null) ?: return ThemePreference.SYSTEM
        return runCatching { enumValueOf<ThemePreference>(name) }.getOrElse {
            Log.w(TAG, "unknown theme '$name'; falling back to SYSTEM")
            ThemePreference.SYSTEM
        }
    }

    fun save(preference: ThemePreference) {
        prefs.edit().putString(K_THEME, preference.name).apply()
    }
}
