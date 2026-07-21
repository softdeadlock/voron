package messenger.common.group

import messenger.common.util.hexToByteArray
import messenger.common.util.toHex

/**
 * Replays and authorizes a group's [GroupControlEvent] log entirely client-side — the relay has no
 * concept of groups at all (by design), so there is no server to centrally decide who's an admin or
 * who's still a member. Every member runs this exact same deterministic logic over whatever events
 * they've received, from whoever they received them from, in whatever order — and must converge to
 * the same [state] regardless.
 *
 * Events form a hash chain, not a strictly ordered list: each carries the hash of the specific
 * predecessor its signer had seen ([GroupControlEvent.prevEventHash]). Normally that's just the
 * previous tip. If two members concurrently (while mutually offline) create different events on
 * top of the *same* predecessor, that predecessor now has two children — a fork. [recompute] walks
 * from genesis and, at every fork, always continues into the lexicographically smallest child hash
 * — an arbitrary but fully deterministic rule, so every member who has seen both forked events picks
 * the same winner without needing to communicate about it. The losing branch's events are simply
 * never applied (not deleted — a later-arriving event built on top of the loser would still be
 * known, just inert unless the loser someday becomes canonical, which it never will once it's lost
 * once, since the tie-break is deterministic and stable). This is deliberately not a full CRDT:
 * fork races are rare (need two admins acting in the same short window while offline from each
 * other) and documented as an accepted eventual-consistency edge case, not solved more rigorously.
 *
 * An event that fails its authorization check for the state it lands on (e.g. a plain member
 * attempting `REMOVE_MEMBER`) is still accepted into the known event set — so it doesn't corrupt
 * the hash-chain for whatever legitimately builds on it later — but its own effect is simply
 * skipped when computing [state].
 */
class GroupControlLog(val groupId: ByteArray) {
    private val eventsByHash = HashMap<String, GroupControlEvent>()
    private val childrenOf = HashMap<String, MutableList<String>>()

    // SECURITY: without this, canonicalPath()'s "always take the lexicographically smallest child"
    // fork rule applies even at the very root -- ANY CREATE_GROUP event claiming prevEventHash =
    // GENESIS_HASH competes to be genesis, forever, no matter how long ago the real genesis was
    // already accepted and built upon. An attacker who merely knows groupId can forge their own
    // CREATE_GROUP (self-signed, so verifySignature() trivially passes), vary its payload until its
    // hash beats the real genesis's (SHA-256 is fast; beating one fixed target is ~50/50 per try),
    // and send it to any member -- recompute() then walks the attacker's fake genesis instead,
    // handing them OWNER over the group's entire membership from that point on. Pinning whichever
    // real CREATE_GROUP this log accepts as root *first* makes that permanent: a later-arriving
    // competing genesis, however small its hash, can never dislodge it. This still leaves a
    // narrower race for a *joining* member specifically -- their log is created fresh (see
    // GroupManager.onControlEvent's getOrPut) the moment ANY GROUP_CONTROL_EVENT for that groupId
    // arrives over any pairwise session, not just from the inviter, so an attacker who merely
    // predicts/knows the groupId (e.g. because they crafted the invite) and holds any pairwise
    // session with the joiner could race a forged CREATE_GROUP in ahead of the real inviter's
    // bootstrap. [expectGenesis] closes that: GroupInvite now carries the real genesis hash
    // (signed by the inviter, who already knows it), and the joiner pins it via expectGenesis
    // before ever ingesting an event, so a forged genesis is simply never selected regardless of
    // arrival order -- this mirrors the same trust-on-first-use model already used for signing-key
    // pinning elsewhere (see E2eeManager.pinnedSigningIdentityKeys), just pinned from a signed,
    // out-of-band-delivered value instead of from whatever arrives first.
    private var pinnedGenesisHash: String? = null

    /**
     * Pins the expected genesis hash *before* any event has been ingested — see this field's own
     * doc above. A no-op if a genesis is already pinned (either from an earlier call, or lazily by
     * [canonicalPath] once real history exists), so calling this on a log that already has state
     * can never retroactively change which genesis it's committed to.
     */
    fun expectGenesis(hash: ByteArray) {
        if (pinnedGenesisHash == null) pinnedGenesisHash = hash.toHex()
    }

    /** The pinned genesis event's hash, or null if none is pinned yet (no event ingested, and [expectGenesis] never called) — what [messenger.common.group.GroupInvite.create] should embed so a joiner can pin it before ever ingesting an event. */
    fun genesisHash(): ByteArray? = pinnedGenesisHash?.hexToByteArray()

    var state: GroupState = GroupState.empty(groupId)
        private set

