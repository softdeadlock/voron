package messenger.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import messenger.android.data.ThemeMode

private val VoronLightColors = lightColorScheme(
    primary = VoronViolet,
    onPrimary = VoronSurface,
    secondary = VoronTeal,
    onSecondary = VoronSurface,
    tertiary = VoronBronze,
    onTertiary = VoronSurface,
    background = VoronBackground,
    onBackground = VoronTextPrimary,
    surface = VoronSurface,
    onSurface = VoronTextPrimary,
    surfaceVariant = VoronSurfaceVariant,
    onSurfaceVariant = VoronTextSecondary,
    outline = VoronDivider,
    error = VoronError,
)

private val VoronDarkColors = darkColorScheme(
    primary = VoronViolet,
    onPrimary = VoronDarkBackground,
    secondary = VoronTeal,
    onSecondary = VoronDarkBackground,
    tertiary = VoronBronze,
    onTertiary = VoronDarkBackground,
    background = VoronDarkBackground,
    onBackground = VoronDarkTextPrimary,
    surface = VoronDarkSurface,
    onSurface = VoronDarkTextPrimary,
    surfaceVariant = VoronDarkSurfaceVariant,
    onSurfaceVariant = VoronDarkTextSecondary,
    outline = VoronDarkDivider,
    error = VoronErrorDark,
)

/** Whether the app is currently rendering in dark mode — resolves [ThemeMode.SYSTEM] against the OS setting. */
val LocalVoronDarkTheme = staticCompositionLocalOf { false }

@Composable
fun VoronTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (darkTheme) VoronDarkColors else VoronLightColors
    CompositionLocalProvider(LocalVoronDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colors,
            typography = VoronTypography,
            content = content,
        )
    }
}

/** Neutral gray container color for [androidx.compose.material3.ModalBottomSheet]s — see [VoronSheetBackground]. */
@Composable
fun voronSheetContainerColor(): Color = if (LocalVoronDarkTheme.current) VoronSheetBackgroundDark else VoronSheetBackground

/** Background for decorative icon circles inside a sheet — see [VoronNeutralIconContainer]. */
@Composable
fun voronNeutralIconContainerColor(): Color = if (LocalVoronDarkTheme.current) VoronNeutralIconContainerDark else VoronNeutralIconContainer

/** Tint for decorative icons inside a sheet — see [VoronNeutralIconContainer]. */
@Composable
fun voronNeutralIconTint(): Color = if (LocalVoronDarkTheme.current) VoronNeutralIconDark else VoronNeutralIcon

/** Fixed color for every pin icon in the app — see [VoronPin]. */
@Composable
fun voronPinColor(): Color = if (LocalVoronDarkTheme.current) VoronPinDark else VoronPin

/** Fixed color for every verified-identity icon in the app — see [VoronVerified]. */
@Composable
fun voronVerifiedColor(): Color = if (LocalVoronDarkTheme.current) VoronVerifiedDark else VoronVerified

/** Fixed color for every "session encrypted" icon in the app — see [VoronEncrypted]. */
@Composable
fun voronEncryptedColor(): Color = if (LocalVoronDarkTheme.current) VoronEncryptedDark else VoronEncrypted

/** Fixed color for every "message delivered" icon in the app — see [VoronDelivered]. */
@Composable
fun voronDeliveredColor(): Color = if (LocalVoronDarkTheme.current) VoronDeliveredDark else VoronDelivered
