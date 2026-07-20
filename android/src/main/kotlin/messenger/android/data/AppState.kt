package messenger.android.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import messenger.common.client.MessengerClient
import messenger.common.util.toHex

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val ownKeyHex: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/** UI-visible state of the current call, if any — set directly by [messenger.android.data.CallManager]. */
sealed interface CallUiState {
    data class IncomingRinging(val peerKeyHex: String, val callId: UUID) : CallUiState
    data class OutgoingRinging(val peerKeyHex: String, val callId: UUID) : CallUiState
    data class Connected(val peerKeyHex: String, val callId: UUID, val startedAtMillis: Long, val muted: Boolean) : CallUiState
    /** Transient — the peer wasn't reachable (offline, or didn't answer in time). Auto-clears itself. */
    data class Unavailable(val peerKeyHex: String) : CallUiState
}

data class UpdateInfo(val versionCode: Int, val versionName: String)

/** Holds all UI-visible state for the app: connection, contacts, per-peer message history. */
class AppState(
    private val contactStore: ContactStore,
    private val messageStore: MessageStore,
    private val profileStore: ProfileStore,
    private val settingsStore: SettingsStore,
    private val avatarStore: AvatarStore,
    private val appLockStore: AppLockStore,
) {
    var connection by mutableStateOf<ConnectionState>(ConnectionState.Idle)
    var displayName by mutableStateOf(profileStore.load() ?: "")
    var avatarIconId by mutableStateOf(avatarStore.load())
        private set
    var themeMode by mutableStateOf(settingsStore.loadThemeMode())
    var fontScale by mutableStateOf(settingsStore.loadFontScale())
    var onionRoutingEnabled by mutableStateOf(settingsStore.loadOnionRoutingEnabled())
        private set
    var onionWifiOnly by mutableStateOf(settingsStore.loadOnionWifiOnly())
        private set
    /** Whether the current connection is actually tunneled through the onion circuit right now (vs. direct, or not yet connected). Set directly by [ConnectionManager] — no persistence, it's live connection state. */
    var onionCircuitActive by mutableStateOf(false)
    var notificationsEnabled by mutableStateOf(settingsStore.loadNotificationsEnabled())
        private set
    var hideNotificationSender by mutableStateOf(settingsStore.loadHideNotificationSender())
        private set
    var hideNotificationContent by mutableStateOf(settingsStore.loadHideNotificationContent())
        private set
    var appLockEnabled by mutableStateOf(appLockStore.load())
        private set
    var pushEnabled by mutableStateOf(settingsStore.loadPushEnabled())
        private set
    /** The UnifiedPush endpoint currently registered with the relay, if any — set by [ConnectionManager.onPushEndpointChanged]. */
    var pushEndpoint by mutableStateOf(settingsStore.loadPushEndpoint())
        private set
    /** True whenever the app should be showing the unlock screen — starts locked if the feature is on, so a freshly-launched (or killed-and-restarted) process never briefly shows chats first. Cleared only by a successful biometric/device-credential prompt. */
    var isLocked by mutableStateOf(appLockEnabled)
    /** Set directly by [messenger.android.data.CallManager], never persisted — a call is always transient, in-memory state. */
    var activeCall by mutableStateOf<CallUiState?>(null)
    /** Set directly by [messenger.android.data.GroupManager] whenever a group's control log changes — the log itself is what's persisted (see [GroupStore]), this is just the recomputed snapshot for the UI. */
    var groups by mutableStateOf<List<messenger.common.group.GroupState>>(emptyList())
    /** Peer keys currently shown as "typing…" — added/auto-cleared by [ConnectionManager], never persisted. */
    val typingPeerKeys = mutableStateSetOf<String>()

    val contacts = mutableStateListOf<Contact>().apply { addAll(contactStore.load()) }

    init {
        if (contacts.none { it.deviceKeyHex == DRAFTS_DEVICE_KEY }) {
            contacts.add(0, Contact(nickname = "Drafts", deviceKeyHex = DRAFTS_DEVICE_KEY, nicknameConfirmed = true))
            contactStore.save(contacts)
        }
    }

    private val conversations = mutableStateMapOf<String, SnapshotStateList<ChatMessage>>().apply {
        messageStore.loadAll().forEach { (peerKeyHex, messages) -> put(peerKeyHex, mutableStateListOf(*messages.toTypedArray())) }
    }

    // PERFORMANCE: contactStore.save/messageStore.append/replaceConversation all go through
    // SecureStore, which does a synchronous encrypted (AES-GCM) rewrite of the *entire* combined
    // history file — every incoming message, ack, reaction, edit, or contact-profile update used to
    // trigger one of these on whatever thread called it (almost always Dispatchers.Main, per
    // ConnectionManager's withContext(Dispatchers.Main) wrapping), blocking the UI thread for
    // longer and longer as total chat history grew. The Compose state mutation itself (list[index]
    // = ...) is cheap and stays synchronous/on-caller-thread; only the disk write moves here.
    // limitedParallelism(1) keeps writes serialized in call order — since messageStore/contactStore
    // both back onto one shared file each, an unordered thread pool could let a later mutation's
    // write land before an earlier one's, silently reverting it.
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private fun persistContacts() {
        val snapshot = contacts.toList()
        persistenceScope.launch { contactStore.save(snapshot) }
    }

    private fun persistConversation(peerKeyHex: String, list: List<ChatMessage>) {
        val snapshot = list.toList()
        persistenceScope.launch { messageStore.replaceConversation(peerKeyHex, snapshot) }
    }

    private fun persistAppendedMessage(peerKeyHex: String, message: ChatMessage) {
        persistenceScope.launch { messageStore.append(peerKeyHex, message) }
    }

    private fun persistConversationDeleted(peerKeyHex: String) {
        persistenceScope.launch { messageStore.deleteConversation(peerKeyHex) }
    }

    private fun persistAllMessagesDeleted() {
        persistenceScope.launch { messageStore.deleteAll() }
    }

    var client: MessengerClient? = null

    /** The peer key of the currently-open chat screen, if any — used to suppress unread badges/notifications for it. */
    var openPeerKeyHex by mutableStateOf<String?>(null)

    var availableUpdate by mutableStateOf<UpdateInfo?>(null)
    var updateDownloading by mutableStateOf(false)
    var updateReadyApkPath by mutableStateOf<String?>(null)

    fun conversationFor(peerKeyHex: String): SnapshotStateList<ChatMessage> =
        conversations.getOrPut(peerKeyHex) { mutableStateListOf() }

    fun groupFor(groupIdHex: String): messenger.common.group.GroupState? = groups.firstOrNull { it.groupId.toHex() == groupIdHex }

    private fun contactFor(peerKeyHex: String): Contact? =
        contacts.firstOrNull { it.deviceKeyHex == peerKeyHex }

    /** Applies [transform] to the contact with [peerKeyHex] and persists — no-op if unknown or if nothing actually changed. */
    private fun updateContact(peerKeyHex: String, transform: (Contact) -> Contact) {
        val index = contacts.indexOfFirst { it.deviceKeyHex == peerKeyHex }
        if (index == -1) return
        val updated = transform(contacts[index])
        if (updated == contacts[index]) return
        contacts[index] = updated
        persistContacts()
    }

    /**
     * Applies [transform] to the message with [messageId] in [peerKeyHex]'s conversation and
     * persists — no-op if the message is unknown, or if [transform] returns null to decline.
     */
    private fun updateMessageById(peerKeyHex: String, messageId: String, transform: (ChatMessage) -> ChatMessage?) {
        val list = conversationFor(peerKeyHex)
        val index = list.indexOfFirst { it.messageId == messageId }
        if (index == -1) return
        val updated = transform(list[index]) ?: return
        list[index] = updated
        persistConversation(peerKeyHex, list)
    }

    fun lastMessageFor(peerKeyHex: String): ChatMessage? = conversations[peerKeyHex]?.lastOrNull()

    fun pinnedMessageFor(peerKeyHex: String): ChatMessage? = conversations[peerKeyHex]?.firstOrNull { it.pinned }

    /** Set right before navigating to a chat from a global search result; [ChatDetailScreen]'s jump-to-message effect consumes (and clears) it exactly once. */
    var pendingSearchJumpMessageId by mutableStateOf<String?>(null)

    /** One global search hit — [messageId] is always non-null (a message search can only ever jump to something with a stable id). */
    data class SearchHit(val peerKeyHex: String, val messageId: String, val snippet: String, val timestampMillis: Long, val fromMe: Boolean)

    /** Local, all-conversations text search over message bodies — no protocol/server involvement, matches [messenger.android.data.MessageStore]'s persisted history directly. */
    fun searchMessages(query: String): List<SearchHit> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return conversations.entries.flatMap { (peerKeyHex, list) ->
            if (peerKeyHex == DRAFTS_DEVICE_KEY) return@flatMap emptyList()
            list.mapNotNull { message ->
                val id = message.messageId ?: return@mapNotNull null
                if (!message.text.contains(q, ignoreCase = true)) return@mapNotNull null
                SearchHit(peerKeyHex, id, message.text, message.timestampMillis, message.fromMe)
            }
        }.sortedByDescending { it.timestampMillis }.take(200)
    }

    fun appendMessage(peerKeyHex: String, message: ChatMessage) {
        val list = conversationFor(peerKeyHex)
        // IDEMPOTENCY: the relay's mailbox errs toward "maybe redeliver" rather than "maybe lose" —
        // if it can't prove a frame actually reached the client before a disconnect, it re-queues
        // it, so the same messageId can legitimately arrive twice. Appending it twice used to just
        // render a harmless duplicate bubble; it crashes now that bubbles are keyed by messageId in
        // the chat's LazyColumn (Compose requires unique keys). Treat a repeat as a no-op — a
        // message with no id (a not-yet-acked FAILED send, or the local Drafts chat) is never
        // deduped against, since null can't collide with a real delivery.
        if (message.messageId != null && list.any { it.messageId == message.messageId }) return
        // Stamped from the chat's *current* disappearing-messages duration — changing that
        // duration later doesn't reach back and alter messages already sent/received.
        val duration = contactFor(peerKeyHex)?.disappearAfterMillis
        val stamped = if (duration != null && message.expiresAtMillis == null) {
            message.copy(expiresAtMillis = System.currentTimeMillis() + duration)
        } else {
            message
        }
        list.add(stamped)
        persistAppendedMessage(peerKeyHex, stamped)
    }

    /** Drops every message across every conversation whose [ChatMessage.expiresAtMillis] has passed — see [ConnectionManager]'s periodic sweep. */
    fun purgeExpiredMessages() {
        val now = System.currentTimeMillis()
        for ((peerKeyHex, list) in conversations) {
            if (list.none { it.expiresAtMillis != null && it.expiresAtMillis <= now }) continue
            list.removeAll { it.expiresAtMillis != null && it.expiresAtMillis <= now }
            persistConversation(peerKeyHex, list)
        }
    }

    /** Pins the message at [index], unpinning any other in the same conversation (only one pin at a time). */
    fun togglePin(peerKeyHex: String, index: Int) {
        val list = conversationFor(peerKeyHex)
        if (index !in list.indices) return
        val newPinned = !list[index].pinned
        for (i in list.indices) {
            if (i != index && list[i].pinned) list[i] = list[i].copy(pinned = false)
        }
        list[index] = list[index].copy(pinned = newPinned)
        persistConversation(peerKeyHex, list)
    }

    /** Replaces the message at [index] in place — used by [ConnectionManager.retryMessage] so a retried send updates its existing bubble instead of appending a new one. */
    fun updateMessageAt(peerKeyHex: String, index: Int, message: ChatMessage) {
        val list = conversationFor(peerKeyHex)
        if (index !in list.indices) return
        list[index] = message
        persistConversation(peerKeyHex, list)
    }

    /** Removes the message at [index] from this device only — the peer keeps their own copy. */
    fun deleteMessage(peerKeyHex: String, index: Int) {
        val list = conversationFor(peerKeyHex)
        if (index !in list.indices) return
        list.removeAt(index)
        persistConversation(peerKeyHex, list)
    }

    /** Marks the message with [messageId] as delivered, once the peer's device acks decrypting it. */
    fun markDelivered(peerKeyHex: String, messageId: String) = updateMessageById(peerKeyHex, messageId) { message ->
        // A read ack can race a slightly-delayed delivery ack (e.g. both queued while briefly
        // offline) — don't let DELIVERED clobber a SEEN that already arrived.
        if (message.deliveryStatus == DeliveryStatus.SEEN) null
        else message.copy(deliveryStatus = DeliveryStatus.DELIVERED)
    }

    /** Marks the message with [messageId] as seen, once the peer's device has actually shown it on screen. */
    fun markSeen(peerKeyHex: String, messageId: String) = updateMessageById(peerKeyHex, messageId) {
        it.copy(deliveryStatus = DeliveryStatus.SEEN)
    }

    /** Incoming (not-from-me) messages in [peerKeyHex]'s conversation we haven't yet told the sender we've seen. */
    fun unseenIncomingMessages(peerKeyHex: String): List<ChatMessage> =
        conversationFor(peerKeyHex).filter { !it.fromMe && it.messageId != null && !it.readAckSent }

    /** Marks [messageId] as having had its read ack sent, so it isn't re-sent on the next chat open. */
    fun markReadAckSent(peerKeyHex: String, messageId: String) = updateMessageById(peerKeyHex, messageId) {
        it.copy(readAckSent = true)
    }

    /**
     * Replaces [messageId]'s text and marks it [ChatMessage.edited] — no history of the previous
     * text is kept. [expectedFromMe] guards against the two call sites getting crossed: my own edit
     * (I changed something I sent) only ever applies to a message with `fromMe == true`; an edit
     * signal that just arrived from the peer only ever applies to one with `fromMe == false` (they
     * can only ever be editing something *they* sent us) — a mismatch is a no-op, not an error.
     */
    fun applyEdit(peerKeyHex: String, messageId: String, newText: String, expectedFromMe: Boolean) =
        updateMessageById(peerKeyHex, messageId) { message ->
            if (message.fromMe != expectedFromMe) null else message.copy(text = newText, edited = true)
        }

    /** Sets (or, [emoji] null, clears) the reaction on [messageId] — [fromMe] selects *whose* reaction slot, not who sent the original message: either party can react to either's messages. */
    fun setReaction(peerKeyHex: String, messageId: String, emoji: String?, fromMe: Boolean) =
        updateMessageById(peerKeyHex, messageId) { message ->
            if (fromMe) message.copy(reactionMine = emoji) else message.copy(reactionTheirs = emoji)
        }

    /** The attachment message whose transfer file id (kept as its [ChatMessage.messageId]) matches [fileId], if any. */
    fun transferMessage(peerKeyHex: String, fileId: String): ChatMessage? =
        conversations[peerKeyHex]?.firstOrNull { it.messageId == fileId }

    /**
     * In-memory-only progress update (0..1) for an in-flight transfer. Deliberately NOT persisted —
     * progress churns once per chunk and rewriting the whole store each time would be wasteful; only
     * terminal state ([updateTransfer]) is saved.
     */
    fun updateTransferProgress(peerKeyHex: String, fileId: String, progress: Float) {
        val list = conversationFor(peerKeyHex)
        val i = list.indexOfFirst { it.messageId == fileId }
        if (i == -1) return
        list[i] = list[i].copy(transferProgress = progress.coerceIn(0f, 1f), transferStatus = FileTransferStatus.TRANSFERRING)
    }

    /** Applies a terminal/metadata change to a transfer message (e.g. COMPLETE + local path, or FAILED) and persists it. */
    fun updateTransfer(peerKeyHex: String, fileId: String, transform: (ChatMessage) -> ChatMessage) =
        updateMessageById(peerKeyHex, fileId, transform)

    /** Adds a contact if its key isn't already known, persisting the updated list. Returns true if it was new. */
    fun addContactIfMissing(contact: Contact): Boolean {
        if (contacts.any { it.deviceKeyHex == contact.deviceKeyHex }) return false
        contacts.add(contact)
        persistContacts()
        return true
    }

    fun toggleChatPinned(contact: Contact) = updateContact(contact.deviceKeyHex) { it.copy(pinned = !it.pinned) }

    fun removeContact(contact: Contact) {
        contacts.removeAll { it.deviceKeyHex == contact.deviceKeyHex }
        conversations.remove(contact.deviceKeyHex)
        persistContacts()
        persistConversationDeleted(contact.deviceKeyHex)
    }

    /**
     * Applies a peer's real display name and chosen avatar glyph, decrypted out of a message we
     * just received from them — the only source of truth for either. Adds the contact if unknown.
     */
    fun applyIncomingProfile(peerKeyHex: String, displayName: String, avatarIconId: AvatarIconId?) {
        val trimmed = displayName.trim()
        val index = contacts.indexOfFirst { it.deviceKeyHex == peerKeyHex }
        if (index == -1) {
            contacts.add(
                Contact(
                    nickname = trimmed.ifBlank { peerKeyHex.take(8) },
                    deviceKeyHex = peerKeyHex,
                    nicknameConfirmed = trimmed.isNotBlank(),
                    avatarIconId = avatarIconId,
                ),
            )
        } else {
            val current = contacts[index]
            val nicknameChanged = trimmed.isNotBlank() && (current.nickname != trimmed || !current.nicknameConfirmed)
            val avatarChanged = current.avatarIconId != avatarIconId
            if (!nicknameChanged && !avatarChanged) return
            contacts[index] = current.copy(
                nickname = if (nicknameChanged) trimmed else current.nickname,
                nicknameConfirmed = current.nicknameConfirmed || nicknameChanged,
                avatarIconId = avatarIconId,
            )
        }
        persistContacts()
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        settingsStore.saveThemeMode(mode)
    }

    fun updateFontScale(scale: Float) {
        fontScale = scale
        settingsStore.saveFontScale(scale)
    }

    /** Persists the preference only — [ConnectionManager.setOnionRoutingEnabled] is what actually applies it, since the transport path is only decided once per connection attempt. */
    fun setOnionRoutingEnabledPersisted(enabled: Boolean) {
        onionRoutingEnabled = enabled
        settingsStore.saveOnionRoutingEnabled(enabled)
    }

    /** Persists the preference only — see [ConnectionManager.setOnionWifiOnly]. */
    fun setOnionWifiOnlyPersisted(enabled: Boolean) {
        onionWifiOnly = enabled
        settingsStore.saveOnionWifiOnly(enabled)
    }

    fun setNotificationsEnabledPersisted(enabled: Boolean) {
        notificationsEnabled = enabled
        settingsStore.saveNotificationsEnabled(enabled)
    }

    fun setHideNotificationSenderPersisted(hide: Boolean) {
        hideNotificationSender = hide
        settingsStore.saveHideNotificationSender(hide)
    }

    fun setHideNotificationContentPersisted(hide: Boolean) {
        hideNotificationContent = hide
        settingsStore.saveHideNotificationContent(hide)
    }

    fun setAppLockEnabledPersisted(enabled: Boolean) {
        appLockEnabled = enabled
        appLockStore.save(enabled)
        if (!enabled) isLocked = false
    }

    fun setPushEnabledPersisted(enabled: Boolean) {
        pushEnabled = enabled
        settingsStore.savePushEnabled(enabled)
    }

    fun setPushEndpointPersisted(endpointUrl: String?) {
        pushEndpoint = endpointUrl
        settingsStore.savePushEndpoint(endpointUrl)
    }

    fun updateAvatarIcon(icon: AvatarIconId?) {
        avatarIconId = icon
        avatarStore.save(icon)
        client?.let { it.avatarIcon = icon.wireValue }
    }

    /** Wipes every conversation's history on this device — contacts and nicknames are untouched. */
    fun clearAllMessages() {
        conversations.values.forEach { it.clear() }
        persistAllMessagesDeleted()
    }

    fun updateDisplayName(name: String) {
        val trimmed = name.trim()
        displayName = trimmed
        profileStore.save(trimmed)
        client?.let { it.displayName = trimmed.ifBlank { it.identity.dhIdentityPublicKey.toHex().take(8) } }
    }

    fun nicknameFor(peerKeyHex: String): String =
        contactFor(peerKeyHex)?.nickname ?: peerKeyHex.take(8)

    fun avatarIconFor(peerKeyHex: String): AvatarIconId? = contactFor(peerKeyHex)?.avatarIconId

    fun isNicknameConfirmed(peerKeyHex: String): Boolean = contactFor(peerKeyHex)?.nicknameConfirmed ?: false

    fun markVerified(peerKeyHex: String) = updateContact(peerKeyHex) { it.copy(verified = true) }

    fun isVerified(peerKeyHex: String): Boolean = contactFor(peerKeyHex)?.verified ?: false

    fun isBlocked(peerKeyHex: String): Boolean = contactFor(peerKeyHex)?.blocked ?: false

    fun setBlocked(peerKeyHex: String, blocked: Boolean) = updateContact(peerKeyHex) { it.copy(blocked = blocked) }

    fun disappearAfterFor(peerKeyHex: String): Long? = contactFor(peerKeyHex)?.disappearAfterMillis

    fun setDisappearAfter(peerKeyHex: String, durationMillis: Long?) =
        updateContact(peerKeyHex) { it.copy(disappearAfterMillis = durationMillis) }

    /** Marks [peerKeyHex] unread, unless its chat screen is the one currently open. */
    fun markUnreadIfClosed(peerKeyHex: String) {
        if (openPeerKeyHex == peerKeyHex) return
        updateContact(peerKeyHex) { it.copy(hasUnread = true) }
    }

    fun markRead(peerKeyHex: String) = updateContact(peerKeyHex) { it.copy(hasUnread = false) }
}
