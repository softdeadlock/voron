package messenger.android.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import messenger.common.client.IncomingGroupControlEvent
import messenger.common.client.IncomingGroupJoinRequest
import messenger.common.client.IncomingGroupMessage
import messenger.common.client.IncomingGroupSyncRequest
import messenger.common.client.MessengerClient
import messenger.common.group.AdminPermission
import messenger.common.group.GENESIS_HASH
import messenger.common.group.GROUP_ID_LENGTH
import messenger.common.group.GroupControlEvent
import messenger.common.group.GroupControlLog
import messenger.common.group.GroupEventPayloads
import messenger.common.group.GroupEventType
import messenger.common.group.GroupInvite
import messenger.common.group.GroupRole
import messenger.common.group.GroupState
import messenger.common.util.toHex
import java.security.SecureRandom

/**
 * Orchestrates the group layer on Android: keeps a [GroupControlLog] per group (persisted as raw
 * events via [GroupStore]), signs and fans out control events / messages over the live
 * [MessengerClient], and keeps everyone's [messenger.common.group.GroupCryptoSession] sender key
 * in step by re-keying and redistributing on every membership change (the two guarantees the
 * sender-key scheme gives — removed members lose future access, added members get no past access).
 *
 * All the security-critical logic (authorization, fork resolution, signature verification, the
 * crypto itself) lives in `common` and is unit-tested there; this class is the app-side glue that
 * drives it with the real client and real persistence.
 */
