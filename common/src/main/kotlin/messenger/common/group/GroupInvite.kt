package messenger.common.group

import java.nio.ByteBuffer
import java.util.Base64
import messenger.common.crypto.Ed25519Signatures

/**
 * A shareable group invite link/QR. The payload is signed by the inviter so a recipient can't be
 * tricked into a [GroupControlLog] join by a link nobody in the group actually issued, and carries
 * an expiry so a leaked old link stops working on its own. The actual add still goes through the
 * normal authorization path (the inviter, on receiving the resulting join request, re-verifies the
 * invite's own signature/expiry/self-identity — see `GroupManager.onJoinRequest` — *and* only then
 * emits an ADD_MEMBER if invite links are still enabled and they're still allowed to add) — this
 * link is the *transport* for a join request, not an authorization bypass. The whole signed
 * [Invite] (not just the groupId) travels with the join request itself precisely so the inviter has
 * something to re-verify instead of trusting "invite links are enabled" as the only gate — see the
 * `GROUP_JOIN_REQUEST` handling in [messenger.common.client.MessengerClient].
 *
 * Text form: `voron-group:<base64url(payload)>`, mirroring the `voron:` contact-QR convention.
 */
object GroupInvite {
    const val PREFIX = "voron-group:"
    private const val DH_KEY_LENGTH = 32
    private const val SIGNING_KEY_LENGTH = Ed25519Signatures.KEY_LENGTH
    private const val SIGNATURE_LENGTH = Ed25519Signatures.SIGNATURE_LENGTH

    class Invite(
        val groupId: ByteArray,
        val inviterDhKey: ByteArray,
        val inviterSigningPublicKey: ByteArray,
        val expiresAtMillis: Long,
        val signature: ByteArray,
    ) {
        fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis
        fun verifySignature(): Boolean = Ed25519Signatures.verify(inviterSigningPublicKey, signedBytes(), signature)

        private fun signedBytes(): ByteArray = signedBytesFor(groupId, inviterDhKey, inviterSigningPublicKey, expiresAtMillis)

        /** Re-serializes this already-signed invite back to the raw bytes [decode] parses — for retransmitting it as a join request's proof, without re-signing anything. */
        fun encode(): ByteArray {
            val buffer = ByteBuffer.allocate(GROUP_ID_LENGTH + DH_KEY_LENGTH + SIGNING_KEY_LENGTH + 8 + SIGNATURE_LENGTH)
            buffer.put(groupId)
            buffer.put(inviterDhKey)
            buffer.put(inviterSigningPublicKey)
            buffer.putLong(expiresAtMillis)
            buffer.put(signature)
            return buffer.array()
        }
    }

    private fun signedBytesFor(groupId: ByteArray, inviterDhKey: ByteArray, inviterSigningPublicKey: ByteArray, expiresAtMillis: Long): ByteArray {
        val buffer = ByteBuffer.allocate(GROUP_ID_LENGTH + DH_KEY_LENGTH + SIGNING_KEY_LENGTH + 8)
        buffer.put(groupId)
        buffer.put(inviterDhKey)
        buffer.put(inviterSigningPublicKey)
        buffer.putLong(expiresAtMillis)
        return buffer.array()
    }

    /** Builds a signed invite link string valid until [expiresAtMillis]. */
    fun create(
        groupId: ByteArray,
        inviterDhKey: ByteArray,
        inviterSigningKeyPair: Ed25519Signatures.SigningKeyPair,
        expiresAtMillis: Long,
    ): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(encode(groupId, inviterDhKey, inviterSigningKeyPair, expiresAtMillis))

    /** Same as [create] but returns the raw signed bytes without the `voron-group:`/base64 text wrapping — used to transmit an invite as a join request's proof (see [messenger.common.client.MessengerClient.sendGroupJoinRequest]) instead of round-tripping through link text. */
    fun encode(
        groupId: ByteArray,
        inviterDhKey: ByteArray,
        inviterSigningKeyPair: Ed25519Signatures.SigningKeyPair,
        expiresAtMillis: Long,
    ): ByteArray {
        val signature = Ed25519Signatures.sign(
            inviterSigningKeyPair.privateKey,
            signedBytesFor(groupId, inviterDhKey, inviterSigningKeyPair.publicKey, expiresAtMillis),
        )
        val buffer = ByteBuffer.allocate(GROUP_ID_LENGTH + DH_KEY_LENGTH + SIGNING_KEY_LENGTH + 8 + SIGNATURE_LENGTH)
        buffer.put(groupId)
        buffer.put(inviterDhKey)
        buffer.put(inviterSigningKeyPair.publicKey)
        buffer.putLong(expiresAtMillis)
        buffer.put(signature)
        return buffer.array()
    }

    /** Parses a `voron-group:` link, or null if it isn't one / is malformed. Does NOT check the signature or expiry — callers do that via [Invite.verifySignature]/[Invite.isExpired] so they can surface distinct errors. */
    fun parse(link: String): Invite? {
        val trimmed = link.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val raw = runCatching { Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX)) }.getOrNull() ?: return null
        return decode(raw)
    }

    /** Parses the raw signed bytes [encode] produces (no link text wrapping), or null if malformed. Same non-verification contract as [parse]. */
    fun decode(raw: ByteArray): Invite? {
        val expectedSize = GROUP_ID_LENGTH + DH_KEY_LENGTH + SIGNING_KEY_LENGTH + 8 + SIGNATURE_LENGTH
        if (raw.size != expectedSize) return null
        val buffer = ByteBuffer.wrap(raw)
        val groupId = ByteArray(GROUP_ID_LENGTH).also { buffer.get(it) }
        val inviterDhKey = ByteArray(DH_KEY_LENGTH).also { buffer.get(it) }
        val inviterSigningPublicKey = ByteArray(SIGNING_KEY_LENGTH).also { buffer.get(it) }
        val expiresAtMillis = buffer.long
        val signature = ByteArray(SIGNATURE_LENGTH).also { buffer.get(it) }
        return Invite(groupId, inviterDhKey, inviterSigningPublicKey, expiresAtMillis, signature)
    }
}
