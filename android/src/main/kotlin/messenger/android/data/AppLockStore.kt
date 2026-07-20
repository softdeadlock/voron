package messenger.android.data

/**
 * Persists whether the app-lock (biometric/PIN gate) is enabled — deliberately encrypted at rest
 * via [SecureStore], not plain SharedPreferences like the rest of [SettingsStore]: a rooted or
 * malware-compromised device that can read arbitrary app files could otherwise just flip this one
 * plaintext boolean to disable the lock screen entirely, without ever having to defeat biometrics.
 */
class AppLockStore(private val store: SecureStore) {
    fun load(): Boolean = store.readText() == "1"

    fun save(enabled: Boolean) {
        store.writeText(if (enabled) "1" else "0")
    }
}
