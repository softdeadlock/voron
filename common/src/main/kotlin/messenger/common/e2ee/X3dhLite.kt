package messenger.common.e2ee

import messenger.common.crypto.Ed25519Signatures
import messenger.common.crypto.Hkdf
import messenger.common.crypto.X25519
import messenger.common.transport.NoiseStaticKeyPair
import messenger.common.util.toHex

/**
 * X3DH-style asynchronous key agreement, reduced to what this messenger
 * needs and built entirely on audited primitives (Curve25519 DH from
 * noise-java, HKDF-SHA256, Ed25519 for prekey signatures).
 *
 * WARNING — this is the one bespoke cryptographic construction in the
 * project. It is deliberately close to the published X3DH spec, but the
 * glue itself has NOT been independently audited. Do not ship as a
 * "private messenger" before that audit (see project plan, Phase 6).
 *
 * It provides forward secrecy (via the ephemeral + one-time prekeys). It
 * does NOT by itself provide post-compromise security — that requires the
 * DH-ratchet step, tracked as future work. The symmetric ratchet that runs
 * on top of the derived root key is in [SymmetricRatchet].
 *
 * SECURITY NOTE (2026-07-18 audit): [initiate] takes the caller's own
 * pinned/expected peer key and enforces it matches the fetched bundle's
 * [PreKeyBundle.dhIdentityKey] *before* doing anything else with the
 * bundle. This is load-bearing, not defensive boilerplate: the bundle's
 * signature only proves internal self-consistency (that `signedPreKey`
 * was signed by the `signingIdentityKey` bundled alongside it) — it says
 * nothing about whether that bundle actually belongs to the peer the
 * caller intended to contact. Prekey bundles are fetched from the relay
 * (see `MessengerClient.fetchBundle`), which is not a trusted party in
 * this threat model; without this check a malicious or compromised relay
 * could hand back its own, honestly-self-signed bundle in place of the
 * real peer's and silently MITM every first-contact session.
 */
object X3dhLite {
    private const val ROOT_INFO = "messenger-x3dh-lite-root-v1"
    const val ROOT_KEY_LENGTH = 32

    // Per the published X3DH spec: 32 bytes of 0xFF prepended to the concatenated DH outputs
    // before HKDF. This keeps the construction byte-for-byte spec-shaped (domain separation from
    // any other use of the same DH outputs) rather than a from-scratch deviation.
    private val F_PREFIX = ByteArray(32) { 0xFF.toByte() }

    class InitiatorResult(
        val rootKey: ByteArray,
        val ephemeralPublicKey: ByteArray,
        val associatedData: ByteArray,
        val usedSignedPreKeyId: Int,
        val usedOneTimePreKeyId: Int,
        val usedSigningIdentityKey: ByteArray,
    )

    class ResponderResult(
        val rootKey: ByteArray,
        val associatedData: ByteArray,
    )