class GroupManager(private val appContext: Context, private val appState: AppState) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val store = GroupStore(SecureStore(appContext, "groups.tsv"))
    private val random = SecureRandom()

    // Guards the logs map + persistence against concurrent incoming events and local actions
    // racing on the same group's log.
    private val mutex = Mutex()
    private val logs = HashMap<String, GroupControlLog>()

    // Groups this device has already established (and distributed) its own sender key for since the
    // process started. Crypto/session state is intentionally not persisted (same as 1:1 ratchet
    // state), so after a restart this is empty and the first send to any group lazily re-keys and
    // redistributes — otherwise sends would silently fail against an empty in-memory group session.
    private val rekeyedThisSession = HashSet<String>()

    init {
        val persisted = store.load()
        for ((groupIdHex, events) in persisted) {
            val groupId = hexToBytes(groupIdHex)
            val log = GroupControlLog(groupId)
            for (event in events) log.ingest(event)
            logs[groupIdHex] = log
        }
        publishState()
    }

    private fun client(): MessengerClient? = appState.client
    private fun selfDhKey(): ByteArray? = client()?.identity?.dhIdentityPublicKey

    /** Subscribes to the client's group flows — call once per client lifetime, alongside ConnectionManager's other collectors. */
    fun attach(client: MessengerClient) {
        // MessengerClient itself only knows pairwise crypto sessions, not group membership -- this
        // is what lets it refuse a GROUP_KEY_REQUEST from someone who's since been removed (see
        // MessengerClient.groupMembershipChecker's doc). Re-set on every reconnect since it's a new
        // MessengerClient instance each time, same as the flow subscriptions below.
        client.groupMembershipChecker = { groupId, peerDhKey -> logs[groupId.toHex()]?.state?.members?.containsKey(peerDhKey.toHex()) == true }
        scope.launch { client.groupControlEvents.collect { onControlEvent(it) } }
        scope.launch { client.groupSyncRequests.collect { onSyncRequest(it) } }
        scope.launch { client.groupJoinRequests.collect { onJoinRequest(it) } }
        scope.launch { client.groupMessages.collect { onGroupMessage(it) } }
    }

    // --- Local actions (plain fire-and-forget wrappers for the UI) -----------

    // SECURITY/CRASH: onCreated (and onResult below) are UI callbacks — a caller navigating off one
    // (e.g. VoronApp's createGroup -> navController.navigate(...)) crashes outright if invoked from
    // this manager's own Dispatchers.Default scope, since Compose Navigation's lifecycle updates
    // require the main thread. Both wrappers hop back to Main before calling the callback.
    fun createGroup(name: String, initialMemberKeys: List<String>, onCreated: (ByteArray) -> Unit = {}) {
        scope.launch {
            val groupId = createGroupSuspend(name, initialMemberKeys)
            withContext(Dispatchers.Main) { onCreated(groupId) }
        }
    }

    fun addMember(groupId: ByteArray, memberKeyHex: String) = scope.launch { addMemberSuspend(groupId, memberKeyHex) }
    fun removeMember(groupId: ByteArray, memberKeyHex: String) = scope.launch { removeMemberSuspend(groupId, memberKeyHex) }
    fun promoteAdmin(groupId: ByteArray, memberKeyHex: String, permissions: Int) = scope.launch { promoteAdminSuspend(groupId, memberKeyHex, permissions) }
    fun demoteAdmin(groupId: ByteArray, memberKeyHex: String) = scope.launch { demoteAdminSuspend(groupId, memberKeyHex) }
    fun transferOwnership(groupId: ByteArray, newOwnerKeyHex: String) = scope.launch { transferOwnershipSuspend(groupId, newOwnerKeyHex) }
    fun setGroupName(groupId: ByteArray, name: String) = scope.launch { setGroupNameSuspend(groupId, name) }
    fun setGroupAvatar(groupId: ByteArray, avatarIconId: Int) = scope.launch { setGroupAvatarSuspend(groupId, avatarIconId) }
    fun setAnnouncementMode(groupId: ByteArray, enabled: Boolean) = scope.launch { setAnnouncementModeSuspend(groupId, enabled) }
    fun setInviteLinksEnabled(groupId: ByteArray, enabled: Boolean) = scope.launch { setInviteLinksEnabledSuspend(groupId, enabled) }
    fun leaveGroup(groupId: ByteArray) = scope.launch { leaveGroupSuspend(groupId) }
    fun sendGroupMessage(groupId: ByteArray, text: String) = scope.launch { sendGroupMessageSuspend(groupId, text) }

    /** [onResult] receives null on success (the actual join arrives later as a control event), or a human-readable error. */
    fun joinViaInvite(link: String, onResult: (String?) -> Unit = {}) {
        scope.launch {
            val result = joinViaInviteSuspend(link)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    // --- Local actions (suspend implementations) ------------------------------

    /** Creates a new group with [name] and [initialMemberKeys] (hex), returning its id. Distributes the full control log + this device's sender key to every initial member. */
    private suspend fun createGroupSuspend(name: String, initialMemberKeys: List<String>): ByteArray = mutex.withLock {
        val identity = client()?.identity ?: error("not connected")
        val groupId = ByteArray(GROUP_ID_LENGTH).also { random.nextBytes(it) }
        val log = GroupControlLog(groupId)
        logs[groupId.toHex()] = log

        val create = GroupControlEvent.create(
            groupId, GENESIS_HASH, GroupEventType.CREATE_GROUP, identity.dhIdentityPublicKey, identity.signingIdentity,
            GroupEventPayloads.encodeCreateGroup(name),
        )
        log.ingest(create)
        for (memberKeyHex in initialMemberKeys.distinct()) {
            // Skipped (not added) if we can't resolve their signing key -- see resolveMemberSigningKey.
            val memberSigningKey = resolveMemberSigningKey(memberKeyHex) ?: continue
            val event = GroupControlEvent.create(
                groupId, log.headHash(), GroupEventType.ADD_MEMBER, identity.dhIdentityPublicKey, identity.signingIdentity,
                GroupEventPayloads.encodeAddMember(hexToBytes(memberKeyHex), memberSigningKey),
            )
            log.ingest(event)
        }
        persist()

        rekeyAndDistribute(groupId, log)
        // Bootstrap each member with the whole log so they can replay to the same state.
        val allEvents = log.eventsInCanonicalOrder()
        for (memberKeyHex in currentOtherMemberKeys(log)) {
            val memberKey = hexToBytes(memberKeyHex)
            for (event in allEvents) runCatching { client()?.sendGroupControlEvent(memberKey, event) }
        }
        publishState()
        groupId
    }

    private suspend fun addMemberSuspend(groupId: ByteArray, memberKeyHex: String) {
        val memberSigningKey = resolveMemberSigningKey(memberKeyHex) ?: return
        membershipChange(groupId, GroupEventType.ADD_MEMBER, GroupEventPayloads.encodeAddMember(hexToBytes(memberKeyHex), memberSigningKey))
    }

    private suspend fun removeMemberSuspend(groupId: ByteArray, memberKeyHex: String) =
        membershipChange(groupId, GroupEventType.REMOVE_MEMBER, GroupEventPayloads.encodeMemberKey(hexToBytes(memberKeyHex)))

    private suspend fun promoteAdminSuspend(groupId: ByteArray, memberKeyHex: String, permissions: Int) =
        settingsChange(groupId, GroupEventType.PROMOTE_ADMIN, GroupEventPayloads.encodePromoteAdmin(hexToBytes(memberKeyHex), permissions))

    private suspend fun demoteAdminSuspend(groupId: ByteArray, memberKeyHex: String) =
        settingsChange(groupId, GroupEventType.DEMOTE_ADMIN, GroupEventPayloads.encodeMemberKey(hexToBytes(memberKeyHex)))

    private suspend fun transferOwnershipSuspend(groupId: ByteArray, newOwnerKeyHex: String) =
        settingsChange(groupId, GroupEventType.TRANSFER_OWNERSHIP, GroupEventPayloads.encodeMemberKey(hexToBytes(newOwnerKeyHex)))

    // SET_GROUP_INFO's payload carries name + avatarIconId together (see GroupEventPayloads), so
    // changing just one has to read the other's *current* value first or it'd silently reset it.
    private suspend fun setGroupNameSuspend(groupId: ByteArray, name: String) {
        val currentAvatar = logs[groupId.toHex()]?.state?.avatarIconId ?: 0
        settingsChange(groupId, GroupEventType.SET_GROUP_INFO, GroupEventPayloads.encodeGroupInfo(name, currentAvatar))
    }

    private suspend fun setGroupAvatarSuspend(groupId: ByteArray, avatarIconId: Int) {
        val currentName = logs[groupId.toHex()]?.state?.name ?: return
        settingsChange(groupId, GroupEventType.SET_GROUP_INFO, GroupEventPayloads.encodeGroupInfo(currentName, avatarIconId))
    }

    private suspend fun setAnnouncementModeSuspend(groupId: ByteArray, enabled: Boolean) =
        settingsChange(groupId, GroupEventType.SET_ANNOUNCEMENT_MODE, GroupEventPayloads.encodeBoolean(enabled))

    private suspend fun setInviteLinksEnabledSuspend(groupId: ByteArray, enabled: Boolean) =
        settingsChange(groupId, GroupEventType.SET_INVITE_LINKS_ENABLED, GroupEventPayloads.encodeBoolean(enabled))

    /** Leaves [groupId] — fans a LEAVE_GROUP event to everyone, then forgets the group locally. Callers must block this in the UI for an owner/last admin without a hand-off first (see the plan's orphaned-group rule). */
    private suspend fun leaveGroupSuspend(groupId: ByteArray) = mutex.withLock {
        val log = logs[groupId.toHex()] ?: return@withLock
        val identity = client()?.identity ?: return@withLock
        val recipients = currentOtherMemberKeys(log)
        val leave = GroupControlEvent.create(
            groupId, log.headHash(), GroupEventType.LEAVE_GROUP, identity.dhIdentityPublicKey, identity.signingIdentity, ByteArray(0),
        )
        for (memberKeyHex in recipients) runCatching { client()?.sendGroupControlEvent(hexToBytes(memberKeyHex), leave) }
        logs.remove(groupId.toHex())
        persist()
        publishState()
    }

    /** Sends [text] to [groupId] — no-op (returns false) if announcement mode is on and this device isn't an admin. Appends the optimistic local echo itself, same as [ConnectionManager.sendMessage] does for 1:1. */
    private suspend fun sendGroupMessageSuspend(groupId: ByteArray, text: String): Boolean = mutex.withLock {
        val log = logs[groupId.toHex()] ?: return@withLock false
        val selfHex = selfDhKey()?.toHex() ?: return@withLock false
        val self = log.state.members[selfHex] ?: return@withLock false
        if (log.state.announcementMode && self.role == GroupRole.MEMBER) return@withLock false
        ensureOwnSenderKey(groupId, log)
        val memberKeys = currentOtherMemberKeys(log).map { hexToBytes(it) }
        val outcome = runCatching { client()?.sendGroupMessage(groupId, memberKeys, text.toByteArray()) }
        // No per-recipient delivery-ack tracking for groups yet (v1 simplification, unlike 1:1's
        // DELIVERY_ACK/READ_ACK) — a successfully queued send is shown as SENT and stays that way.
        appState.appendMessage(
            groupId.toHex(),
            ChatMessage(
                fromMe = true,
                text = text,
                timestampMillis = System.currentTimeMillis(),
                messageId = java.util.UUID.randomUUID().toString(),
                deliveryStatus = if (outcome.isSuccess) DeliveryStatus.SENT else DeliveryStatus.FAILED,
            ),
        )
        outcome.isSuccess
    }

    /** Establishes and distributes this device's own group sender key if it hasn't been this process lifetime (e.g. right after an app restart, when in-memory crypto state is gone but the persisted control log isn't). */
    private suspend fun ensureOwnSenderKey(groupId: ByteArray, log: GroupControlLog) {
        if (rekeyedThisSession.contains(groupId.toHex())) return
        rekeyAndDistribute(groupId, log)
    }

    /** Builds a signed, expiring invite link for [groupId] — only meaningful if invite links are enabled and the caller is allowed to add (the receiving inviter re-checks both). */
    fun createInviteLink(groupId: ByteArray, validForMillis: Long = 7L * 24 * 60 * 60 * 1000): String? {
        val identity = client()?.identity ?: return null
        return GroupInvite.create(groupId, identity.dhIdentityPublicKey, identity.signingIdentity, System.currentTimeMillis() + validForMillis)
    }

    /** Opens an invite link/QR: validates it and asks the inviter to add us. Returns a human-readable error, or null on success (the actual add arrives later as a control event). */
    private suspend fun joinViaInviteSuspend(link: String): String? {
        val invite = GroupInvite.parse(link) ?: return "Not a Voron group invite"
        if (!invite.verifySignature()) return "This invite's signature is invalid"
        if (invite.isExpired(System.currentTimeMillis())) return "This invite link has expired"
        val client = client() ?: return "Not connected"
        return try {
            // The whole signed invite travels with the request (not just the groupId) so the
            // inviter can re-verify it themselves instead of trusting that we hold a valid one.
            client.sendGroupJoinRequest(invite.inviterDhKey, invite)
            null
        } catch (e: Exception) {
            "Couldn't reach the inviter: ${e.message}"
        }
    }

    /**
     * Resolves [memberKeyHex]'s signing identity key via the client's TOFU pin/bundle-fetch (see
     * [MessengerClient.resolveSigningIdentityKey]) — needed so [GroupControlLog] can pin it as that
     * member's [messenger.common.group.GroupMember.signingKey] the moment they're added, rather than
     * trusting whatever signing key a later event self-declares for their dhKey. Null (caller skips
     * the add) if the peer has no published prekey bundle to resolve one from at all.
     */
    private suspend fun resolveMemberSigningKey(memberKeyHex: String): ByteArray? =
        client()?.resolveSigningIdentityKey(hexToBytes(memberKeyHex))

    // --- Incoming ------------------------------------------------------------

    private suspend fun onControlEvent(incoming: IncomingGroupControlEvent) = mutex.withLock {
        val groupId = incoming.event.groupId
        val log = logs.getOrPut(groupId.toHex()) { GroupControlLog(groupId) }
        val prevHex = incoming.event.prevEventHash.toHex()
        val prevKnown = incoming.event.prevEventHash.contentEquals(GENESIS_HASH) || log.eventByHash(prevHex) != null
        log.ingest(incoming.event)
        persist()
        publishState()
        // We're missing the history this event builds on — ask the sender to catch us up.
        if (!prevKnown) {
            runCatching { client()?.sendGroupControlSyncRequest(incoming.senderDhIdentityKey, groupId, log.headHash()) }
        }
    }

    private suspend fun onSyncRequest(incoming: IncomingGroupSyncRequest) = mutex.withLock {
        val log = logs[incoming.groupId.toHex()] ?: return@withLock
        val client = client() ?: return@withLock
        val canonical = log.eventsInCanonicalOrder()
        // Everything past their head, or the whole log if we don't recognise their head (new member).
        val theirHeadHex = incoming.headHash.toHex()
        val startIndex = canonical.indexOfFirst { it.hash().toHex() == theirHeadHex }
        val toSend = if (startIndex >= 0) canonical.drop(startIndex + 1) else canonical
        for (event in toSend) runCatching { client.sendGroupControlEvent(incoming.senderDhIdentityKey, event) }
    }

    private suspend fun onJoinRequest(incoming: IncomingGroupJoinRequest) = mutex.withLock {
        val identity = client()?.identity ?: return@withLock
        val invite = incoming.invite
        // SECURITY: re-verify the invite ourselves rather than trusting that whoever messaged us
        // actually held a valid one -- the join request's only other content is the sender's
        // routing-authenticated identity, which proves nothing about invite validity on its own.
        // - inviterDhKey/inviterSigningPublicKey must be *us* specifically: an invite is only ever
        //   proof that WE (its named inviter) agreed to vouch for whoever presents it, not that some
        //   other member did.
        // - verifySignature()/isExpired() are the same checks the honest joiner already did locally
        //   before sending -- redone here because a malicious sender could skip them and replay a
        //   forged or stale/expired invite payload directly.
        if (!invite.inviterDhKey.contentEquals(identity.dhIdentityPublicKey)) return@withLock
        if (!invite.inviterSigningPublicKey.contentEquals(identity.signingIdentityPublicKey)) return@withLock
        if (!invite.verifySignature()) return@withLock
        if (invite.isExpired(System.currentTimeMillis())) return@withLock

        val log = logs[invite.groupId.toHex()] ?: return@withLock
        val selfHex = selfDhKey()?.toHex() ?: return@withLock
        val self = log.state.members[selfHex] ?: return@withLock
        // Honour the join only if invite links are on AND we're actually allowed to add — the same
        // checks GroupControlLog would apply to our resulting ADD_MEMBER anyway, done up front so we
        // don't emit a doomed event.
        val allowed = log.state.inviteLinksEnabled && (self.role == GroupRole.OWNER || self.hasPermission(AdminPermission.ADD_MEMBERS) || self.role == GroupRole.MEMBER)
        if (!allowed) return@withLock
        if (log.state.members.containsKey(incoming.senderDhIdentityKey.toHex())) return@withLock
        val memberSigningKey = resolveMemberSigningKey(incoming.senderDhIdentityKey.toHex()) ?: return@withLock
        applyMembershipChangeLocked(invite.groupId, log, GroupEventType.ADD_MEMBER, GroupEventPayloads.encodeAddMember(incoming.senderDhIdentityKey, memberSigningKey))
        // Bootstrap the newcomer with the full log so they can replay to the current state.
        for (event in log.eventsInCanonicalOrder()) runCatching { client()?.sendGroupControlEvent(incoming.senderDhIdentityKey, event) }
    }

    private fun onGroupMessage(incoming: IncomingGroupMessage) {
        // SECURITY: MessengerClient's group-message/sender-key handling is purely mechanical pairwise
        // crypto — it has no concept of group membership at all (that's this class's job), so a
        // removed member (or anyone who ever established a pairwise session and learned groupId)
        // could otherwise keep injecting messages that render exactly like a real member's. Checking
        // against the *canonical* GroupControlLog state here, not just "did this decrypt", is what
        // actually enforces "removed means removed" for incoming group chat content.
        val log = logs[incoming.groupId.toHex()] ?: return
        if (!log.state.members.containsKey(incoming.senderDhIdentityKey.toHex())) return
        // Deterministic, not random: (sender, epoch, counter) uniquely identifies this exact
        // message (see IncomingGroupMessage's doc) — a mailbox redelivery of the same frame
        // produces the identical id and is deduped by AppState.appendMessage's existing
        // messageId-idempotency check, instead of showing up as a second bubble.
        val messageId = "${incoming.senderDhIdentityKey.toHex()}:${incoming.epoch}:${incoming.counter}"
        appState.appendMessage(
            incoming.groupId.toHex(),
            ChatMessage(
                fromMe = false,
                text = String(incoming.plaintext),
                timestampMillis = System.currentTimeMillis(),
                messageId = messageId,
                senderKeyHex = incoming.senderDhIdentityKey.toHex(),
            ),
        )
    }

    // --- Shared helpers ------------------------------------------------------

    private suspend fun membershipChange(groupId: ByteArray, eventType: Byte, payload: ByteArray) = mutex.withLock {
        val log = logs[groupId.toHex()] ?: return@withLock
        applyMembershipChangeLocked(groupId, log, eventType, payload)
    }

    private suspend fun applyMembershipChangeLocked(groupId: ByteArray, log: GroupControlLog, eventType: Byte, payload: ByteArray) {
        val identity = client()?.identity ?: return
        val event = GroupControlEvent.create(groupId, log.headHash(), eventType, identity.dhIdentityPublicKey, identity.signingIdentity, payload)
        if (!log.ingest(event)) return
        persist()
        // Fan the event to everyone currently in the group, then re-key: membership changed, so
        // removed members must lose the next epoch and added ones must not see the previous one.
        for (memberKeyHex in currentOtherMemberKeys(log)) runCatching { client()?.sendGroupControlEvent(hexToBytes(memberKeyHex), event) }
        rekeyAndDistribute(groupId, log)
        publishState()
    }

    private suspend fun settingsChange(groupId: ByteArray, eventType: Byte, payload: ByteArray) = mutex.withLock {
        val log = logs[groupId.toHex()] ?: return@withLock
        val identity = client()?.identity ?: return@withLock
        val event = GroupControlEvent.create(groupId, log.headHash(), eventType, identity.dhIdentityPublicKey, identity.signingIdentity, payload)
        if (!log.ingest(event)) return@withLock
        persist()
        for (memberKeyHex in currentOtherMemberKeys(log)) runCatching { client()?.sendGroupControlEvent(hexToBytes(memberKeyHex), event) }
        publishState()
    }

    private suspend fun rekeyAndDistribute(groupId: ByteArray, log: GroupControlLog) {
        val client = client() ?: return
        val senderKey = client.createOrRekeyGroup(groupId)
        rekeyedThisSession.add(groupId.toHex())
        for (memberKeyHex in currentOtherMemberKeys(log)) {
            runCatching { client.sendGroupSenderKey(hexToBytes(memberKeyHex), senderKey) }
        }
    }

    private fun currentOtherMemberKeys(log: GroupControlLog): List<String> {
        val selfHex = selfDhKey()?.toHex()
        return log.state.members.keys.filter { it != selfHex }
    }

    private fun persist() {
        // ALL events, not just canonical ones — see GroupControlLog.allEvents: persisting only the
        // canonical path would lose a resolved fork's losing branch, so a later event building on it
        // would look like unknown history forever.
        store.save(logs.mapValues { (_, log) -> log.allEvents() })
    }

    private fun publishState() {
        appState.groups = logs.values.map { it.state }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }
}
