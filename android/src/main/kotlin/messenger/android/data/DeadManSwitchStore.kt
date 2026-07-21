package messenger.android.data

/** What fires once the configured inactivity window elapses — see [DeadManSwitchStore]. */
enum class DeadManSwitchAction { SEND_MESSAGE, WIPE_APP_DATA }

/**
 * [enabled]/[intervalDays]/[action]/[targetPeerKeyHex] are the armed configuration; [messageText]
 * is only meaningful for [DeadManSwitchAction.SEND_MESSAGE]. [lastActivityMillis] is bumped on
 * every app foreground (see `VoronApplication`'s lifecycle observer) — the switch fires once
 * `now - lastActivityMillis` exceeds [intervalDays], checked by [messenger.android.work.DeadManSwitchWorker].
 */
data class DeadManSwitchConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val action: DeadManSwitchAction = DeadManSwitchAction.SEND_MESSAGE,
    val targetPeerKeyHex: String? = null,
    val messageText: String = "",
    val lastActivityMillis: Long = System.currentTimeMillis(),
)

/**
 * Persists [DeadManSwitchConfig] through [SecureStore] rather than plain SharedPreferences (like
 * most other settings) — [DeadManSwitchConfig.messageText] can be genuinely sensitive ("if you're
 * reading this, I've been detained, do X"), deserving the same at-rest protection as message
 * history rather than living in a plaintext prefs XML.
 *
 * Wire shape (first line is fixed fields, everything after the first newline is the raw message
 * text verbatim — it's the only field that can itself contain newlines/tabs):
 * `enabled\tintervalDays\tactionOrdinal\ttargetPeerKeyHex\tlastActivityMillis\n<messageText>`
 */
class DeadManSwitchStore(private val store: SecureStore) {
    fun load(): DeadManSwitchConfig {
        val text = store.readText() ?: return DeadManSwitchConfig()
        val firstNewline = text.indexOf('\n')
        val header = if (firstNewline >= 0) text.substring(0, firstNewline) else text
        val messageText = if (firstNewline >= 0) text.substring(firstNewline + 1) else ""
        val parts = header.split('\t')
        if (parts.size < 5) return DeadManSwitchConfig()
        return try {
            DeadManSwitchConfig(
                enabled = parts[0].toBoolean(),
                intervalDays = parts[1].toInt(),
                action = DeadManSwitchAction.entries.getOrElse(parts[2].toInt()) { DeadManSwitchAction.SEND_MESSAGE },
                targetPeerKeyHex = parts[3].ifEmpty { null },
                messageText = messageText,
                lastActivityMillis = parts[4].toLong(),
            )
        } catch (e: Exception) {
            DeadManSwitchConfig()
        }
    }

    fun save(config: DeadManSwitchConfig) {
        val header = listOf(
            config.enabled.toString(),
            config.intervalDays.toString(),
            config.action.ordinal.toString(),
            config.targetPeerKeyHex.orEmpty(),
            config.lastActivityMillis.toString(),
        ).joinToString("\t")
        store.writeText("$header\n${config.messageText}")
    }

    /** Bumps [DeadManSwitchConfig.lastActivityMillis] to now, leaving every other field untouched — called on every app foreground. A no-op read+write, not hot-path enough to warrant caching. */
    fun recordActivity() {
        val current = load()
        if (!current.enabled) return
        save(current.copy(lastActivityMillis = System.currentTimeMillis()))
    }
}
