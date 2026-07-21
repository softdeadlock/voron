package messenger.common.e2ee

import java.util.concurrent.ConcurrentHashMap
import messenger.common.util.toHex

/**
 * Owns all 1:1 sessions for one device: decides whether an outgoing message
 * starts a new session (X3DH from a fetched prekey bundle) or continues an
 * existing one, and builds the responder session the first time a peer
 * opens one against us.
 *
 * Sessions are keyed by the peer's DH identity key, which is also its relay
 * routing address — so the transport layer's "who sent this" answers
 * "which session" directly.
 */
class E2eeManager(
    private val identity: DeviceIdentity,
    private val preKeyStore: PreKeyStore,
) {
    private val sessions = ConcurrentHashMap<String, E2eeSession>()

    // Tracks, per peer, the initiatorEphemeralKey of the Initial message that most recently
    // (re)built our *responder* side of the session — see the replay check in [decrypt]. Absent
    // for sessions we ourselves opened as initiator (encrypt() never writes here), which is
    // exactly the signal that lets a peer-initiated Initial correctly rebuild in that case.
    private val responderEphemeralKeys = ConcurrentHashMap<String, ByteArray>()

    // TOFU pin of each peer's signingIdentityKey, set the first time we ever build an initiator
    // session with them (see [encrypt]) — see X3dhSigningKeySpoofExploitTest for why this can't be
    // left to bundle.dhIdentityKey pinning alone.
    private val pinnedSigningIdentityKeys = ConcurrentHashMap<String, ByteArray>()

    // Every initiatorEphemeralKey (hex) we've ever accepted to build a responder session with a
    // given peer this process lifetime. Guards against an Initial-message ROLLBACK/REPLAY: X3DH
    // gives the Initial no replay protection, and a pure-3-DH Initial (no one-time prekey, a
    // supported path) re-derives the exact same root key every time as long as our signed prekey is
    // still resolvable (~3 weeks). Without this, a malicious relay could capture an old pure-3-DH
    // Initial plus the Normal messages that followed it and, once our current session's ratchet had
    // moved past that ephemeral, replay the Initial to force our session back to message #0 and then
    // replay those Normals so they re-decrypt and surface as brand new messages — a message
    // re-injection / desync attack beyond the baseline "a relay can drop messages". A genuinely new
    // handshake (peer reinstalled) always uses a fresh ephemeral not in this set, so it's unaffected.
    // Bounded per peer so a relay spamming distinct ephemerals can't grow it without limit.
    private val acceptedInitiatorEphemerals = ConcurrentHashMap<String, MutableSet<String>>()

    fun hasSession(peerDhIdentityKey: ByteArray): Boolean =
        sessions.containsKey(peerDhIdentityKey.toHex())

    /** The TOFU-pinned signing identity key for this peer, if we've ever established one (as initiator, or via [pinSigningIdentityKey]) — null if we've never had a reason to learn it. */
    fun pinnedSigningIdentityKey(peerDhIdentityKey: ByteArray): ByteArray? =
        pinnedSigningIdentityKeys[peerDhIdentityKey.toHex()]

    /**
     * Pins [signingIdentityKey] for [peerDhIdentityKey] if nothing is pinned yet, same TOFU rule
     * [encrypt] applies. Returns false (and pins nothing) if a *different* key is already pinned —
     * the caller should treat that as a spoofing attempt, exactly like [UnexpectedSigningIdentity].
     */
    fun pinSigningIdentityKey(peerDhIdentityKey: ByteArray, signingIdentityKey: ByteArray): Boolean {
        val peerHex = peerDhIdentityKey.toHex()
        val existing = pinnedSigningIdentityKeys.putIfAbsent(peerHex, signingIdentityKey)
        return existing == null || existing.contentEquals(signingIdentityKey)
    }

    /** Discards any session with this peer, so the next [encrypt] call starts a fresh X3DH handshake. */
    fun dropSession(peerDhIdentityKey: ByteArray) {
        val peerHex = peerDhIdentityKey.toHex()
        sessions.remove(peerHex)
        responderEphemeralKeys.remove(peerHex)
    }

    /**
     * Encrypts [plaintext] to a peer. If there is no session yet, [bundle]
     * (the peer's fetched prekey bundle) is required to open one and the
     * result is an [E2eeMessage.Initial]; otherwise it is an
     * [E2eeMessage.Normal].
     */
    fun encrypt(peerDhIdentityKey: ByteArray, bundle: PreKeyBundle?, plaintext: ByteArray): E2eeMessage {
        val peerHex = peerDhIdentityKey.toHex()
        sessions[peerHex]?.let { return E2eeMessage.Normal(it.encrypt(plaintext)) }

        requireNotNull(bundle) { "no session with $peerHex and no prekey bundle to start one" }
        val init = X3dhLite.initiate(
            identity.dhIdentity,
            peerDhIdentityKey,
            bundle,
            expectedPeerSigningIdentityKey = pinnedSigningIdentityKeys[peerHex],
            initiatorSigningIdentityKey = identity.signingIdentityPublicKey,
        )
        pinnedSigningIdentityKeys.putIfAbsent(peerHex, init.usedSigningIdentityKey)
        val session = E2eeSession.forInitiator(init.rootKey, bundle.signedPreKey, init.associatedData)
        sessions[peerHex] = session
        return E2eeMessage.Initial(
            initiatorDhIdentityKey = identity.dhIdentityPublicKey,
            initiatorSigningIdentityKey = identity.signingIdentityPublicKey,
            initiatorEphemeralKey = init.ephemeralPublicKey,
            signedPreKeyId = init.usedSignedPreKeyId,
            oneTimePreKeyId = init.usedOneTimePreKeyId,
            payload = session.encrypt(plaintext),
        )
    }

    /** Decrypts a message from [peerDhIdentityKey], establishing the responder session on first contact. */
    fun decrypt(peerDhIdentityKey: ByteArray, message: E2eeMessage): ByteArray {
        val peerHex = peerDhIdentityKey.toHex()
        return when (message) {
            is E2eeMessage.Initial -> {
                // message.initiatorDhIdentityKey is self-declared by the sender inside the E2EE
                // payload, independent of peerDhIdentityKey (the routing-layer/relay-authenticated
                // sender). An honest client's declared key always matches its real one, and X3DH's
                // own DH binding means a mismatched declaration can't produce a payload that
                // decrypts successfully anyway — but that's a subtle argument to lean on silently.
                // Enforcing it explicitly here means the session map's key and the session's own
                // X3DH identity binding can never disagree, by construction, regardless of how the
                // rest of this class evolves later.
                require(message.initiatorDhIdentityKey.contentEquals(peerDhIdentityKey)) {
                    "E2EE Initial message declares a different identity ($peerHex expected) than its routing envelope"
                }

                val existingSession = sessions[peerHex]
                val existingEphemeral = responderEphemeralKeys[peerHex]
                if (existingSession != null && existingEphemeral != null && existingEphemeral.contentEquals(message.initiatorEphemeralKey)) {
                    // SECURITY: same handshake as our current responder session (identical
                    // initiatorEphemeralKey), most likely a duplicate delivery/replay of the very
                    // first message rather than a genuinely new one — X3DH itself gives no replay
                    // protection to the Initial message, and when no one-time prekey was available
                    // (pure 3-DH, a supported/expected path — see PreKeyBundle), *rebuilding* the
                    // session here would re-derive the exact same rootKey and therefore the exact
                    // same message-#0 key that already encrypted a real message. Decrypting against
                    // the *existing* session instead routes through its receiving chain's own
                    // replay guard (`require(number >= counter)` in SymmetricRatchet), which
                    // correctly rejects an already-consumed message number instead of silently
                    // reusing a ratchet key under Aead's fixed zero nonce — reuse there is exactly
                    // the two-time-pad failure the whole "every key used exactly once" design
                    // depends on never happening.
                    existingSession.decrypt(message.payload)
                } else {
                    // SECURITY: reject a replay of an Initial whose ephemeral we already consumed for
                    // this peer but which isn't our current one — that's precisely the rollback attack
                    // acceptedInitiatorEphemerals guards against (a relay replaying an old pure-3-DH
                    // Initial to reset our ratchet so previously-seen messages re-inject as new). A
                    // genuine re-handshake always brings a fresh, never-seen ephemeral, so this only
                    // ever fires on a real replay.
                    val ephemeralHex = message.initiatorEphemeralKey.toHex()
                    val seen = acceptedInitiatorEphemerals[peerHex]
                    if (seen != null && seen.contains(ephemeralHex)) {
                        throw ReplayedInitialException(peerHex)
                    }

                    // A fresh X3DH-initial message with a *different* ephemeral key from a peer we
                    // already have a session with means their side lost its session state (e.g.
                    // reinstalled) and is starting over — our old session is now stale, so replace
                    // it rather than keep decrypting against dead ratchet state. Only commit the
                    // replacement once decrypt actually succeeds, so a malformed Initial can't
                    // clobber a working session with a broken one.
                    val session = responderSessionFor(message)
                    val plaintext = session.decrypt(message.payload)
                    // Only now, with the AEAD tag having actually verified, is it safe to burn the
                    // one-time prekey — responderSessionFor only *peeked* it. Committing this any
                    // earlier would let a forged Initial with garbage ciphertext permanently burn a
                    // real one-time prekey before the genuine handshake that needs it arrives.
                    preKeyStore.consumeOneTimePreKey(message.oneTimePreKeyId)
                    sessions[peerHex] = session
                    responderEphemeralKeys[peerHex] = message.initiatorEphemeralKey
                    rememberAcceptedEphemeral(peerHex, ephemeralHex)
                    plaintext
                }
            }
            is E2eeMessage.Normal -> {
                val session = sessions[peerHex] ?: throw NoSessionException(peerHex)
                session.decrypt(message.payload)
            }
        }
    }

    /** Thrown only when we hold no session at all for [peerHex] — see MessengerClient's catch. */
    class NoSessionException(peerHex: String) : Exception("no session with $peerHex for a normal message")

    /** A previously-consumed Initial ephemeral was replayed for [peerHex] — a session-rollback attempt, dropped like any other undecryptable message (never triggers a session reset). */
    class ReplayedInitialException(peerHex: String) : Exception("replayed Initial ephemeral for $peerHex (session rollback attempt)")

    private fun rememberAcceptedEphemeral(peerHex: String, ephemeralHex: String) {
        val set = acceptedInitiatorEphemerals.getOrPut(peerHex) { java.util.Collections.synchronizedSet(LinkedHashSet()) }
        synchronized(set) {
            set.add(ephemeralHex)
            // Bound per peer: legitimate re-handshakes are rare, so this only grows meaningfully
            // under a relay deliberately spamming distinct ephemerals — evict oldest past the cap.
            val iterator = set.iterator()
            while (set.size > MAX_ACCEPTED_EPHEMERALS_PER_PEER && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun responderSessionFor(message: E2eeMessage.Initial): E2eeSession {
        val peerHex = message.initiatorDhIdentityKey.toHex()
        // Same TOFU pin as the initiator side's expectedPeerSigningIdentityKey check (X3dhLite),
        // just mirrored: whoever opens a session with *us* first also gets their signing key
        // pinned, so a relay can't later swap in its own on a fresh Initial for a peer we already
        // know, even though nothing here required a signature over this field (it only becomes
        // load-bearing via the AD, not an independent check) -- pinning it explicitly means a
        // mismatch is caught before ever deriving a session at all, not just silently producing an
        // AD/root key the peer's own side won't agree on.
        val pinned = pinnedSigningIdentityKeys[peerHex]
        if (pinned != null && !pinned.contentEquals(message.initiatorSigningIdentityKey)) {
            throw UnexpectedSigningIdentity(pinned.toHex(), message.initiatorSigningIdentityKey.toHex())
        }
        val signedPreKey = preKeyStore.signedPreKeyFor(message.signedPreKeyId)
            ?: throw IllegalStateException("unknown signed prekey id ${message.signedPreKeyId}")
        // Peeked, not consumed — see consumeOneTimePreKey call at this function's call site.
        val oneTimePreKey = preKeyStore.peekOneTimePreKey(message.oneTimePreKeyId)
        val result = X3dhLite.respond(
            responderDhIdentity = identity.dhIdentity,
            responderSigningIdentityKey = identity.signingIdentityPublicKey,
            signedPreKey = signedPreKey,
            oneTimePreKey = oneTimePreKey,
            initiatorDhIdentityKey = message.initiatorDhIdentityKey,
            initiatorSigningIdentityKey = message.initiatorSigningIdentityKey,
            initiatorEphemeralKey = message.initiatorEphemeralKey,
        )
        pinnedSigningIdentityKeys.putIfAbsent(peerHex, message.initiatorSigningIdentityKey)
        return E2eeSession.forResponder(result.rootKey, signedPreKey, result.associatedData)
    }

    private companion object {
        // SECURITY: an entry only ever gets added *after* its Initial's AEAD tag has actually
        // verified (see rememberAcceptedEphemeral's call site) -- a relay can't manufacture new
        // entries by spamming forged Initials with fresh ephemerals, since it holds none of the
        // private key material needed to make one of those actually decrypt. The only way to evict
        // a specific old entry is to have accumulated that many *genuine* successful re-handshakes
        // with the same peer first (reinstalls, device resets, session-reset flows) -- implausible
        // at 64, but raised well past that anyway since the actual cost (a handful of hex strings
        // per peer) is negligible, closing the eviction-then-replay-old-Initial rollback path even
        // over a very long-lived, heavily-reset relationship.
        const val MAX_ACCEPTED_EPHEMERALS_PER_PEER = 4096
    }
}
