package messenger.common.group

import java.nio.ByteBuffer
import java.security.MessageDigest
import messenger.common.crypto.Ed25519Signatures
import messenger.common.util.toHex

/** Group membership/role events — see [GroupControlLog] for how these are authorized, applied, and kept in sync across members with no server involved. */
object GroupEventType {
    const val CREATE_GROUP: Byte = 0x01
    const val ADD_MEMBER: Byte = 0x02
    const val REMOVE_MEMBER: Byte = 0x03
    const val PROMOTE_ADMIN: Byte = 0x04
    const val DEMOTE_ADMIN: Byte = 0x05
    const val TRANSFER_OWNERSHIP: Byte = 0x06
    const val SET_GROUP_INFO: Byte = 0x07
    const val SET_ANNOUNCEMENT_MODE: Byte = 0x08
    const val SET_INVITE_LINKS_ENABLED: Byte = 0x09
    const val LEAVE_GROUP: Byte = 0x0A
}

/** Bitmask of what an ADMIN (not the OWNER, who implicitly has all of these) is allowed to do — assigned by the owner in the [GroupEventType.PROMOTE_ADMIN] event that made them an admin. */
object AdminPermission {
    const val ADD_MEMBERS = 0x01
    const val REMOVE_MEMBERS = 0x02
    const val CHANGE_INFO = 0x04
}

/** 32 all-zero bytes — the [GroupControlEvent.prevEventHash] every group's genesis [GroupEventType.CREATE_GROUP] event uses, since it has no predecessor. */
val GENESIS_HASH: ByteArray = ByteArray(32)

/**
 * One signed entry in a group's membership/role event log. Self-contained and independently
 * verifiable: [signerSigningPublicKey] is carried inline (rather than looked up from local state)
 * so a brand new member replaying the whole log from genesis can verify every event's signature
 * without already knowing who anyone was at the time each event was created.
 *
 * [verifySignature] alone only proves *internal* self-consistency (whoever holds the private key
 * matching [signerSigningPublicKey] produced [signature] over this exact content) — it says nothing
 * about whether that signing key actually belongs to [signerDhKey]'s owner, since both fields are
 * self-declared by whoever built the event. [GroupControlLog] is what pins each member's signing
 * key the first time it can trust one (genesis for the creator, the ADD_MEMBER payload for everyone
 * else) and rejects any later event whose declared key doesn't match — don't rely on
 * [verifySignature] on its own as proof of who signed something.
 *
 * [prevEventHash] chains each event to the specific predecessor its signer had seen when they
 * created it — this is what lets [GroupControlLog] detect and deterministically resolve the rare
 * case of two members concurrently (while mutually offline) creating conflicting events on top of
 * the same predecessor.
 */
class GroupControlEvent(
    val groupId: ByteArray,
    val prevEventHash: ByteArray,
    val eventType: Byte,
    val signerDhKey: ByteArray,
    val signerSigningPublicKey: ByteArray,
    val payload: ByteArray,
    val signature: ByteArray,
) {
    /** Everything a signature covers — [sign] and [verifySignature] must use exactly this. */
    private fun signedBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(GROUP_ID_LENGTH + 32 + 1 + signerDhKey.size + signerSigningPublicKey.size + 4 + payload.size)
        buffer.put(groupId)
        buffer.put(prevEventHash)
        buffer.put(eventType)
        buffer.put(signerDhKey)
        buffer.put(signerSigningPublicKey)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun verifySignature(): Boolean = Ed25519Signatures.verify(signerSigningPublicKey, signedBytes(), signature)

    /** SHA-256 of the fully-encoded (including signature) event — used as the next event's [prevEventHash] and as the deterministic fork tie-breaker in [GroupControlLog]. */
    fun hash(): ByteArray = MessageDigest.getInstance("SHA-256").digest(encode())

    fun encode(): ByteArray {
        val signed = signedBytes()
        val buffer = ByteBuffer.allocate(signed.size + signature.size)
        buffer.put(signed)
        buffer.put(signature)
        return buffer.array()
    }

    companion object {
        private const val DH_KEY_LENGTH = 32
        private const val SIGNING_KEY_LENGTH = Ed25519Signatures.KEY_LENGTH
        private const val SIGNATURE_LENGTH = Ed25519Signatures.SIGNATURE_LENGTH

        /** Builds and signs a new event on top of [prevEventHash] — use [GENESIS_HASH] for a group's very first (`CREATE_GROUP`) event. */
        fun create(
            groupId: ByteArray,
            prevEventHash: ByteArray,
            eventType: Byte,
            signerDhKey: ByteArray,
            signerSigningKeyPair: Ed25519Signatures.SigningKeyPair,
            payload: ByteArray,
        ): GroupControlEvent {
            val unsigned = GroupControlEvent(groupId, prevEventHash, eventType, signerDhKey, signerSigningKeyPair.publicKey, payload, ByteArray(0))
            val signature = Ed25519Signatures.sign(signerSigningKeyPair.privateKey, unsigned.signedBytes())
            return GroupControlEvent(groupId, prevEventHash, eventType, signerDhKey, signerSigningKeyPair.publicKey, payload, signature)
        }

        fun decode(bytes: ByteArray): GroupControlEvent {
            val buffer = ByteBuffer.wrap(bytes)
            val groupId = ByteArray(GROUP_ID_LENGTH).also { buffer.get(it) }
            val prevEventHash = ByteArray(32).also { buffer.get(it) }
            val eventType = buffer.get()
            val signerDhKey = ByteArray(DH_KEY_LENGTH).also { buffer.get(it) }
            val signerSigningPublicKey = ByteArray(SIGNING_KEY_LENGTH).also { buffer.get(it) }
            val payloadLength = buffer.int
            require(payloadLength in 0..MAX_PAYLOAD_LENGTH) { "corrupt group control event: implausible payload length $payloadLength" }
            val payload = ByteArray(payloadLength).also { buffer.get(it) }
            val signature = ByteArray(SIGNATURE_LENGTH).also { buffer.get(it) }
            return GroupControlEvent(groupId, prevEventHash, eventType, signerDhKey, signerSigningPublicKey, payload, signature)
        }

        // Locally-generated/replayed data, not raw untrusted wire input beyond the envelope's own
        // AEAD — but a corrupt/truncated event shouldn't be able to make `ByteArray(payloadLength)`
        // allocate something absurd either.
        private const val MAX_PAYLOAD_LENGTH = 8192
    }
}

/** Convenience for keying maps by an event's hash. */
fun GroupControlEvent.hashHex(): String = hash().toHex()
