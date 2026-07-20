package messenger.common.e2ee

import messenger.common.crypto.Hkdf

/**
 * The symmetric (hash) ratchet that runs within one DH-ratchet epoch. Each direction of the
 * conversation has its own chain; every message advances the chain and the previous chain key is
 * discarded, so compromising the current key does not expose earlier messages (forward secrecy).
 *
 * This is the symmetric half of the Double Ratchet — the DH-ratchet half (which adds
 * post-compromise security: a chain-key compromise doesn't expose *future* messages either, once
 * the next DH step mixes in fresh randomness) lives in [E2eeSession], which starts a fresh
 * [SendingChain]/[ReceivingChain] pair on every ratchet step.
 */
private val MESSAGE_KEY_SEED = byteArrayOf(0x01)
private val CHAIN_KEY_SEED = byteArrayOf(0x02)

private fun messageKey(chainKey: ByteArray) = Hkdf.hmac(chainKey, MESSAGE_KEY_SEED)
private fun advance(chainKey: ByteArray) = Hkdf.hmac(chainKey, CHAIN_KEY_SEED)

class SendingChain(private var chainKey: ByteArray) {
    var counter = 0
        private set

    /** Returns the message number and the one-time key to encrypt it with. */
    fun next(): Pair<Int, ByteArray> {
        val key = messageKey(chainKey)
        chainKey = advance(chainKey)
        return counter++ to key
    }
}

class ReceivingChain(private var chainKey: ByteArray, private val maxSkip: Int = 256) {
    private var counter = 0
    private val skipped = HashMap<Int, ByteArray>()

    /**
     * An independent copy sharing none of this chain's mutable state — used to compute a
     * tentative [retireSkippingTo] result without advancing the real chain, in case the caller
     * ultimately doesn't commit to it (see [E2eeSession.decrypt]).
     */
    fun copy(): ReceivingChain = ReceivingChain(chainKey.copyOf(), maxSkip).also {
        it.counter = counter
        it.skipped.putAll(skipped)
    }

    /**
     * Returns the message key for message [number], deriving and caching any
     * skipped keys before it so out-of-order and dropped messages still
     * decrypt. Throws if a key was already consumed or too many are skipped.
     */
    fun messageKeyFor(number: Int): ByteArray {
        skipped.remove(number)?.let { return it }
        require(number >= counter) { "message key for #$number already consumed" }
        require(number - counter <= maxSkip) { "too many skipped messages (${number - counter})" }
        while (counter < number) {
            skipped[counter] = messageKey(chainKey)
            chainKey = advance(chainKey)
            counter++
        }
        val key = messageKey(chainKey)
        chainKey = advance(chainKey)
        counter++
        return key
    }

    /**
     * Called right before this chain is retired by a DH-ratchet step: derives and returns every
     * not-yet-consumed message key up to (but not including) [targetCount] — the sender's reported
     * length for this chain (the Double Ratchet spec's "PN") — so a message still in flight on the
     * *old* chain can still be decrypted after the peer has already moved on to a new one. Bounded
     * by the same [maxSkip] as ordinary in-chain skipping, for the same reason: an unbounded
     * "skip to N" from a malicious/corrupt header would otherwise let a peer force unbounded key
     * derivation and memory growth.
     */
    fun retireSkippingTo(targetCount: Int): Map<Int, ByteArray> {
        require(targetCount - counter <= maxSkip) { "too many messages to skip on chain retirement (${targetCount - counter})" }
        val out = HashMap<Int, ByteArray>(skipped)
        while (counter < targetCount) {
            out[counter] = messageKey(chainKey)
            chainKey = advance(chainKey)
            counter++
        }
        return out
    }
}
