package messenger.common.group

import java.nio.ByteBuffer
import messenger.common.util.toHex

enum class GroupRole { OWNER, ADMIN, MEMBER }

/**
 * [permissions] is a bitmask of [AdminPermission] flags and is only meaningful when [role] is
 * [GroupRole.ADMIN] — the owner implicitly has every permission, plain members have none.
 *
 * [signingKey] is pinned once, at the moment this member was added (or, for the group's creator,
 * at genesis) — see [GroupControlLog.applyIfAuthorized]'s `signer` lookup — and never changes for
 * the lifetime of this dhKey's membership. This is what stops [GroupControlEvent.signerSigningPublicKey]
 * from being trusted purely because it's self-consistent with its own signature: a
 * [GroupControlEvent] is otherwise a "self-contained" envelope carrying an unauthenticated,
 * self-declared signing key, so without this pin anyone who knows a member's public dhKey (which is
 * not secret — it's the routing address shared in every invite/QR) could forge control events
 * (REMOVE_MEMBER, PROMOTE_ADMIN, TRANSFER_OWNERSHIP, ...) claiming to be signed by them, using a
 * throwaway keypair of the forger's own choosing, and have them pass [GroupControlEvent.verifySignature].
 */
data class GroupMember(val dhKey: ByteArray, val role: GroupRole, val permissions: Int = 0, val signingKey: ByteArray) {
    fun hasPermission(flag: Int): Boolean = role == GroupRole.OWNER || (role == GroupRole.ADMIN && permissions and flag != 0)

    // Plain data-class equals()/hashCode() compare ByteArray properties by reference, not content
    // (arrays don't override Any.equals) -- silently wrong for anything that decodes the same bytes
    // twice into separate arrays (e.g. two GroupControlLog replays of the same event history), so
    // it's overridden here explicitly rather than left as the default trap.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupMember) return false
        return dhKey.contentEquals(other.dhKey) && role == other.role && permissions == other.permissions && signingKey.contentEquals(other.signingKey)
    }

    override fun hashCode(): Int {
        var result = dhKey.contentHashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + permissions
        result = 31 * result + signingKey.contentHashCode()
        return result
    }
}

/**
 * The current, fully-computed membership/settings snapshot for one group — what
 * [GroupControlLog.state] exposes after replaying and authorizing every event in canonical order.
 * Never constructed directly by callers outside this package; always the result of applying a
 * validated event sequence.
 */
data class GroupState(
    val groupId: ByteArray,
    val name: String,
    val members: Map<String, GroupMember>, // keyed by dhKey.toHex()
    val announcementMode: Boolean,
    val inviteLinksEnabled: Boolean,
) {
    val ownerKeyHex: String? get() = members.values.firstOrNull { it.role == GroupRole.OWNER }?.dhKey?.toHex()

    /** True once nobody with OWNER/ADMIN role remains — see the plan's "orphaned group" note: this is the client-visible fallback state when a graceful hand-off never happened (lost device, force-uninstall) and nobody can change membership/settings anymore, though ordinary messaging is unaffected. */
    val isFrozen: Boolean get() = members.values.none { it.role == GroupRole.OWNER || it.role == GroupRole.ADMIN }

    // See GroupMember's equals()/hashCode() override for why this can't be the plain data-class default.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupState) return false
        return groupId.contentEquals(other.groupId) && name == other.name && members == other.members &&
            announcementMode == other.announcementMode && inviteLinksEnabled == other.inviteLinksEnabled
    }

    override fun hashCode(): Int {
        var result = groupId.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + members.hashCode()
        result = 31 * result + announcementMode.hashCode()
        result = 31 * result + inviteLinksEnabled.hashCode()
        return result
    }

    companion object {
        fun empty(groupId: ByteArray): GroupState = GroupState(groupId, name = "", members = emptyMap(), announcementMode = false, inviteLinksEnabled = false)
    }
}

/** Payload encode/decode for each [GroupEventType] — kept separate from [GroupControlEvent] itself so the wire-level event stays a dumb signed envelope and every type-specific shape lives in one place. */
object GroupEventPayloads {
    fun encodeCreateGroup(name: String): ByteArray = encodeString(name)
    fun decodeCreateGroupName(payload: ByteArray): String = decodeString(ByteBuffer.wrap(payload))

    fun encodeMemberKey(memberDhKey: ByteArray): ByteArray = memberDhKey
    fun decodeMemberKey(payload: ByteArray): ByteArray = payload

    /** ADD_MEMBER carries the new member's signing key alongside their dhKey — [GroupControlLog] pins it as that member's [GroupMember.signingKey] right away, rather than waiting for (and trusting) some later self-signed event from them. The adder is expected to have resolved this via [messenger.common.client.MessengerClient.resolveSigningIdentityKey], the same TOFU trust a first 1:1 contact already gets. */
    data class AddMemberPayload(val memberDhKey: ByteArray, val memberSigningKey: ByteArray)

    fun encodeAddMember(memberDhKey: ByteArray, memberSigningKey: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(memberDhKey.size + memberSigningKey.size)
        buffer.put(memberDhKey)
        buffer.put(memberSigningKey)
        return buffer.array()
    }

    fun decodeAddMember(payload: ByteArray): AddMemberPayload {
        val buffer = ByteBuffer.wrap(payload)
        val memberDhKey = ByteArray(32).also { buffer.get(it) }
        val memberSigningKey = ByteArray(32).also { buffer.get(it) }
        return AddMemberPayload(memberDhKey, memberSigningKey)
    }

    fun encodePromoteAdmin(memberDhKey: ByteArray, permissions: Int): ByteArray {
        val buffer = ByteBuffer.allocate(memberDhKey.size + 4)
        buffer.put(memberDhKey)
        buffer.putInt(permissions)
        return buffer.array()
    }

    data class PromoteAdminPayload(val memberDhKey: ByteArray, val permissions: Int)

    fun decodePromoteAdmin(payload: ByteArray): PromoteAdminPayload {
        val buffer = ByteBuffer.wrap(payload)
        val memberDhKey = ByteArray(32).also { buffer.get(it) }
        val permissions = buffer.int
        return PromoteAdminPayload(memberDhKey, permissions)
    }

    fun encodeGroupInfo(name: String): ByteArray = encodeString(name)
    fun decodeGroupInfoName(payload: ByteArray): String = decodeString(ByteBuffer.wrap(payload))

    fun encodeBoolean(value: Boolean): ByteArray = byteArrayOf(if (value) 1 else 0)
    fun decodeBoolean(payload: ByteArray): Boolean = payload.isNotEmpty() && payload[0] != 0.toByte()

    private fun encodeString(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(2 + bytes.size)
        buffer.putShort(bytes.size.toShort())
        buffer.put(bytes)
        return buffer.array()
    }

    private fun decodeString(buffer: ByteBuffer): String {
        val length = buffer.short.toInt() and 0xFFFF
        val bytes = ByteArray(length).also { buffer.get(it) }
        return String(bytes, Charsets.UTF_8)
    }
}
