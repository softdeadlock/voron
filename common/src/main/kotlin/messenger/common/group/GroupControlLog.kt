package messenger.common.group

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

    var state: GroupState = GroupState.empty(groupId)
        private set

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
        var current = GENESIS_HASH.toHex()
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

    private fun applyIfAuthorized(state: GroupState, event: GroupControlEvent): GroupState {
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
                state.copy(name = GroupEventPayloads.decodeGroupInfoName(event.payload))
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
