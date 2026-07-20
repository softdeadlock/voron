package messenger.android.data

/** A user-chosen icon glyph for their own avatar, in place of their nickname's initial — see [messenger.android.ui.Avatar] for the actual icons, all raven/bird-themed. Null (the default) means "show the initial letter". */
enum class AvatarIconId {
    RAVEN, WING, FEATHER, FLOCK, MOON, EYE;

    companion object {
        fun fromStorageKey(key: String?): AvatarIconId? = entries.firstOrNull { it.name == key }

        /** Inverse of [wireValue] — the byte an incoming [messenger.common.client.IncomingMessage.senderAvatarIcon] carries. */
        fun fromWireValue(value: Int): AvatarIconId? = if (value <= 0) null else entries.getOrNull(value - 1)
    }
}

/** The byte this icon (or the absence of one) travels as inside [messenger.common.client.ApplicationMessage] — 0 means "no icon, show the initial letter", so it survives round-tripping through peers on an older build too. */
val AvatarIconId?.wireValue: Int
    get() = this?.let { it.ordinal + 1 } ?: 0
