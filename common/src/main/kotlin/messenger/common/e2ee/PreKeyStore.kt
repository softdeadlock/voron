package messenger.common.e2ee

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import messenger.common.crypto.Ed25519Signatures
import messenger.common.crypto.X25519
import messenger.common.transport.NoiseStaticKeyPair

/**
 * Holds a device's own PRIVATE prekeys so it can complete an incoming
 * session that some peer opened against one of its published public
 * prekeys. This lives only on the owning device.
 *
 * The signed prekey rotates on a weekly schedule (like Signal) instead of staying fixed for the
 * process's whole lifetime, and is persisted through [loadPersisted]/[persist] — supplied by the
 * caller in the same shape as [DeviceIdentity.loadOrCreate]'s byte-array variant — so a restart
 * reuses the same key/id instead of silently minting a new keypair under the same id, which used
 * to desync any X3DH handshake a peer started against the since-replaced key (previously only
 * papered over after the fact via [messenger.common.protocol.TransportFrame.SESSION_RESET_NOTICE]).
 * The just-rotated-away-from signed prekey stays usable for [GRACE_PERIOD_MILLIS] after rotation
 * so a handshake started just before doesn't break.
 *
 * One-time prekeys are consumed on use and never reissued — that is what makes them one-time —
 * and are persisted the same way, so a private one-time key whose public half a peer already
 * fetched survives this device restarting before that peer's first message arrives.
 */
