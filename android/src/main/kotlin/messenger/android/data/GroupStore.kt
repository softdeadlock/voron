package messenger.android.data

import java.util.Base64
import messenger.common.group.GroupControlEvent

/**
 * Persists every group's control-event log (see [messenger.common.group.GroupControlLog]) as
 * base64 lines through [SecureStore], one `groupIdHex\tbase64(event)` line per known event. The
 * current membership/role/settings snapshot is never stored — it's always recomputed by replaying
 * these events on load, so there's exactly one source of truth and no chance of a stored snapshot
 * drifting from the log it was derived from.
 *
 * Sender-key/ratchet material is deliberately NOT persisted here (or anywhere), same as 1:1
 * ratchet state — a restart re-derives group sessions lazily via GROUP_KEY_REQUEST. Only the
 * durable, replayable control history lives on disk.
 */
class GroupStore(private val store: SecureStore) {

    /** Returns all persisted events grouped by their group id (hex). Order within each group is preserved as written; [messenger.common.group.GroupControlLog] re-canonicalizes regardless of order. */
    fun load(): Map<String, List<GroupControlEvent>> {
        val text = store.readText() ?: return emptyMap()
        val byGroup = LinkedHashMap<String, MutableList<GroupControlEvent>>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2) continue
            val event = runCatching { GroupControlEvent.decode(Base64.getDecoder().decode(parts[1])) }.getOrNull() ?: continue
            byGroup.getOrPut(parts[0]) { mutableListOf() }.add(event)
        }
        return byGroup
    }

    fun save(eventsByGroup: Map<String, List<GroupControlEvent>>) {
        val lines = buildList {
            for ((groupIdHex, events) in eventsByGroup) {
                for (event in events) {
                    add("$groupIdHex\t${Base64.getEncoder().encodeToString(event.encode())}")
                }
            }
        }
        store.writeText(lines.joinToString("\n"))
    }
}
