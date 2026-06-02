// Generated using MaterialKolor Builder version 1.3.0 (103)
// https://materialkolor.com/?color_seed=FFFFBDA8&color_primary=FFFFBDA8&dark_mode=true&style=Neutral&color_spec=SPEC_2025
package re.chasam.voicetastic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import re.chasam.voicetastic.model.ThemePreference

private val lightColorScheme = lightColorScheme(
    primary = PrimaryLightHighContrast,
    onPrimary = OnPrimaryLightHighContrast,
    primaryContainer = PrimaryContainerLightHighContrast,
    onPrimaryContainer = OnPrimaryContainerLightHighContrast,
    inversePrimary = InversePrimaryLightHighContrast,
    secondary = SecondaryLightHighContrast,
    onSecondary = OnSecondaryLightHighContrast,
    secondaryContainer = SecondaryContainerLightHighContrast,
    onSecondaryContainer = OnSecondaryContainerLightHighContrast,
    tertiary = TertiaryLightHighContrast,
    onTertiary = OnTertiaryLightHighContrast,
    tertiaryContainer = TertiaryContainerLightHighContrast,
    onTertiaryContainer = OnTertiaryContainerLightHighContrast,
    background = BackgroundLightHighContrast,
    onBackground = OnBackgroundLightHighContrast,
    surface = SurfaceLightHighContrast,
    onSurface = OnSurfaceLightHighContrast,
    surfaceVariant = SurfaceVariantLightHighContrast,
    onSurfaceVariant = OnSurfaceVariantLightHighContrast,
    surfaceTint = SurfaceTintLightHighContrast,
    inverseSurface = InverseSurfaceLightHighContrast,
    inverseOnSurface = InverseOnSurfaceLightHighContrast,
    error = ErrorLightHighContrast,
    onError = OnErrorLightHighContrast,
    errorContainer = ErrorContainerLightHighContrast,
    onErrorContainer = OnErrorContainerLightHighContrast,
    outline = OutlineLightHighContrast,
    outlineVariant = OutlineVariantLightHighContrast,
    scrim = ScrimLightHighContrast,
    surfaceBright = SurfaceBrightLightHighContrast,
    surfaceContainer = SurfaceContainerLightHighContrast,
    surfaceContainerHigh = SurfaceContainerHighLightHighContrast,
    surfaceContainerHighest = SurfaceContainerHighestLightHighContrast,
    surfaceContainerLow = SurfaceContainerLowLightHighContrast,
    surfaceContainerLowest = SurfaceContainerLowestLightHighContrast,
    surfaceDim = SurfaceDimLightHighContrast,
    primaryFixed = PrimaryFixedHighContrast,
    primaryFixedDim = PrimaryFixedDimHighContrast,
    onPrimaryFixed = OnPrimaryFixedHighContrast,
    onPrimaryFixedVariant = OnPrimaryFixedVariantHighContrast,
    secondaryFixed = SecondaryFixedHighContrast,
    secondaryFixedDim = SecondaryFixedDimHighContrast,
    onSecondaryFixed = OnSecondaryFixedHighContrast,
    onSecondaryFixedVariant = OnSecondaryFixedVariantHighContrast,
    tertiaryFixed = TertiaryFixedHighContrast,
    tertiaryFixedDim = TertiaryFixedDimHighContrast,
    onTertiaryFixed = OnTertiaryFixedHighContrast,
    onTertiaryFixedVariant = OnTertiaryFixedVariantHighContrast,
)

private val darkColorScheme = darkColorScheme(
    primary = PrimaryDarkHighContrast,
    onPrimary = OnPrimaryDarkHighContrast,
    primaryContainer = PrimaryContainerDarkHighContrast,
    onPrimaryContainer = OnPrimaryContainerDarkHighContrast,
    inversePrimary = InversePrimaryDarkHighContrast,
    secondary = SecondaryDarkHighContrast,
    onSecondary = OnSecondaryDarkHighContrast,
    secondaryContainer = SecondaryContainerDarkHighContrast,
    onSecondaryContainer = OnSecondaryContainerDarkHighContrast,
    tertiary = TertiaryDarkHighContrast,
    onTertiary = OnTertiaryDarkHighContrast,
    tertiaryContainer = TertiaryContainerDarkHighContrast,
    onTertiaryContainer = OnTertiaryContainerDarkHighContrast,
    background = BackgroundDarkHighContrast,
    onBackground = OnBackgroundDarkHighContrast,
    surface = SurfaceDarkHighContrast,
    onSurface = OnSurfaceDarkHighContrast,
    surfaceVariant = SurfaceVariantDarkHighContrast,
    onSurfaceVariant = OnSurfaceVariantDarkHighContrast,
    surfaceTint = SurfaceTintDarkHighContrast,
    inverseSurface = InverseSurfaceDarkHighContrast,
    inverseOnSurface = InverseOnSurfaceDarkHighContrast,
    error = ErrorDarkHighContrast,
    onError = OnErrorDarkHighContrast,
    errorContainer = ErrorContainerDarkHighContrast,
    onErrorContainer = OnErrorContainerDarkHighContrast,
    outline = OutlineDarkHighContrast,
    outlineVariant = OutlineVariantDarkHighContrast,
    scrim = ScrimDarkHighContrast,
    surfaceBright = SurfaceBrightDarkHighContrast,
    surfaceContainer = SurfaceContainerDarkHighContrast,
    surfaceContainerHigh = SurfaceContainerHighDarkHighContrast,
    surfaceContainerHighest = SurfaceContainerHighestDarkHighContrast,
    surfaceContainerLow = SurfaceContainerLowDarkHighContrast,
    surfaceContainerLowest = SurfaceContainerLowestDarkHighContrast,
    surfaceDim = SurfaceDimDarkHighContrast,
    primaryFixed = PrimaryFixedHighContrast,
    primaryFixedDim = PrimaryFixedDimHighContrast,
    onPrimaryFixed = OnPrimaryFixedHighContrast,
    onPrimaryFixedVariant = OnPrimaryFixedVariantHighContrast,
    secondaryFixed = SecondaryFixedHighContrast,
    secondaryFixedDim = SecondaryFixedDimHighContrast,
    onSecondaryFixed = OnSecondaryFixedHighContrast,
    onSecondaryFixedVariant = OnSecondaryFixedVariantHighContrast,
    tertiaryFixed = TertiaryFixedHighContrast,
    tertiaryFixedDim = TertiaryFixedDimHighContrast,
    onTertiaryFixed = OnTertiaryFixedHighContrast,
    onTertiaryFixedVariant = OnTertiaryFixedVariantHighContrast,
)

@Composable
fun AppTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val colorScheme = if (darkTheme) darkColorScheme else lightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
