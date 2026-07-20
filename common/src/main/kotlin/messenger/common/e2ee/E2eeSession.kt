package messenger.common.e2ee

import messenger.common.crypto.Aead
import messenger.common.crypto.Hkdf
import messenger.common.crypto.X25519
import messenger.common.transport.NoiseStaticKeyPair
import messenger.common.util.toHex

/**
 * A 1:1 session: the Double Ratchet running on top of the X3DH root key. X3DH alone gives forward
 * secrecy (via the ephemeral/one-time prekeys) but NOT post-compromise security — if a chain key
 * is ever extracted, a plain symmetric ratchet lets an attacker keep deriving every future key
 * forever, since nothing new is ever mixed in. The DH-ratchet fixes that: every time the
 * conversation "turns" (the peer's ratchet public key changes, meaning they sent using a keypair
 * we haven't seen before), both sides fold a *fresh* Diffie-Hellman output into the root key. That
 * fresh randomness is exactly what lets the session heal after a compromise: an attacker who stole
 * an old chain key gains nothing from a ratchet step's new DH output unless they also stole the
 * matching private key, which is freshly generated and never reused.
 *
 * Bootstrapping mirrors the Signal X3DH-to-Double-Ratchet integration exactly, since it's
 * deliberately asymmetric: Bob (the responder) has no way to ratchet until he's seen an initiator
 * ratchet public key, so he reuses his already-published *signed prekey keypair* as his initial
 * ratchet keypair and starts with no sending chain at all. Alice (the initiator) *does* have Bob's
 * signed prekey public key already (it's in the bundle she fetched) and a fresh ratchet keypair of
 * her own, so she can perform one ratchet step immediately and has a sending chain from message 1
 * — this is a *different* keypair from the X3DH ephemeral (still sent alongside it, in
 * [E2eeMessage.Initial]); the ratchet keypair is carried in every message via [RatchetPayload].
 *
 * The X3DH associated data is bound into every AEAD tag so a message cannot be replayed into a
 * different identity pairing, and stays fixed for the session's whole lifetime (only the identity
 * keys that produced it matter, not which ratchet epoch a message happens to be in).
 */
