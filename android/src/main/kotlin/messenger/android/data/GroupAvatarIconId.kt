package messenger.android.data

/**
 * A built-in icon glyph for a group's avatar, distinct from [AvatarIconId]'s personal set — these
 * are about gathering/belonging rather than an individual bird, though still drawn from the same
 * raven vocabulary (a flock of ravens is traditionally called a "murder"). Null/0 (the default)
 * falls back to a generic group icon — see [messenger.android.ui.Avatar].
 */
enum class GroupAvatarIconId {
    NEST, MURDER, BRANCH, LANTERN, KNOT, COMPASS;

    companion object {
        fun fromStorageKey(key: String?): GroupAvatarIconId? = entries.firstOrNull { it.name == key }

        /** Inverse of [wireValue] — decodes [messenger.common.group.GroupState.avatarIconId]. */
        fun fromWireValue(value: Int): GroupAvatarIconId? = if (value <= 0) null else entries.getOrNull(value - 1)
    }
}

/** The int this icon (or its absence) travels as inside [messenger.common.group.GroupState.avatarIconId] — 0 means "no icon, show the generic group glyph", same "0 means none" convention as [messenger.android.data.wireValue] for personal avatars. */
val GroupAvatarIconId?.wireValue: Int
    get() = this?.let { it.ordinal + 1 } ?: 0
