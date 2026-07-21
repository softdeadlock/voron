package messenger.android.data

import messenger.android.R

/**
 * A bundled sticker, referenced by a small integer over the wire (see
 * [messenger.common.client.ApplicationMessage]'s `stickerId` field) rather than shipping any image
 * bytes — every build carries the same fixed art, so an ID is all a peer needs to render it. Mirrors
 * [AvatarIconId]/[GroupAvatarIconId]'s pattern for the same reason: keep the wire format ignorant of
 * the actual art.
 *
 * Sliced from a green-screen Midjourney sheet (not a dark background) — that first attempt's die-cut
 * was rough (leftover background fragments, ghost bleed from neighboring cells) and got scrapped
 * entirely rather than patched. `CORVUS_*` is the general pack; `ALPHA_*` is the "Voron vs. the paper
 * airplane" pack, exclusive to Founder-flagged installs (see `AppState.isFounder`) both to send *and*
 * to receive — a non-founder peer who gets sent one still falls back gracefully (see
 * [fromWireValue]'s `null` case, a generic placeholder) rather than crashing on an unknown ID.
 */
enum class StickerId(val drawableRes: Int, val founderOnly: Boolean = false) {
    CORVUS_00(R.drawable.sticker_corvus_00),
    CORVUS_01(R.drawable.sticker_corvus_01),
    CORVUS_02(R.drawable.sticker_corvus_02),
    CORVUS_03(R.drawable.sticker_corvus_03),
    CORVUS_04(R.drawable.sticker_corvus_04),
    CORVUS_05(R.drawable.sticker_corvus_05),
    CORVUS_06(R.drawable.sticker_corvus_06),
    CORVUS_07(R.drawable.sticker_corvus_07),

    ALPHA_00(R.drawable.sticker_alpha_00, founderOnly = true),
    ALPHA_01(R.drawable.sticker_alpha_01, founderOnly = true),
    ALPHA_02(R.drawable.sticker_alpha_02, founderOnly = true),
    ALPHA_03(R.drawable.sticker_alpha_03, founderOnly = true),
    ALPHA_04(R.drawable.sticker_alpha_04, founderOnly = true),
    ALPHA_05(R.drawable.sticker_alpha_05, founderOnly = true),
    ALPHA_06(R.drawable.sticker_alpha_06, founderOnly = true),
    ALPHA_07(R.drawable.sticker_alpha_07, founderOnly = true),
    ;

    companion object {
        /** Inverse of [wireValue] — the byte an incoming [messenger.common.client.ApplicationMessage.Decoded.stickerId] carries. Null on an out-of-range/unrecognized ID (an older or newer build's sticker set). */
        fun fromWireValue(value: Int?): StickerId? = if (value == null) null else entries.getOrNull(value)
    }
}

/** The byte [StickerId] travels as inside [messenger.common.client.ApplicationMessage] — see [StickerId.fromWireValue]. */
val StickerId.wireValue: Int
    get() = ordinal
