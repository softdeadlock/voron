package messenger.android.ui.theme

import androidx.compose.ui.graphics.Color

// Deep violet/indigo palette (matches the reference inbox mockup): near-black purple
// background, a brighter violet used for selection/accent surfaces, and a vivid violet
// primary for buttons/badges/links.
val VoronBackground = Color(0xFFF4F2FA)
val VoronSurface = Color(0xFFFFFFFF)
val VoronSurfaceVariant = Color(0xFFE9E4F7)

val VoronTextPrimary = Color(0xFF1C1533)
val VoronTextSecondary = Color(0xFF6B6285)
val VoronDivider = Color(0xFFDED6F0)
val VoronPlaceholderBar = Color(0xFFE3DCF5)

// 2026-07-19 rebrand: a slightly deeper, more blue-leaning violet than the original 7059C9 — reads
// as a jewel-toned "corvid feather" hue rather than a flat purple, and gives the launcher icon's
// blue/teal gradient (ic_launcher_foreground.xml) a real point of contact with the in-app accent
// instead of belonging to an unrelated palette. Still an evolution of the same violet, not a
// different hue family.
val VoronViolet = Color(0xFF6C4FE0)
val VoronVioletDark = Color(0xFF5A3FC4)
val VoronLavender = Color(0xFF9B8CE6)
val VoronLavenderDark = Color(0xFF7C6BD4)

// Modern dark scheme: a very dark, slightly blue-tinted charcoal ground (#0F1419) with a distinctly
// lighter card surface (#1A1F2E) and a lighter-still variant for incoming bubbles/chips — enough
// step between the three that surfaces read as layered rather than flat, matching the 2025 refresh.
val VoronDarkBackground = Color(0xFF0F1419)
val VoronDarkSurface = Color(0xFF1A1F2E)
val VoronDarkSurfaceVariant = Color(0xFF262D3D)
val VoronDarkTextPrimary = Color(0xFFE8EAED)
val VoronDarkTextSecondary = Color(0xFF8A8D93)
val VoronDarkDivider = Color(0xFF262D3D)

val VoronError = Color(0xFFDC2626)
val VoronErrorDark = Color(0xFFF87171)

val VoronAvatarGradient = listOf(VoronViolet, VoronLavender)

// Deliberately neutral gray (not violet-tinted) so bottom sheets read as a distinct floating
// layer rather than blending into the page's own colored background/surface.
val VoronSheetBackground = Color(0xFFE7E6EA)
val VoronSheetBackgroundDark = Color(0xFF242229)

// Decorative icon circles/tints *inside* sheets (avatar placeholders, purely functional action
// icons) — kept neutral gray so they don't compete with either the sheet's primary button or the
// semantic status colors below.
val VoronNeutralIconContainer = Color(0xFFD6D4DA)
val VoronNeutralIconContainerDark = Color(0xFF34323C)
val VoronNeutralIcon = Color(0xFF5B5966)
val VoronNeutralIconDark = Color(0xFFB6B4BE)

// Status icons carry their own fixed meaning across the whole app (pin, verified, encrypted,
// delivered) instead of all sharing the violet accent or all going flat gray — each gets one
// consistent, recognizable color everywhere it appears.
val VoronPin = Color(0xFFB07C00)
val VoronPinDark = Color(0xFFFBBF24)
val VoronVerified = Color(0xFF2563EB)
val VoronVerifiedDark = Color(0xFF60A5FA)
// Calmer, less vivid green than a full 4ADE80 ("чуть меньше зелёного") — still clearly the
// "encrypted/seen" green, just less saturated so it doesn't shout on the dark ground.
val VoronEncrypted = Color(0xFF2F9E63)
val VoronEncryptedDark = Color(0xFF5CC98C)
val VoronDelivered = Color(0xFF0D9488)
val VoronDeliveredDark = Color(0xFF2DD4BF)