class E2eeSession private constructor(
    private val associatedData: ByteArray,
    private var rootKey: ByteArray,
    private var selfRatchetPrivate: ByteArray,
    private var selfRatchetPublic: ByteArray,
    private var peerRatchetPublic: ByteArray?,
    private var sendingChain: SendingChain?,
    private var receivingChain: ReceivingChain?,
) {
    private var previousSendingChainLength = 0

    // Message keys skipped while retiring a *previous* ratchet epoch's receiving chain — lets a
    // message still in flight on the peer's old sending chain decrypt even after they've already
    // moved on to a new one. Keyed by "<peer ratchet pubkey hex>:<message number>". Bounded (oldest
    // entries evicted first) so a very long-lived session with many ratchet steps and some
    // genuinely never-delivered messages can't grow this without bound.
    private val retiredSkipped = object : LinkedHashMap<String, ByteArray>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > MAX_RETIRED_SKIPPED
    }

    /** Encrypts [plaintext] on the current sending chain, ratcheting it forward by one message. */
    fun encrypt(plaintext: ByteArray): RatchetPayload {
        val chain = requireNotNull(sendingChain) {
            "cannot send before this session's ratchet has been initialized (Bob-side sessions " +
                "can't send until they've received at least one message from the peer)"
        }
        val (number, key) = chain.next()
        val ciphertext = Aead.encrypt(key, Aead.ZERO_NONCE, associatedData, plaintext)
        return RatchetPayload(selfRatchetPublic, previousSendingChainLength, number, ciphertext)
    }

    /**
     * Decrypts [payload]. If [RatchetPayload.senderRatchetPublicKey] is one we haven't seen
     * before, this performs a DH-ratchet step first — but only *tentatively*: the new ratchet
     * state is computed on copies and only committed once the incoming message actually decrypts
     * under it. Without that, a relay (not a trusted party here) could inject a single bogus
     * ratchet header and permanently desync a real session — pure denial of service, since it
     * can't forge a valid AEAD tag, but a cheap one to rule out entirely.
     */
    fun decrypt(payload: RatchetPayload): ByteArray {
        val currentPeerPublic = peerRatchetPublic
        if (currentPeerPublic != null && payload.senderRatchetPublicKey.contentEquals(currentPeerPublic)) {
            val chain = requireNotNull(receivingChain) { "no receiving chain for the current ratchet epoch" }
            // Tentative, same as the new-epoch path below: messageKeyFor() derives and caches every
            // skipped key up to payload.messageNumber, which is otherwise-uncapped, attacker-supplied
            // wire data. Working on a copy and only committing after the AEAD tag actually checks out
            // means a forged messageNumber with garbage ciphertext never leaves anything behind in the
            // real chain — see SkippedKeyMemoryExhaustionExploitTest.
            val tentative = chain.copy()
            val key = tentative.messageKeyFor(payload.messageNumber)
            val plaintext = Aead.decrypt(key, Aead.ZERO_NONCE, associatedData, payload.ciphertext)
            receivingChain = tentative
            return plaintext
        }

        val retiredKeyId = retiredKeyId(payload.senderRatchetPublicKey, payload.messageNumber)
        val retiredKey = retiredSkipped[retiredKeyId]
        if (retiredKey != null) {
            val plaintext = Aead.decrypt(retiredKey, Aead.ZERO_NONCE, associatedData, payload.ciphertext)
            retiredSkipped.remove(retiredKeyId)
            return plaintext
        }

        val step = computeRatchetStep(payload.senderRatchetPublicKey, payload.previousChainLength)
        val key = step.receivingChain.messageKeyFor(payload.messageNumber)
        val plaintext = Aead.decrypt(key, Aead.ZERO_NONCE, associatedData, payload.ciphertext)
        commit(step)
        return plaintext
    }

    /** Everything a DH-ratchet step produces, computed without touching `this` — see [decrypt]. */
    private class RatchetStep(
        val rootKey: ByteArray,
        val selfRatchetPrivate: ByteArray,
        val selfRatchetPublic: ByteArray,
        val peerRatchetPublic: ByteArray,
        val receivingChain: ReceivingChain,
        val sendingChain: SendingChain,
        val previousSendingChainLength: Int,
        val retiredFromOldEpoch: Map<Int, ByteArray>,
        val oldPeerRatchetPublic: ByteArray?,
    )

    private fun computeRatchetStep(newPeerPublic: ByteArray, peerReportedPreviousChainLength: Int): RatchetStep {
        // Retire a *copy* of the current receiving chain (if any) rather than the real one: if this
        // whole step ultimately doesn't get committed (the incoming message fails to decrypt under
        // it), the real `receivingChain` must be exactly as usable as before this call.
        val retiredFromOldEpoch = receivingChain?.copy()?.retireSkippingTo(peerReportedPreviousChainLength).orEmpty()

        var dh1: ByteArray? = null
        var dh2: ByteArray? = null
        try {
            dh1 = X25519.dh(selfRatchetPrivate, newPeerPublic)
            val (rootAfterReceive, newReceivingChainKey) = kdfRk(rootKey, dh1)

            val fresh = X25519.generateKeyPair()
            dh2 = X25519.dh(fresh.privateKey, newPeerPublic)
            val (rootAfterSend, newSendingChainKey) = kdfRk(rootAfterReceive, dh2)

            return RatchetStep(
                rootKey = rootAfterSend,
                selfRatchetPrivate = fresh.privateKey,
                selfRatchetPublic = fresh.publicKey,
                peerRatchetPublic = newPeerPublic,
                receivingChain = ReceivingChain(newReceivingChainKey),
                sendingChain = SendingChain(newSendingChainKey),
                previousSendingChainLength = sendingChain?.counter ?: 0,
                retiredFromOldEpoch = retiredFromOldEpoch,
                oldPeerRatchetPublic = peerRatchetPublic,
            )
        } finally {
            wipe(dh1, dh2)
        }
    }

    private fun commit(step: RatchetStep) {
        wipe(selfRatchetPrivate)
        step.oldPeerRatchetPublic?.let { oldPeer ->
            for ((number, key) in step.retiredFromOldEpoch) {
                retiredSkipped[retiredKeyId(oldPeer, number)] = key
            }
        }
        rootKey = step.rootKey
        selfRatchetPrivate = step.selfRatchetPrivate
        selfRatchetPublic = step.selfRatchetPublic
        peerRatchetPublic = step.peerRatchetPublic
        receivingChain = step.receivingChain
        sendingChain = step.sendingChain
        previousSendingChainLength = step.previousSendingChainLength
    }

    private fun retiredKeyId(peerRatchetPublicKey: ByteArray, messageNumber: Int) =
        "${peerRatchetPublicKey.toHex()}:$messageNumber"

    companion object {
        private const val KDF_RK_INFO = "messenger-double-ratchet-kdf-rk-v1"
        private const val MAX_RETIRED_SKIPPED = 2000

        fun forInitiator(rootKey: ByteArray, peerSignedPreKeyPublic: ByteArray, associatedData: ByteArray): E2eeSession {
            val fresh = X25519.generateKeyPair()
            var dh: ByteArray? = null
            val (newRootKey, sendingChainKey) = try {
                dh = X25519.dh(fresh.privateKey, peerSignedPreKeyPublic)
                kdfRk(rootKey, dh)
            } finally {
                wipe(dh)
            }
            return E2eeSession(
                associatedData = associatedData,
                rootKey = newRootKey,
                selfRatchetPrivate = fresh.privateKey,
                selfRatchetPublic = fresh.publicKey,
                peerRatchetPublic = peerSignedPreKeyPublic,
                sendingChain = SendingChain(sendingChainKey),
                receivingChain = null,
            )
        }

        fun forResponder(rootKey: ByteArray, ownSignedPreKeyPair: NoiseStaticKeyPair, associatedData: ByteArray): E2eeSession =
            E2eeSession(
                associatedData = associatedData,
                rootKey = rootKey,
                // A defensive copy, not the live reference: this signed prekey is shared across
                // every new responder session until the next weekly rotation (see PreKeyStore), but
                // commit() unconditionally wipes whatever selfRatchetPrivate holds once the first
                // ratchet step on THIS session succeeds. Aliasing the real key here meant the very
                // first session anyone completed with us zeroed out our actual persisted signed
                // prekey in place — breaking every other new contact's handshake against the same
                // (still-published) signed prekey until it happened to rotate.
                selfRatchetPrivate = ownSignedPreKeyPair.privateKey.copyOf(),
                selfRatchetPublic = ownSignedPreKeyPair.publicKey,
                peerRatchetPublic = null,
                sendingChain = null,
                receivingChain = null,
            )

        /** The Double Ratchet's KDF_RK: HKDF with the *old* root key as salt, the DH output as IKM. */
        private fun kdfRk(rk: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
            val prk = Hkdf.extract(salt = rk, ikm = dhOutput)
            val output = Hkdf.expand(prk, KDF_RK_INFO.toByteArray(), 64)
            return output.copyOfRange(0, 32) to output.copyOfRange(32, 64)
        }

        private fun wipe(vararg arrays: ByteArray?) {
            for (array in arrays) array?.fill(0)
        }
    }
}

/**
 * A Double Ratchet message header + ciphertext: [senderRatchetPublicKey] is the DH public key the
 * sender used for this and every message since their last ratchet step; [previousChainLength] is
 * how many messages they sent on the chain *before* that step (their "PN"), so the receiver knows
 * how far to retire-and-cache their own matching receiving chain instead of discarding still-
 * in-flight message keys; [messageNumber] is this message's index within the sender's current
 * chain.
 */
class RatchetPayload(
    val senderRatchetPublicKey: ByteArray,
    val previousChainLength: Int,
    val messageNumber: Int,
    val ciphertext: ByteArray,
) {
    internal fun encodeHeaderInto(buffer: java.nio.ByteBuffer) {
        buffer.put(senderRatchetPublicKey)
        buffer.putInt(previousChainLength)
        buffer.putInt(messageNumber)
    }

    companion object {
        internal const val HEADER_LENGTH = 32 + 4 + 4

        internal fun decodeFrom(buffer: java.nio.ByteBuffer): RatchetPayload {
            val ratchetKey = ByteArray(32).also { buffer.get(it) }
            val previousChainLength = buffer.int
            val number = buffer.int
            val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
            return RatchetPayload(ratchetKey, previousChainLength, number, ciphertext)
        }
    }
}
