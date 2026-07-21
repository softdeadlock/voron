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

// 2026-07-21: "Noir" — a stricter, near-monochrome dark variant (see ThemeVariant). True black
// rather than ink's soft #0A0A0C, and violet pushed down to a bare whisper (a warm-grey primary
// with only the faintest violet undertone) instead of the vivid brand hue — teal/bronze stay at
// closer to full strength so the theme reads as "quiet", not "colorless". Light mode has no Noir
// counterpart: a strict black theme has nothing to say about a light background, so Theme.kt falls
// back to the ordinary Corvid light scheme whenever the resolved appearance isn't dark.
val NoirBackground = Color(0xFF000000)
val NoirSurface = Color(0xFF0D0D0D)
val NoirSurfaceVariant = Color(0xFF171717)
val NoirTextPrimary = Color(0xFFEDEDED)
val NoirTextSecondary = Color(0xFF7A7A7A)
val NoirDivider = Color(0xFF1C1C1C)

val NoirPrimary = Color(0xFFACA6B2)
val NoirSecondary = Color(0xFF2E7A72)
val NoirTertiary = Color(0xFF8A6B3C)

val NoirPin = NoirTertiary
val NoirVerified = NoirPrimary
val NoirEncrypted = NoirSecondary
val NoirDelivered = Color(0xFF6E6E6E)

// Three more dark-only identities (see ThemeVariant) -- each a genuinely different hue family, not
// a hue-shifted Corvid, so picking between all five actually feels like choosing an identity.

// Ember: coals, not fire -- warm amber primary, rust-red secondary, dull gold tertiary.
val EmberBackground = Color(0xFF120D0A)
val EmberSurface = Color(0xFF1C1410)
val EmberSurfaceVariant = Color(0xFF241A14)
val EmberTextPrimary = Color(0xFFF0E4D8)
val EmberTextSecondary = Color(0xFFA08972)
val EmberDivider = Color(0xFF2E2018)
val EmberPrimary = Color(0xFFE0793E)
val EmberSecondary = Color(0xFFC24A3D)
val EmberTertiary = Color(0xFFD4A24C)
val EmberPin = EmberTertiary
val EmberVerified = EmberPrimary
val EmberEncrypted = EmberSecondary
val EmberDelivered = Color(0xFF8A7562)

// Glacier: ice, not sky -- icy cyan primary, steel-blue secondary, pale mint tertiary.
val GlacierBackground = Color(0xFF060B10)
val GlacierSurface = Color(0xFF0C1620)
val GlacierSurfaceVariant = Color(0xFF12202C)
val GlacierTextPrimary = Color(0xFFDCEBF2)
val GlacierTextSecondary = Color(0xFF7A93A0)
val GlacierDivider = Color(0xFF16283A)
val GlacierPrimary = Color(0xFF4FB8D6)
val GlacierSecondary = Color(0xFF5A7FBE)
val GlacierTertiary = Color(0xFF8FD8C4)
val GlacierPin = GlacierTertiary
val GlacierVerified = GlacierPrimary
val GlacierEncrypted = GlacierSecondary
val GlacierDelivered = Color(0xFF6E7F8A)

// Moss: forest floor, not spring leaves -- moss-green primary, olive secondary, deep teal-green tertiary.
val MossBackground = Color(0xFF0A0F0A)
val MossSurface = Color(0xFF121A12)
val MossSurfaceVariant = Color(0xFF1A2418)
val MossTextPrimary = Color(0xFFE4EBDE)
val MossTextSecondary = Color(0xFF8C9884)
val MossDivider = Color(0xFF202C1C)
val MossPrimary = Color(0xFF6FA05A)
val MossSecondary = Color(0xFF9C8A4E)
val MossTertiary = Color(0xFF4E8C7A)
val MossPin = MossTertiary
val MossVerified = MossPrimary
val MossEncrypted = MossSecondary
val MossDelivered = Color(0xFF7C8874)

// Founder: gold over near-black, exclusive to early-alpha installs (see ThemeVariant.FOUNDER) --
// the other four brand hues (violet/teal/bronze already used elsewhere, plus gold itself) fold in
// as secondary/tertiary rather than introducing a fifth unrelated color.
val FounderBackground = Color(0xFF08080A)
val FounderSurface = Color(0xFF121014)
val FounderSurfaceVariant = Color(0xFF1C181E)
val FounderTextPrimary = Color(0xFFF5EDD9)
val FounderTextSecondary = Color(0xFF9C8F6E)
val FounderDivider = Color(0xFF2A2420)
val FounderPrimary = Color(0xFFE8C468)
val FounderSecondary = Color(0xFF8B6FD6)
val FounderTertiary = Color(0xFF3FA79C)
val FounderPin = FounderPrimary
val FounderVerified = FounderSecondary
val FounderEncrypted = FounderTertiary
val FounderDelivered = Color(0xFF9C8F6E)
