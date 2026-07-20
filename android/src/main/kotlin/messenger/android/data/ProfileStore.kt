package messenger.android.data

/** Persists the user's own display name, encrypted at rest — the only thing sent (encrypted) to peers to identify us. */
class ProfileStore(private val store: SecureStore) {
    fun load(): String? = store.readText()?.trim()?.ifBlank { null }

    fun save(displayName: String) {
        store.writeText(displayName.trim())
    }
}
