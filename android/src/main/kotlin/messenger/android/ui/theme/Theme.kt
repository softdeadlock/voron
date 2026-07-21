package messenger.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import messenger.android.data.ThemeMode
import messenger.android.data.ThemeVariant

/** Status-icon colors (pin/verified/encrypted/delivered) have no dedicated Material color-scheme role, so each variant gets one of these alongside its [ColorScheme] — see [LocalVoronStatusColors]. */
data class VoronStatusColors(val pin: Color, val verified: Color, val encrypted: Color, val delivered: Color)

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
private val VoronLightStatusColors = VoronStatusColors(VoronPin, VoronVerified, VoronEncrypted, VoronDelivered)

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
private val VoronDarkStatusColors = VoronStatusColors(VoronPinDark, VoronVerifiedDark, VoronEncryptedDark, VoronDeliveredDark)

// Every variant below is dark-only — see ThemeVariant's doc for why: none of them has anything
// distinct to say about a light background, so [VoronTheme] falls back to VoronLightColors above
// whenever the resolved appearance isn't dark, regardless of which variant is selected.

private val NoirDarkColors = darkColorScheme(
    primary = NoirPrimary, onPrimary = NoirBackground,
    secondary = NoirSecondary, onSecondary = NoirBackground,
    tertiary = NoirTertiary, onTertiary = NoirBackground,
    background = NoirBackground, onBackground = NoirTextPrimary,
    surface = NoirSurface, onSurface = NoirTextPrimary,
    surfaceVariant = NoirSurfaceVariant, onSurfaceVariant = NoirTextSecondary,
    outline = NoirDivider, error = VoronErrorDark,
)
private val NoirStatusColors = VoronStatusColors(NoirPin, NoirVerified, NoirEncrypted, NoirDelivered)

private val EmberDarkColors = darkColorScheme(
    primary = EmberPrimary, onPrimary = EmberBackground,
    secondary = EmberSecondary, onSecondary = EmberBackground,
    tertiary = EmberTertiary, onTertiary = EmberBackground,
    background = EmberBackground, onBackground = EmberTextPrimary,
    surface = EmberSurface, onSurface = EmberTextPrimary,
    surfaceVariant = EmberSurfaceVariant, onSurfaceVariant = EmberTextSecondary,
    outline = EmberDivider, error = VoronErrorDark,
)
private val EmberStatusColors = VoronStatusColors(EmberPin, EmberVerified, EmberEncrypted, EmberDelivered)

private val GlacierDarkColors = darkColorScheme(
    primary = GlacierPrimary, onPrimary = GlacierBackground,
    secondary = GlacierSecondary, onSecondary = GlacierBackground,
    tertiary = GlacierTertiary, onTertiary = GlacierBackground,
    background = GlacierBackground, onBackground = GlacierTextPrimary,
    surface = GlacierSurface, onSurface = GlacierTextPrimary,
    surfaceVariant = GlacierSurfaceVariant, onSurfaceVariant = GlacierTextSecondary,
    outline = GlacierDivider, error = VoronErrorDark,
)
private val GlacierStatusColors = VoronStatusColors(GlacierPin, GlacierVerified, GlacierEncrypted, GlacierDelivered)

private val MossDarkColors = darkColorScheme(
    primary = MossPrimary, onPrimary = MossBackground,
    secondary = MossSecondary, onSecondary = MossBackground,
    tertiary = MossTertiary, onTertiary = MossBackground,
    background = MossBackground, onBackground = MossTextPrimary,
    surface = MossSurface, onSurface = MossTextPrimary,
    surfaceVariant = MossSurfaceVariant, onSurfaceVariant = MossTextSecondary,
    outline = MossDivider, error = VoronErrorDark,
)
private val MossStatusColors = VoronStatusColors(MossPin, MossVerified, MossEncrypted, MossDelivered)

private val FounderDarkColors = darkColorScheme(
    primary = FounderPrimary, onPrimary = FounderBackground,
    secondary = FounderSecondary, onSecondary = FounderBackground,
    tertiary = FounderTertiary, onTertiary = FounderBackground,
    background = FounderBackground, onBackground = FounderTextPrimary,
    surface = FounderSurface, onSurface = FounderTextPrimary,
    surfaceVariant = FounderSurfaceVariant, onSurfaceVariant = FounderTextSecondary,
    outline = FounderDivider, error = VoronErrorDark,
)
private val FounderStatusColors = VoronStatusColors(FounderPin, FounderVerified, FounderEncrypted, FounderDelivered)

