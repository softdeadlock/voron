package messenger.common.onion

import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import messenger.common.crypto.Aead
import messenger.common.crypto.Hkdf
import messenger.common.crypto.X25519

/** One onion hop's WS endpoint and current static key — fetched fresh at connect time from its `/v1/onion-info`, the same not-yet-pinned TOFU story the relay's own key already has. */
data class OnionNodeInfo(val wsUrl: String, val staticPublicKey: ByteArray)

private const val INFO_CLIENT_TO_EXIT = "voron-onion-client-to-exit"
private const val INFO_EXIT_TO_CLIENT = "voron-onion-exit-to-client"
private val EMPTY_AAD = ByteArray(0)

/** Real-length header size prepended before padding — see [pad]/[unpad]. */
private const val LENGTH_HEADER_SIZE = 4

/**
 * Fixed size buckets a padded frame is rounded up to before entering the circuit — chosen around
 * Voron's actual traffic shapes rather than arbitrary powers of two: control signals (acks,
 * typing, reactions) are tens of bytes; text messages and encoded link-preview text run up to a
 * few KiB; file/voice chunks are always exactly [messenger.common.e2ee.FileSignal.CHUNK_SIZE]
 * (16 KiB) plus a small constant envelope/AEAD overhead. Anything bigger than the largest bucket
 * (rare — nothing in the app currently sends single frames that large) is passed through with only
 * its own real length rounded up to a 4 KiB step, never fully unpadded, so it still doesn't leak
 * its exact byte count.
 */
private val PADDING_BUCKETS = intArrayOf(256, 1024, 4096, 16 * 1024 + 1024, 64 * 1024, 256 * 1024)
private const val OVERFLOW_STEP = 4096

/** Pads [plaintext] up to a fixed bucket boundary so a passive observer watching only frame sizes on the wire can't correlate this frame across hops by its exact byte count — see [PADDING_BUCKETS]. */
private fun pad(plaintext: ByteArray): ByteArray {
    val needed = LENGTH_HEADER_SIZE + plaintext.size
    val bucket = PADDING_BUCKETS.firstOrNull { it >= needed }
        ?: (((needed + OVERFLOW_STEP - 1) / OVERFLOW_STEP) * OVERFLOW_STEP)
    val out = ByteArray(bucket)
    ByteBuffer.wrap(out).putInt(plaintext.size)
    System.arraycopy(plaintext, 0, out, LENGTH_HEADER_SIZE, plaintext.size)
    return out
}

/** Reverses [pad] — the real length is always stored in the first 4 bytes regardless of which bucket (or overflow step) was used, so no separate "was this padded" flag is needed. */
private fun unpad(padded: ByteArray): ByteArray {
    val realLength = ByteBuffer.wrap(padded, 0, LENGTH_HEADER_SIZE).int
    return padded.copyOfRange(LENGTH_HEADER_SIZE, LENGTH_HEADER_SIZE + realLength)
}

/**
 * A fixed-length circuit (one or more forwarding-only hops, ordered entry-first) that the client's
 * real relay connection is tunneled through instead of connecting to the relay directly — see
 * [messenger.server.routing.configureOnionNode] for what each hop does with the layers this
 * produces. The exit is always the real relay itself, unmodified: once the last hop peels its
 * layer, what's left is exactly the plaintext Noise/transport bytes the client would have sent it
 * directly, so nothing past this circuit needs to know onion routing exists.
 *
 * [encodeOutgoing]/[decodeIncoming] are meant to be passed straight to
 * [messenger.common.client.MessengerClient.connect] — every frame it would otherwise send/receive
 * unwrapped gets wrapped/peeled by this circuit instead, entirely underneath the Noise/E2EE layers
 * which stay exactly as they are. Every frame is also padded to a fixed size bucket (see [pad]) so
 * frame *sizes* don't survive the trip either — closing the size-correlation gap a passive
 * observer at both ends of an unpadded 2-hop circuit could otherwise exploit (see
 * `OnionTrafficCorrelationExploit`/`correlate_onion_traffic.py` in the security-audit tooling).
 */
class OnionCircuit private constructor(
    val entryWsUrl: String,
    private val hopEncryptKeys: List<ByteArray>,
    private val hopDecryptKeys: List<ByteArray>,
) {
    private val sendCounters = Array(hopEncryptKeys.size) { AtomicLong(0) }
    private val recvCounters = Array(hopDecryptKeys.size) { AtomicLong(0) }

    /** Pads then wraps a frame bound for the real relay in every onion layer, innermost (last hop) first — the entry hop's layer, applied last, is the only one it can peel on arrival. */
    fun encodeOutgoing(plaintext: ByteArray): ByteArray {
        var layer = pad(plaintext)
        for (i in hopEncryptKeys.indices.reversed()) {
            layer = Aead.encrypt(hopEncryptKeys[i], Aead.counterNonce(sendCounters[i].getAndIncrement()), EMPTY_AAD, layer)
        }
        return layer
    }

    /** Peels every onion layer off a frame that came back from the real relay, entry-hop-first, then strips the padding. */
    fun decodeIncoming(ciphertext: ByteArray): ByteArray {
        var layer = ciphertext
        for (i in hopDecryptKeys.indices) {
            layer = Aead.decrypt(hopDecryptKeys[i], Aead.counterNonce(recvCounters[i].getAndIncrement()), EMPTY_AAD, layer)
        }
        return unpad(layer)
    }

    companion object {
        /** Picks a fresh ephemeral key for this one circuit only — never reused, so no per-node identity leaks across separate connections. [hops] must be ordered entry-first; the last hop is the one that talks to the real relay. */
        fun build(hops: List<OnionNodeInfo>): OnionCircuit {
            require(hops.isNotEmpty()) { "at least one onion hop required" }
            val ephemeral = X25519.generateKeyPair()
            val encryptKeys = ArrayList<ByteArray>(hops.size)
            val decryptKeys = ArrayList<ByteArray>(hops.size)
            for (hop in hops) {
                val shared = X25519.dh(ephemeral.privateKey, hop.staticPublicKey)
                encryptKeys += Hkdf.derive(shared, INFO_CLIENT_TO_EXIT, Aead.KEY_LENGTH)
                decryptKeys += Hkdf.derive(shared, INFO_EXIT_TO_CLIENT, Aead.KEY_LENGTH)
            }
            val ekParam = Base64.getEncoder().encodeToString(ephemeral.publicKey)

            return OnionCircuit(
                entryWsUrl = "${hops.first().wsUrl}?ek=$ekParam",
                hopEncryptKeys = encryptKeys,
                hopDecryptKeys = decryptKeys,
            )
        }

        /** Convenience for the common 2-hop (guard, middle) case — equivalent to `build(listOf(guard, middle))`. */
        fun build(guard: OnionNodeInfo, middle: OnionNodeInfo): OnionCircuit = build(listOf(guard, middle))
    }
}
