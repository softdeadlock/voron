package messenger.android.data

/**
 * Persists the contact list as one `nickname\tdeviceKeyHex` line per contact, encrypted at rest
 * via [SecureStore]. Deliberately not a database — this is a test-UI app and the contact list is
 * small.
 */
class ContactStore(private val store: SecureStore) {

    fun load(): List<Contact> {
        val text = store.readText() ?: return emptyList()
        return text.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 9)
                // Older saves predate later columns; missing ones default to false/none rather
                // than downgrading already-known contacts.
                when (parts.size) {
                    9 -> Contact(
                        nickname = parts[0],
                        deviceKeyHex = parts[1],
                        nicknameConfirmed = parts[2] == "1",
                        pinned = parts[3] == "1",
                        verified = parts[4] == "1",
                        hasUnread = parts[5] == "1",
                        avatarIconId = AvatarIconId.fromStorageKey(parts[6].ifEmpty { null }),
                        blocked = parts[7] == "1",
                        disappearAfterMillis = parts[8].toLongOrNull(),
                    )
                    7 -> Contact(
                        nickname = parts[0],
                        deviceKeyHex = parts[1],
                        nicknameConfirmed = parts[2] == "1",
                        pinned = parts[3] == "1",
                        verified = parts[4] == "1",
                        hasUnread = parts[5] == "1",
                        avatarIconId = AvatarIconId.fromStorageKey(parts[6].ifEmpty { null }),
                    )
                    6 -> Contact(
                        nickname = parts[0],
                        deviceKeyHex = parts[1],
                        nicknameConfirmed = parts[2] == "1",
                        pinned = parts[3] == "1",
                        verified = parts[4] == "1",
                        hasUnread = parts[5] == "1",
                    )
                    5 -> Contact(
                        nickname = parts[0],
                        deviceKeyHex = parts[1],
                        nicknameConfirmed = parts[2] == "1",
                        pinned = parts[3] == "1",
                        verified = parts[4] == "1",
                    )
                    4 -> Contact(nickname = parts[0], deviceKeyHex = parts[1], nicknameConfirmed = parts[2] == "1", pinned = parts[3] == "1")
                    3 -> Contact(nickname = parts[0], deviceKeyHex = parts[1], nicknameConfirmed = parts[2] == "1")
                    2 -> Contact(nickname = parts[0], deviceKeyHex = parts[1], nicknameConfirmed = true)
                    else -> null
                }
            }
    }

    fun save(contacts: List<Contact>) {
        store.writeText(
            contacts.joinToString("\n") {
                "${it.nickname}\t${it.deviceKeyHex}\t${if (it.nicknameConfirmed) "1" else "0"}\t" +
                    "${if (it.pinned) "1" else "0"}\t${if (it.verified) "1" else "0"}\t${if (it.hasUnread) "1" else "0"}\t" +
                    "${it.avatarIconId?.name.orEmpty()}\t${if (it.blocked) "1" else "0"}\t${it.disappearAfterMillis?.toString().orEmpty()}"
            },
        )
    }
}