    companion object {
        // DoS: recompute() replays the *entire* canonical path on every single ingest(), and
        // GroupManager.store.load() replays the whole thing again on every app launch -- with no
        // ceiling, any current (or since-removed, since removal doesn't retroactively invalidate
        // events they already got accepted into the shared history) member could keep this log
        // growing forever, making every future ingest() and every future app launch progressively
        // more expensive for every other member. The relay-side GroupEventRateLimiter throttles how
        // *fast* one device can push events; this caps the total regardless of how slowly they
        // trickle it in. 5000 is comfortably above any real group's realistic lifetime event count
        // (member/role/setting changes), not a limit anyone using the app normally would ever hit.
        private const val MAX_EVENTS_PER_GROUP = 5000
    }

    /** The canonical tip's hash, or [GENESIS_HASH] if nothing has been ingested yet — what a new event's `prevEventHash` should point to. */
    fun headHash(): ByteArray = canonicalPath().lastOrNull()?.let { eventsByHash.getValue(it).hash() } ?: GENESIS_HASH

    /**
     * Adds [event] to the known event set and recomputes [state]. Returns true if the event was
     * well-formed, for the right group, and correctly signed — "accepted into the log", which is
     * independent of whether it turns out to be authorized or lands on the canonical path at all.
     * Idempotent: re-ingesting an already-known event (by hash) is a harmless no-op.
     */
    fun ingest(event: GroupControlEvent): Boolean {
        if (!event.groupId.contentEquals(groupId)) return false
        if (!event.verifySignature()) return false
        val hash = event.hashHex()
        if (eventsByHash.containsKey(hash)) return true
        if (eventsByHash.size >= MAX_EVENTS_PER_GROUP) return false
        eventsByHash[hash] = event
        childrenOf.getOrPut(event.prevEventHash.toHex()) { mutableListOf() }.add(hash)
        recompute()
        return true
    }

    /** Every event this log has ever seen, in canonical order — what a member with a stale [headHash] should be sent back in response to a sync request (see the plan's `GROUP_CONTROL_SYNC_REQUEST`). */
    fun eventsInCanonicalOrder(): List<GroupControlEvent> = canonicalPath().map { eventsByHash.getValue(it) }

    /**
     * Every event this log has ever accepted, INCLUDING ones not on the canonical path (the losing
     * side of a resolved fork). This is what must be persisted — dropping the non-canonical events
     * would lose the ability to recognise a later event built on top of one of them (it'd look like
     * unknown history and trigger an endless sync request), and would break duplicate detection on
     * reload. Order is unspecified; [ingest] re-canonicalizes regardless.
     */
    fun allEvents(): List<GroupControlEvent> = eventsByHash.values.toList()

    fun eventByHash(hashHex: String): GroupControlEvent? = eventsByHash[hashHex]

    private fun canonicalPath(): List<String> {
        val path = ArrayList<String>()
        if (pinnedGenesisHash == null) {
            // Only a real CREATE_GROUP can ever become the root -- if some other event type won a
            // race to claim prevEventHash = GENESIS_HASH first (or an attacker aims a junk event at
            // that slot specifically to grab the pin), falling through to it here would permanently
            // wedge this group at an empty state, before the real genesis ever gets a chance.
            pinnedGenesisHash = childrenOf[GENESIS_HASH.toHex()]
                ?.filter { eventsByHash[it]?.eventType == GroupEventType.CREATE_GROUP }
                ?.minOrNull()
        }
        var current = pinnedGenesisHash ?: return path
        path += current
        while (true) {
            val next = childrenOf[current]?.minOrNull() ?: break
            path += next
            current = next
        }
        return path
    }

    private fun recompute() {
        var computed = GroupState.empty(groupId)
        for (hash in canonicalPath()) {
            computed = applyIfAuthorized(computed, eventsByHash.getValue(hash))
        }
        state = computed
    }

    // CRASH/DoS: every branch below decodes event.payload with a fixed-length ByteBuffer read and
    // no bounds check of its own -- a payload that's the wrong length (an old build's persisted
    // event replayed against a newer, incompatible wire shape; or a malicious/buggy peer's
    // malformed-but-validly-signed event, since verifySignature only proves who signed it, not
    // that the payload decodes) throws straight out of decode. Since recompute() replays the
    // *entire* canonical path on every ingest() and store.load() replays the whole log on every
    // process start, one such event didn't just get skipped -- it permanently wedged this log (and,
    // for GroupManager's persisted logs, crashed the app on every single future launch). Treating a
    // decode failure the same as "not authorized" (state unchanged) keeps this consistent with
    // every other rejection path below instead of being the one way a single bad event takes the
    // whole log down.
    private fun applyIfAuthorized(state: GroupState, event: GroupControlEvent): GroupState = try {
        applyIfAuthorizedUnsafe(state, event)
    } catch (e: Exception) {
        state
    }

