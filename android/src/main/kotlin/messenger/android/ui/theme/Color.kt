package messenger.android.ui.theme

import androidx.compose.ui.graphics.Color

// 2026-07-21 rebrand: ports the marketing site's "corvid feather iridescence" identity
// (violet/teal/bronze over warm near-black ink, cream "paper" for display text) into the app
// itself, replacing the earlier generic violet/charcoal scheme. One fixed set of accent hues is
// shared by both themes — light mode is "ink on paper", dark mode is "paper on ink" — rather than
// each theme getting its own brighter/dimmer variant of the brand colors.
val VoronViolet = Color(0xFF8B6FD6)
val VoronTeal = Color(0xFF3FA79C)
val VoronBronze = Color(0xFFB48A4E)

// Light ("paper") theme: a warm parchment ground, not a flat white — keeps the editorial,
// slightly bookish feel of the site's dark mode even where the OS forces a light background.
val VoronBackground = Color(0xFFF3F0E9)
val VoronSurface = Color(0xFFFBF9F5)
val VoronSurfaceVariant = Color(0xFFEAE5D9)

val VoronTextPrimary = Color(0xFF1C1A16)
val VoronTextSecondary = Color(0xFF6E685F)
val VoronDivider = Color(0xFFDCD5C6)
val VoronPlaceholderBar = Color(0xFFE9E3D4)

// Dark ("ink") theme: matches the site's --ink/--ink-2/--paper/--muted/--rule tokens directly, so
// the app and the landing page read as the same object rather than two different products.
val VoronDarkBackground = Color(0xFF0A0A0C)
val VoronDarkSurface = Color(0xFF101014)
val VoronDarkSurfaceVariant = Color(0xFF17171C)
val VoronDarkTextPrimary = Color(0xFFE7E3DC)
val VoronDarkTextSecondary = Color(0xFF8D8894)
val VoronDarkDivider = Color(0xFF232228)

val VoronError = Color(0xFFB84A3E)
val VoronErrorDark = Color(0xFFE08276)

val VoronAvatarGradient = listOf(VoronViolet, VoronTeal, VoronBronze)

// Deliberately neutral (not accent-tinted) so bottom sheets read as a distinct floating layer
// rather than blending into the page's own background/surface.
val VoronSheetBackground = Color(0xFFEAE6DC)
val VoronSheetBackgroundDark = Color(0xFF17171C)

// Decorative icon circles/tints *inside* sheets (avatar placeholders, purely functional action
// icons) — kept neutral so they don't compete with either the sheet's primary button or the
// semantic status colors below.
val VoronNeutralIconContainer = Color(0xFFDDD7C9)
val VoronNeutralIconContainerDark = Color(0xFF232228)
val VoronNeutralIcon = Color(0xFF6E685F)
val VoronNeutralIconDark = Color(0xFFB0ABA0)

// Status icons each draw from the same three brand hues instead of reaching for unrelated
// colors (the old scheme's blue "verified" and green "encrypted" belonged to no particular
// palette) — pin/verified/encrypted/delivered now read as one family, not four accidents.
val VoronPin = VoronBronze
val VoronPinDark = Color(0xFFCBA268)
val VoronVerified = VoronViolet
val VoronVerifiedDark = Color(0xFFA894E3)
val VoronEncrypted = VoronTeal
val VoronEncryptedDark = Color(0xFF5CC0B4)
// Deliberately the quiet neutral, not a fourth accent hue: "delivered" is a passive, low-emphasis
// state (unlike pin/verified/encrypted, which are things a user actively cares about noticing).
val VoronDelivered = Color(0xFF8D8577)
val VoronDeliveredDark = Color(0xFF8D8894)
