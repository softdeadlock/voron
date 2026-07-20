package messenger.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import messenger.android.R

/**
 * Inter (rsms/inter, SIL OFL) — the geometric grotesque from the reference design. Bundled
 * statics rather than a downloadable font: this app must render identically on de-Googled
 * ROMs with no Google Fonts provider.
 */
val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

// 2026-07-21 rebrand: headlineLarge/Medium switch to the system serif (the site's --display stack
// is a serif system-font list for the same reason InterFontFamily above is bundled rather than
// fetched — no network font source this app can rely on) so the "Voron" wordmark and other big
// display moments (ConnectScreen's title) read as the same typographic identity as the landing
// page, not a second, unrelated brand voice. Everything below headline stays Inter/UI-grade —
// only the handful of true display moments get the serif treatment.
val VoronDisplayFontFamily = FontFamily.Serif

// Inter is designed for slightly negative tracking at UI sizes (its dynamic-metrics table
// suggests ~-1% around 14-16sp) — without it the face reads wider/looser than the reference.
val VoronTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = VoronDisplayFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = VoronDisplayFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 27.sp,
        lineHeight = 33.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp,
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp,
    ),
    // labelMedium/labelSmall carry the site's other borrowed voice: its uppercase mono "eyebrow"
    // labels (section tags, the CORVUS eyebrow over the wordmark). These two roles are exactly
    // that job in the app — timestamps, delivery status, badges — never message body copy, so the
    // monospace switch has no legibility cost where it actually lands.
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
)