class PreKeyStore(
    private val identity: DeviceIdentity,
    private val loadPersisted: () -> ByteArray? = { null },
    private val persist: (ByteArray) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class SignedPreKey(val id: Int, val keyPair: NoiseStaticKeyPair, val signature: ByteArray, val createdAtMillis: Long)

    private class PersistedState(
        val current: SignedPreKey,
        val previous: SignedPreKey?,
        val nextOneTimeId: Int,
        val oneTimePreKeys: Map<Int, NoiseStaticKeyPair>,
    )

    @Volatile private var current: SignedPreKey
    @Volatile private var previous: SignedPreKey?

    private val nextOneTimeId = AtomicInteger()
    private val oneTimePreKeys = ConcurrentHashMap<Int, NoiseStaticKeyPair>()

    // Every mutation (rotation, publish/regenerate, consume) re-persists the whole state
    // immediately rather than batching. The state is small (one SPK, at most one grace-period
    // SPK, a few dozen one-time keys) and correctness — never losing a consumed OTP's "it's gone"
    // fact to a crash, which would let it be reused and quietly break forward secrecy — matters
    // far more here than write amplification. Guards persistState()/rotateIfDue() against
    // interleaved writes to the backing store; reentrant so rotateIfDue() can call persistState()
    // while already holding it.
    private val persistLock = Any()

    init {
        val loaded = loadPersisted()?.let { runCatching { decodeState(it) }.getOrNull() }
        val rotated = rotate(loaded)
        current = rotated.current
        previous = rotated.previous
        nextOneTimeId.set(rotated.nextOneTimeId)
        oneTimePreKeys.putAll(rotated.oneTimePreKeys)
        persistState()
    }

    private fun rotate(loaded: PersistedState?): PersistedState {
        if (loaded == null) return PersistedState(generateSignedPreKey(id = 1), null, 1, emptyMap())
        val currentAge = now() - loaded.current.createdAtMillis
        if (currentAge < ROTATION_INTERVAL_MILLIS) {
            val stillInGrace = loaded.previous?.takeIf { now() - it.createdAtMillis < ROTATION_INTERVAL_MILLIS + GRACE_PERIOD_MILLIS }
            return PersistedState(loaded.current, stillInGrace, loaded.nextOneTimeId, loaded.oneTimePreKeys)
        }
        // Due for rotation: the old current becomes the grace-period previous, replacing whatever
        // previous existed before it (only one old key is kept, per design) — unless it's already
        // past its own grace-period cutoff too, e.g. the process was offline for a long time.
        val demoted = loaded.current.takeIf { currentAge < ROTATION_INTERVAL_MILLIS + GRACE_PERIOD_MILLIS }
        return PersistedState(generateSignedPreKey(id = loaded.current.id + 1), demoted, loaded.nextOneTimeId, loaded.oneTimePreKeys)
    }

    private fun generateSignedPreKey(id: Int): SignedPreKey {
        val keyPair = X25519.generateKeyPair()
        val signature = Ed25519Signatures.sign(identity.signingIdentity.privateKey, keyPair.publicKey)
        return SignedPreKey(id, keyPair, signature, now())
    }

    /** Generates [count] fresh one-time prekeys and returns them for publishing. */
    fun generateOneTimePreKeys(count: Int): List<PublishedOneTimePreKey> {
        val published = ArrayList<PublishedOneTimePreKey>(count)
        repeat(count) {
            val id = nextOneTimeId.getAndIncrement()
            val kp = X25519.generateKeyPair()
            oneTimePreKeys[id] = kp
            published += PublishedOneTimePreKey(id, kp.publicKey)
        }
        persistState()
        return published
    }

    /**
     * Republishing (e.g. on every relay reconnect) fully replaces the previous batch server-side
     * — [messenger.server.routing.PreKeyDirectory.publish] overwrites rather than merges. That's
     * fine for one-time prekeys nobody ever fetched (they're simply unreachable from the relay
     * from now on, no loss). It is NOT fine for one-time prekeys a peer already fetched via
     * FETCH_PREKEYS shortly before this republish fires: their message using that id may still be
     * in flight or sitting mailboxed on the relay. This used to unconditionally `clear()` the
     * whole local map here, on every reconnect — not just app restarts — which could delete the
     * private half of an already-fetched, not-yet-consumed one-time prekey out from under a
     * message that hadn't arrived yet. Once that happened, the message could never decrypt: the
     * sender's root key derivation included a real 4th DH term from that one-time prekey, but ours
     * would fall back to a plain 3-DH with it gone, permanently disagreeing on the root key — see
     * "messages sent while the recipient was briefly offline never arrive even after reconnecting".
     * [evictOldestUnfetchedBeyondCapacity] bounds growth instead, by age, without deleting anything
     * that might still be needed.
     *
     * Also the natural place to re-check signed-prekey rotation: this fires on every reconnect
     * (see callers), so a process that stays alive and connected past [ROTATION_INTERVAL_MILLIS]
     * still rotates over time, not just once at construction.
     */
    fun publishedPreKeys(oneTimeCount: Int): PublishedPreKeys {
        rotateIfDue()
        evictOldestUnfetchedBeyondCapacity()
        return PublishedPreKeys(
            dhIdentityKey = identity.dhIdentityPublicKey,
            signingIdentityKey = identity.signingIdentityPublicKey,
            signedPreKeyId = current.id,
            signedPreKey = current.keyPair.publicKey,
            signedPreKeySignature = current.signature,
            oneTimePreKeys = generateOneTimePreKeys(oneTimeCount),
        )
    }

    /**
     * Keeps [oneTimePreKeys] from growing without bound across a long-lived, frequently-reconnecting
     * process, by discarding the *oldest* entries (lowest ids, since ids are assigned in strictly
     * increasing order) once there are more than [MAX_RETAINED_ONE_TIME_PREKEYS] of them. A real
     * conversation only ever has a handful of handshakes in flight at once, so anything this old was
     * either never fetched (safe to drop — see [publishedPreKeys]) or fetched so long ago that the
     * peer's message would already have arrived or been considered lost by other means.
     */
    private fun evictOldestUnfetchedBeyondCapacity() {
        val excess = oneTimePreKeys.size - MAX_RETAINED_ONE_TIME_PREKEYS
        if (excess <= 0) return
        oneTimePreKeys.keys.sorted().take(excess).forEach { oneTimePreKeys.remove(it) }
    }

    private fun rotateIfDue() {
        if (now() - current.createdAtMillis < ROTATION_INTERVAL_MILLIS) return
        synchronized(persistLock) {
            if (now() - current.createdAtMillis < ROTATION_INTERVAL_MILLIS) return
            previous = current
            current = generateSignedPreKey(id = current.id + 1)
            persistState()
        }
    }

    fun signedPreKeyFor(id: Int): NoiseStaticKeyPair? {
        if (id == current.id) return current.keyPair
        if (id == previous?.id) return previous?.keyPair
        return null
    }

    /**
     * Looks up the private one-time prekey with [id] *without* removing it, or null if unknown/
     * already used. Prekey ids are relay-visible directory data (PUBLISH_PREKEYS/FETCH_PREKEYS are
     * never E2E-wrapped), so a malicious relay can learn exactly which id a real handshake is about
     * to use and race a forged Initial message naming the same id before the real one arrives. If
     * that forgery were allowed to remove the prekey on its own, it would permanently break the real
     * handshake with zero cryptographic capability beyond running the relay — see
     * security-audit/ADVERSARIAL_REVIEW_PAVEL.md finding #1. Callers must use [consumeOneTimePreKey]
     * to actually remove it, and only after the payload decrypted with it has verified.
     */
    fun peekOneTimePreKey(id: Int): NoiseStaticKeyPair? {
        if (id == PreKeyBundle.NO_ONE_TIME_PREKEY) return null
        return oneTimePreKeys[id]
    }

    /** Removes and returns the private one-time prekey with [id], or null if already used — call only once the payload decrypted under it has actually verified (see [peekOneTimePreKey]). */
    fun consumeOneTimePreKey(id: Int): NoiseStaticKeyPair? {
        if (id == PreKeyBundle.NO_ONE_TIME_PREKEY) return null
        val removed = oneTimePreKeys.remove(id) ?: return null
        persistState()
        return removed
    }

    private fun persistState() = synchronized(persistLock) {
        persist(encodeState())
    }

    private fun encodeState(): ByteArray {
        val previousLocal = previous
        val otps = oneTimePreKeys.entries.toList()
        val buffer = ByteBuffer.allocate(
            1 + SIGNED_PREKEY_LENGTH + 1 + (if (previousLocal != null) SIGNED_PREKEY_LENGTH else 0) +
                4 + 4 + otps.size * ONE_TIME_PREKEY_LENGTH,
        )
        buffer.put(FORMAT_VERSION)
        encodeSignedPreKey(buffer, current)
        buffer.put(if (previousLocal != null) 1 else 0)
        if (previousLocal != null) encodeSignedPreKey(buffer, previousLocal)
        buffer.putInt(nextOneTimeId.get())
        buffer.putInt(otps.size)
        for ((id, keyPair) in otps) {
            buffer.putInt(id)
            buffer.put(keyPair.privateKey)
            buffer.put(keyPair.publicKey)
        }
        return buffer.array()
    }

    private fun encodeSignedPreKey(buffer: ByteBuffer, spk: SignedPreKey) {
        buffer.putInt(spk.id)
        buffer.putLong(spk.createdAtMillis)
        buffer.put(spk.keyPair.privateKey)
        buffer.put(spk.keyPair.publicKey)
        buffer.put(spk.signature)
    }

    private fun decodeState(bytes: ByteArray): PersistedState {
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.get() == FORMAT_VERSION) { "unsupported persisted prekey format" }
        val decodedCurrent = decodeSignedPreKey(buffer)
        val hasPrevious = buffer.get().toInt() != 0
        val decodedPrevious = if (hasPrevious) decodeSignedPreKey(buffer) else null
        val nextOneTimeId = buffer.int
        val otpCount = buffer.int
        // Locally-generated data, not wire data from a peer — but a corrupt/truncated file
        // shouldn't be able to make `HashMap(otpCount)` allocate something absurd either.
        require(otpCount in 0..MAX_PERSISTED_ONE_TIME_PREKEYS) { "corrupt persisted prekey state: otp count $otpCount" }
        val otps = HashMap<Int, NoiseStaticKeyPair>(otpCount)
        repeat(otpCount) {
            val id = buffer.int
            val privateKey = ByteArray(32).also { buffer.get(it) }
            val publicKey = ByteArray(32).also { buffer.get(it) }
            otps[id] = NoiseStaticKeyPair(privateKey, publicKey)
        }
        return PersistedState(decodedCurrent, decodedPrevious, nextOneTimeId, otps)
    }

    private fun decodeSignedPreKey(buffer: ByteBuffer): SignedPreKey {
        val id = buffer.int
        val createdAtMillis = buffer.long
        val privateKey = ByteArray(32).also { buffer.get(it) }
        val publicKey = ByteArray(32).also { buffer.get(it) }
        val signature = ByteArray(Ed25519Signatures.SIGNATURE_LENGTH).also { buffer.get(it) }
        return SignedPreKey(id, NoiseStaticKeyPair(privateKey, publicKey), signature, createdAtMillis)
    }

    companion object {
        const val ROTATION_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val GRACE_PERIOD_MILLIS = 14L * 24 * 60 * 60 * 1000
        private const val MAX_RETAINED_ONE_TIME_PREKEYS = 500
        private const val FORMAT_VERSION: Byte = 1
        private const val SIGNED_PREKEY_LENGTH = 4 + 8 + 32 + 32 + Ed25519Signatures.SIGNATURE_LENGTH
        private const val ONE_TIME_PREKEY_LENGTH = 4 + 32 + 32
        private const val MAX_PERSISTED_ONE_TIME_PREKEYS = 10_000
    }
}

class PublishedOneTimePreKey(val id: Int, val publicKey: ByteArray)

/** Everything a device publishes to the relay's prekey directory. */
class PublishedPreKeys(
    val dhIdentityKey: ByteArray,
    val signingIdentityKey: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val oneTimePreKeys: List<PublishedOneTimePreKey>,
)
