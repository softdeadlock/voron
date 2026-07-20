package messenger.android.data

/** User's preferred appearance — [SYSTEM] follows the OS day/night setting. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromStorageKey(key: String?): ThemeMode = entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}