    /**
     * Initiator side. [expectedPeerDhIdentityKey] is the peer this session is *supposed* to be
     * with (the routing key the caller already resolved/pinned) — checked against the bundle
     * before it's trusted for anything else. Verifies the bundle's signed prekey, then derives
     * the shared root key. Throws [UnexpectedBundleIdentity] if the bundle is for a different
     * identity than expected, or [BadPreKeySignature] if the bundle is forged.
     */
    fun initiate(
        initiatorDhIdentity: NoiseStaticKeyPair,
        expectedPeerDhIdentityKey: ByteArray,
        bundle: PreKeyBundle,
        expectedPeerSigningIdentityKey: ByteArray? = null,
    ): InitiatorResult {
        if (!bundle.dhIdentityKey.contentEquals(expectedPeerDhIdentityKey)) {
            throw UnexpectedBundleIdentity(expectedPeerDhIdentityKey.toHex(), bundle.dhIdentityKey.toHex())
        }
        // dhIdentityKey and signingIdentityKey are independently-generated keypairs (see
        // DeviceIdentity) — the check above says nothing about signingIdentityKey. Without this,
        // Ed25519Signatures.verify() below only proves the bundle is *internally* self-consistent
        // (whoever holds signingIdentityKey's private half signed signedPreKey), not that it's the
        // real peer's signing key. A relay that keeps dhIdentityKey untouched but swaps in its own
        // self-signed signingIdentityKey/signedPreKey sails through both the pin above and the
        // user-facing safety number (which also only hashes dhIdentityKey) — see
        // X3dhSigningKeySpoofExploitTest. First contact has nothing to pin against yet (null,
        // TOFU); E2eeManager persists it after and passes it on every later call.
        if (expectedPeerSigningIdentityKey != null && !bundle.signingIdentityKey.contentEquals(expectedPeerSigningIdentityKey)) {
            throw UnexpectedSigningIdentity(expectedPeerSigningIdentityKey.toHex(), bundle.signingIdentityKey.toHex())
        }
        if (!Ed25519Signatures.verify(bundle.signingIdentityKey, bundle.signedPreKey, bundle.signedPreKeySignature)) {
            throw BadPreKeySignature()
        }
        val ephemeral = X25519.generateKeyPair()

        var dh1: ByteArray? = null
        var dh2: ByteArray? = null
        var dh3: ByteArray? = null
        var dh4: ByteArray? = null
        var ikm: ByteArray? = null
        try {
            dh1 = X25519.dh(initiatorDhIdentity.privateKey, bundle.signedPreKey)
            dh2 = X25519.dh(ephemeral.privateKey, bundle.dhIdentityKey)
            dh3 = X25519.dh(ephemeral.privateKey, bundle.signedPreKey)
            dh4 = bundle.oneTimePreKey?.let { X25519.dh(ephemeral.privateKey, it) }
            ikm = F_PREFIX + dh1 + dh2 + dh3 + (dh4 ?: ByteArray(0))

            return InitiatorResult(
                rootKey = Hkdf.derive(ikm, ROOT_INFO, ROOT_KEY_LENGTH),
                ephemeralPublicKey = ephemeral.publicKey,
                associatedData = initiatorDhIdentity.publicKey + bundle.dhIdentityKey,
                usedSignedPreKeyId = bundle.signedPreKeyId,
                usedOneTimePreKeyId = bundle.oneTimePreKeyId,
                usedSigningIdentityKey = bundle.signingIdentityKey,
            )
        } finally {
            // Best-effort hygiene: the JVM gives no hard guarantee these bytes are gone (GC may
            // have already copied them, no mlock/swap protection), but there's no reason to leave
            // the raw DH outputs and the ephemeral private key sitting in memory any longer than
            // it takes to fold them into rootKey.
            wipe(dh1, dh2, dh3, dh4, ikm, ephemeral.privateKey)
        }
    }

    /**
     * Responder side. Reconstructs the same root key from its own private
     * prekeys and the initiator's identity + ephemeral public keys.
     */
    fun respond(
        responderDhIdentity: NoiseStaticKeyPair,
        signedPreKey: NoiseStaticKeyPair,
        oneTimePreKey: NoiseStaticKeyPair?,
        initiatorDhIdentityKey: ByteArray,
        initiatorEphemeralKey: ByteArray,
    ): ResponderResult {
        var dh1: ByteArray? = null
        var dh2: ByteArray? = null
        var dh3: ByteArray? = null
        var dh4: ByteArray? = null
        var ikm: ByteArray? = null
        try {
            dh1 = X25519.dh(signedPreKey.privateKey, initiatorDhIdentityKey)
            dh2 = X25519.dh(responderDhIdentity.privateKey, initiatorEphemeralKey)
            dh3 = X25519.dh(signedPreKey.privateKey, initiatorEphemeralKey)
            dh4 = oneTimePreKey?.let { X25519.dh(it.privateKey, initiatorEphemeralKey) }
            ikm = F_PREFIX + dh1 + dh2 + dh3 + (dh4 ?: ByteArray(0))

            return ResponderResult(
                rootKey = Hkdf.derive(ikm, ROOT_INFO, ROOT_KEY_LENGTH),
                associatedData = initiatorDhIdentityKey + responderDhIdentity.publicKey,
            )
        } finally {
            // responderDhIdentity/signedPreKey/oneTimePreKey are owned by the caller (PreKeyStore
            // etc.) and outlive this call — only the DH outputs computed here are ours to wipe.
            wipe(dh1, dh2, dh3, dh4, ikm)
        }
    }

    private fun wipe(vararg arrays: ByteArray?) {
        for (array in arrays) array?.fill(0)
    }
}

class BadPreKeySignature : Exception("prekey bundle signature verification failed")

/**
 * The prekey bundle's [PreKeyBundle.dhIdentityKey] didn't match the peer the caller intended to
 * open a session with — either a relay bug or a relay actively substituting a different identity.
 * Either way this must never be silently accepted.
 */
class UnexpectedBundleIdentity(expectedHex: String, actualHex: String) :
    Exception("prekey bundle identity mismatch: expected $expectedHex, got $actualHex")

/**
 * The prekey bundle's [PreKeyBundle.signingIdentityKey] didn't match the signing key this peer
 * used the first time we ever fetched a bundle for them — a relay serving a self-consistent but
 * substituted signing key/signed-prekey pair, or the peer genuinely rotated their signing
 * identity (device reinstall). Either way this must never be silently accepted.
 */
class UnexpectedSigningIdentity(expectedHex: String, actualHex: String) :
    Exception("prekey bundle signing identity mismatch: expected $expectedHex, got $actualHex")