private val DarkVariants: Map<ThemeVariant, Pair<ColorScheme, VoronStatusColors>> = mapOf(
    ThemeVariant.CORVID to (VoronDarkColors to VoronDarkStatusColors),
    ThemeVariant.NOIR to (NoirDarkColors to NoirStatusColors),
    ThemeVariant.EMBER to (EmberDarkColors to EmberStatusColors),
    ThemeVariant.GLACIER to (GlacierDarkColors to GlacierStatusColors),
    ThemeVariant.MOSS to (MossDarkColors to MossStatusColors),
    ThemeVariant.FOUNDER to (FounderDarkColors to FounderStatusColors),
)

/** Whether the app is currently rendering in dark mode — resolves [ThemeMode.SYSTEM] against the OS setting. */
val LocalVoronDarkTheme = staticCompositionLocalOf { false }

/** The active [VoronStatusColors] for pin/verified/encrypted/delivered icons — see [VoronTheme]. */
val LocalVoronStatusColors = staticCompositionLocalOf { VoronLightStatusColors }

@Composable
fun VoronTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themeVariant: ThemeVariant = ThemeVariant.CORVID,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val (colors, statusColors) = if (darkTheme) {
        DarkVariants.getValue(themeVariant)
    } else {
        VoronLightColors to VoronLightStatusColors
    }
    CompositionLocalProvider(LocalVoronDarkTheme provides darkTheme, LocalVoronStatusColors provides statusColors) {
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
fun voronPinColor(): Color = LocalVoronStatusColors.current.pin

/** Fixed color for every verified-identity icon in the app — see [VoronVerified]. */
@Composable
fun voronVerifiedColor(): Color = LocalVoronStatusColors.current.verified

/** Fixed color for every "session encrypted" icon in the app — see [VoronEncrypted]. */
@Composable
fun voronEncryptedColor(): Color = LocalVoronStatusColors.current.encrypted

/** Fixed color for every "message delivered" icon in the app — see [VoronDelivered]. */
@Composable
fun voronDeliveredColor(): Color = LocalVoronStatusColors.current.delivered

/**
 * The gradient every "brand accent" surface uses (outgoing message bubbles, avatars, the QR card,
 * the input bar's send button) — pulled from the *active* [MaterialTheme.colorScheme] instead of a
 * fixed color list, so switching [ThemeVariant] actually reads as a different identity everywhere
 * at once, not just in Settings' own preview swatches.
 */
@Composable
fun voronAccentGradient(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.secondary,
    MaterialTheme.colorScheme.tertiary,
)

/** This variant's [primary, secondary, tertiary] swatch, for rendering a theme picker — not composition-local, since a picker shows every option regardless of which one is currently active. */
fun ThemeVariant.previewColors(): List<Color> = when (this) {
    ThemeVariant.CORVID -> listOf(VoronViolet, VoronTeal, VoronBronze)
    ThemeVariant.NOIR -> listOf(NoirPrimary, NoirSecondary, NoirTertiary)
    ThemeVariant.EMBER -> listOf(EmberPrimary, EmberSecondary, EmberTertiary)
    ThemeVariant.GLACIER -> listOf(GlacierPrimary, GlacierSecondary, GlacierTertiary)
    ThemeVariant.MOSS -> listOf(MossPrimary, MossSecondary, MossTertiary)
    ThemeVariant.FOUNDER -> listOf(FounderPrimary, FounderSecondary, FounderTertiary)
}

/** This variant's background swatch, for rendering a theme picker. */
fun ThemeVariant.previewBackground(): Color = when (this) {
    ThemeVariant.CORVID -> VoronDarkBackground
    ThemeVariant.NOIR -> NoirBackground
    ThemeVariant.EMBER -> EmberBackground
    ThemeVariant.GLACIER -> GlacierBackground
    ThemeVariant.MOSS -> MossBackground
    ThemeVariant.FOUNDER -> FounderBackground
}

/** Display name for [ThemeVariant], for the theme picker. */
val ThemeVariant.displayName: String
    get() = when (this) {
        ThemeVariant.CORVID -> "Corvid"
        ThemeVariant.NOIR -> "Noir"
        ThemeVariant.EMBER -> "Ember"
        ThemeVariant.GLACIER -> "Glacier"
        ThemeVariant.MOSS -> "Moss"
        ThemeVariant.FOUNDER -> "Founder"
    }