    private fun applyIfAuthorizedUnsafe(state: GroupState, event: GroupControlEvent): GroupState {
        val signerHex = event.signerDhKey.toHex()
        // SECURITY: a GroupControlEvent's signerSigningPublicKey is otherwise just a self-declared
        // field — verifySignature() only proves internal self-consistency (whoever holds that
        // specific signing key produced that specific signature), not that it's really the signing
        // key signerDhKey's owner was ever given. Requiring it to match this member's *pinned*
        // signingKey (set once, at ADD_MEMBER/genesis time — see GroupMember) is what stops anyone
        // who merely knows a member's public dhKey from forging events in their name with a
        // throwaway keypair of their own.
        val signer = state.members[signerHex]?.takeIf { it.signingKey.contentEquals(event.signerSigningPublicKey) }
        return when (event.eventType) {
            GroupEventType.CREATE_GROUP -> {
                if (state.members.isNotEmpty() || !event.prevEventHash.contentEquals(GENESIS_HASH)) return state
                val name = GroupEventPayloads.decodeCreateGroupName(event.payload)
                // Genesis is the group's own trust root -- there is nothing to pin the creator's
                // signing key against yet, so it's trusted from the event itself, once, here only.
                state.copy(name = name, members = mapOf(signerHex to GroupMember(event.signerDhKey, GroupRole.OWNER, signingKey = event.signerSigningPublicKey)))
            }

            GroupEventType.ADD_MEMBER -> {
                val canAdd = signer?.role == GroupRole.OWNER ||
                    signer?.hasPermission(AdminPermission.ADD_MEMBERS) == true ||
                    (state.inviteLinksEnabled && signer != null)
                if (!canAdd) return state
                val decoded = GroupEventPayloads.decodeAddMember(event.payload)
                val memberHex = decoded.memberDhKey.toHex()
                if (state.members.containsKey(memberHex)) return state
                state.copy(members = state.members + (memberHex to GroupMember(decoded.memberDhKey, GroupRole.MEMBER, signingKey = decoded.memberSigningKey)))
            }

            GroupEventType.REMOVE_MEMBER -> {
                val canRemove = signer?.role == GroupRole.OWNER || signer?.hasPermission(AdminPermission.REMOVE_MEMBERS) == true
                if (!canRemove) return state
                val targetHex = GroupEventPayloads.decodeMemberKey(event.payload).toHex()
                if (state.members[targetHex]?.role == GroupRole.OWNER) return state // owner can't be removed, only transferred/self-left
                state.copy(members = state.members - targetHex)
            }

            GroupEventType.PROMOTE_ADMIN -> {
                if (signer?.role != GroupRole.OWNER) return state
                val decoded = GroupEventPayloads.decodePromoteAdmin(event.payload)
                val targetHex = decoded.memberDhKey.toHex()
                val target = state.members[targetHex] ?: return state
                if (target.role == GroupRole.OWNER) return state
                state.copy(members = state.members + (targetHex to GroupMember(decoded.memberDhKey, GroupRole.ADMIN, decoded.permissions, signingKey = target.signingKey)))
            }

            GroupEventType.DEMOTE_ADMIN -> {
                if (signer?.role != GroupRole.OWNER) return state
                val targetHex = GroupEventPayloads.decodeMemberKey(event.payload).toHex()
                val target = state.members[targetHex] ?: return state
                if (target.role != GroupRole.ADMIN) return state
                state.copy(members = state.members + (targetHex to GroupMember(target.dhKey, GroupRole.MEMBER, signingKey = target.signingKey)))
            }

            GroupEventType.TRANSFER_OWNERSHIP -> {
                if (signer?.role != GroupRole.OWNER) return state
                val newOwnerKey = GroupEventPayloads.decodeMemberKey(event.payload)
                val newOwnerHex = newOwnerKey.toHex()
                val newOwner = state.members[newOwnerHex] ?: return state
                val allPermissions = AdminPermission.ADD_MEMBERS or AdminPermission.REMOVE_MEMBERS or AdminPermission.CHANGE_INFO
                state.copy(
                    members = state.members +
                        (signerHex to GroupMember(event.signerDhKey, GroupRole.ADMIN, allPermissions, signingKey = signer.signingKey)) +
                        (newOwnerHex to GroupMember(newOwner.dhKey, GroupRole.OWNER, signingKey = newOwner.signingKey)),
                )
            }

            GroupEventType.SET_GROUP_INFO -> {
                val canChange = signer?.role == GroupRole.OWNER || signer?.hasPermission(AdminPermission.CHANGE_INFO) == true
                if (!canChange) return state
                val decoded = GroupEventPayloads.decodeGroupInfo(event.payload)
                state.copy(name = decoded.name, avatarIconId = decoded.avatarIconId)
            }

            GroupEventType.SET_ANNOUNCEMENT_MODE -> {
                if (signer?.role != GroupRole.OWNER) return state
                state.copy(announcementMode = GroupEventPayloads.decodeBoolean(event.payload))
            }

            GroupEventType.SET_INVITE_LINKS_ENABLED -> {
                if (signer?.role != GroupRole.OWNER) return state
                state.copy(inviteLinksEnabled = GroupEventPayloads.decodeBoolean(event.payload))
            }

            GroupEventType.LEAVE_GROUP -> {
                if (signer == null) return state
                state.copy(members = state.members - signerHex)
            }

            else -> state
        }
    }
}
