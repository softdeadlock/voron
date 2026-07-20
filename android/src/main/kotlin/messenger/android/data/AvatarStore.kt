package messenger.android.data

/** Persists the user's chosen avatar glyph, encrypted at rest alongside the rest of their profile. */
class AvatarStore(private val store: SecureStore) {
    fun load(): AvatarIconId? = AvatarIconId.fromStorageKey(store.readText()?.trim()?.ifBlank { null })

    fun save(icon: AvatarIconId?) {
        store.writeText(icon?.name.orEmpty())
    }
}
