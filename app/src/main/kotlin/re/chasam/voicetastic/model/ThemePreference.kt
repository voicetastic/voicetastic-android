package re.chasam.voicetastic.model

import androidx.annotation.StringRes
import re.chasam.voicetastic.R

enum class ThemePreference(@StringRes val labelRes: Int) {
    SYSTEM(R.string.settings_theme_system),
    LIGHT(R.string.settings_theme_light),
    DARK(R.string.settings_theme_dark),
}
