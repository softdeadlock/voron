package messenger.common.group

import java.security.SecureRandom
import messenger.common.crypto.Aead
import messenger.common.e2ee.ReceivingChain
import messenger.common.e2ee.SendingChain
import messenger.common.util.toHex

/**
 * Pragmatic group E2EE via "sender keys" (the scheme early WhatsApp/Signal group chats used,
 * *not* the full RFC 9420 MLS/TreeKEM protocol the roadmap originally named — see the phase-11
 * design note for why: TreeKEM is its own multi-week protocol implementation, and every crypto
 * primitive it would need beyond this is unaudited-by-design here just like the rest of `common`).
 * Each member owns one [SendingChain] for their own outgoing messages, seeded fresh on every
 * "epoch" (see [rekeySelf]) and handed to every other member as a [GroupSenderKeyMessage] — but
 * only ever *inside* an already-E2EE pairwise envelope (that distribution step is exactly a normal
 * 1:1 message, reusing 100% of the existing X3DH+Double-Ratchet stack; no new key-distribution
 * crypto is needed at all). Every other member's sender key is tracked here as a [ReceivingChain].
 *
 * Rekey policy: **every membership change (add or remove) rekeys the whole group** — every
 * current member mints a fresh chain and redistributes it to the new full member list. This is
 * what gives a removed member no access to future messages (they never receive the new epoch's
 * key) and a newly added member no access to past messages (their [ReceivingChain] for anyone
 * only starts existing from the epoch they were added into) — the two guarantees sender-keys can
 * give without a ratchet tree. It does NOT give per-message forward secrecy the way the 1:1 Double
 * Ratchet does; a single sender-key compromise exposes every message in that epoch. Callers
 * (`GroupManager` on Android) should also rekey periodically for the same reason signed prekeys
 * rotate, even with no membership change — not yet wired up as of this pass.
 */
class GroupCryptoSession(val groupId: ByteArray, private val ownDhKey: ByteArray) {
    private var myEpoch = -1
    private var mySendingChain: SendingChain? = null

    // The seed a running SendingChain was constructed from is otherwise unrecoverable once
    // messages have advanced it (SendingChain never exposes its current internal chain key) --
    // kept here purely so [currentSenderKeyMessageOrNull] can re-derive the *same* distribution
    // message on demand, without minting (and having to redistribute) a brand new epoch. Resending
    // the original seed is safe even after messages have already gone out under it: a
    // [ReceivingChain] built from that seed can independently fast-forward to any message number
    // via [messenger.common.e2ee.ReceivingChain.messageKeyFor], the same skipped-message support
    // the 1:1 ratchet already relies on.
    private var mySeedChainKey: ByteArray? = null

    private class ReceivedKey(val epoch: Int, val chain: ReceivingChain)
    private val memberReceiving = HashMap<String, ReceivedKey>()

    /** Starts a fresh sender-key epoch for this device's own outgoing messages and returns the message to distribute (1:1, pairwise-encrypted) to every current member, including ones already known — everyone's copy of "my" chain must move together. */
    fun rekeySelf(): GroupSenderKeyMessage {
        val chainKey = ByteArray(Aead.KEY_LENGTH).also { SecureRandom().nextBytes(it) }
        myEpoch++
        mySendingChain = SendingChain(chainKey)
        mySeedChainKey = chainKey
        return GroupSenderKeyMessage(groupId, myEpoch, chainKey)
    }

    /**
     * Re-derives the exact message [rekeySelf] most recently returned — for resending to a member
     * whose local copy of it went missing (e.g. their app restarted with no persisted group-session
     * state; groups deliberately have none, same as 1:1 ratchet sessions). Returns null if this
     * device has never sent anything to this group yet. See [messenger.common.client.MessengerClient]'s
     * `GROUP_KEY_REQUEST` handling for the only caller.
     */
    fun currentSenderKeyMessageOrNull(): GroupSenderKeyMessage? =
        mySeedChainKey?.let { GroupSenderKeyMessage(groupId, myEpoch, it) }

    /**
     * Records (or replaces) another member's sender key, e.g. after receiving their
     * [GroupSenderKeyMessage] over a pairwise session. A message for an epoch *older* than the one
     * already on file is ignored rather than overwriting it: pairwise delivery gives no ordering
     * guarantee (mailbox redelivery/reconnect races can bring an earlier rekey distribution after a
     * later one), and silently downgrading here would make this device unable to decrypt the
     * sender's current messages until they rekeyed again. Same or newer epoch still replaces it —
     * a same-epoch resend (e.g. answering a GROUP_KEY_REQUEST) is harmless to reapply.
     */
    fun receiveSenderKey(senderDhKey: ByteArray, message: GroupSenderKeyMessage) {
        require(message.groupId.contentEquals(groupId)) { "sender key is for a different group" }
        val senderHex = senderDhKey.toHex()
        val existing = memberReceiving[senderHex]
        if (existing != null && message.epoch < existing.epoch) return
        memberReceiving[senderHex] = ReceivedKey(message.epoch, ReceivingChain(message.chainKey))
    }

    /** Encrypts a group message under this device's own current sender-key chain — call [rekeySelf] first if none exists yet. */
    fun encrypt(plaintext: ByteArray): GroupCiphertextMessage {
        val chain = checkNotNull(mySendingChain) { "no sender key yet -- call rekeySelf() before sending to this group" }
        val (counter, key) = chain.next()
        val ciphertext = Aead.encrypt(key, Aead.ZERO_NONCE, associatedData(groupId, ownDhKey, myEpoch), plaintext)
        return GroupCiphertextMessage(groupId, ownDhKey, myEpoch, counter, ciphertext)
    }

    /**
     * Decrypts a group message from [senderDhKey], using whichever epoch's chain that sender
     * distributed to us. Throws [NoSuchElementException] if we never received a sender key from
     * them (e.g. we joined after their last rekey, or they aren't actually a member) — callers
     * should treat this the same as any other permanently-undecryptable message, not retry.
     */
    fun decrypt(senderDhKey: ByteArray, message: GroupCiphertextMessage): ByteArray {
        require(message.groupId.contentEquals(groupId)) { "ciphertext is for a different group" }
        require(message.senderDhKey.contentEquals(senderDhKey)) { "ciphertext sender doesn't match claimed sender" }
        val received = memberReceiving[senderDhKey.toHex()]
            ?: throw NoSuchElementException("no sender key on file for this member")
        require(message.epoch == received.epoch) {
            "sender key epoch mismatch (have ${received.epoch}, message is epoch ${message.epoch}) -- sender must have rekeyed"
        }
        val key = received.chain.messageKeyFor(message.counter)
        return Aead.decrypt(key, Aead.ZERO_NONCE, associatedData(groupId, senderDhKey, message.epoch), message.ciphertext)
    }

    companion object {
        /** Binds each group ciphertext to exactly this group, this sender, and this epoch, so it can never be replayed as if it came from a different group/sender/epoch. */
        private fun associatedData(groupId: ByteArray, senderDhKey: ByteArray, epoch: Int): ByteArray {
            val buffer = java.nio.ByteBuffer.allocate(groupId.size + senderDhKey.size + 4)
            buffer.put(groupId)
            buffer.put(senderDhKey)
            buffer.putInt(epoch)
            return buffer.array()
        }
    }
}
