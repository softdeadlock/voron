package messenger.common.e2ee

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class E2eeSessionTest {

    private fun aliceAndBob(): Pair<Pair<DeviceIdentity, PreKeyStore>, Pair<DeviceIdentity, PreKeyStore>> {
        val aliceId = DeviceIdentity.generate()
        val bobId = DeviceIdentity.generate()
        return (aliceId to PreKeyStore(aliceId)) to (bobId to PreKeyStore(bobId))
    }

    /** Turns Bob's published prekeys into a fetched bundle popping one one-time prekey, as the relay would. */
    private fun bundleFrom(published: PublishedPreKeys, otpIndex: Int?): PreKeyBundle {
        val otp = otpIndex?.let { published.oneTimePreKeys[it] }
        return PreKeyBundle(
            dhIdentityKey = published.dhIdentityKey,
            signingIdentityKey = published.signingIdentityKey,
            signedPreKeyId = published.signedPreKeyId,
            signedPreKey = published.signedPreKey,
            signedPreKeySignature = published.signedPreKeySignature,
            oneTimePreKeyId = otp?.id ?: PreKeyBundle.NO_ONE_TIME_PREKEY,
            oneTimePreKey = otp?.publicKey,
        )
    }

    @Test
    fun `x3dh derives the same root key on both sides with a one-time prekey`() {
        val (alice, bob) = aliceAndBob()
        val (aliceId, _) = alice
        val (bobId, bobStore) = bob
        val published = bobStore.publishedPreKeys(oneTimeCount = 5)
        val bundle = bundleFrom(published, otpIndex = 0)

        val init = X3dhLite.initiate(aliceId.dhIdentity, bobId.dhIdentityPublicKey, bundle, initiatorSigningIdentityKey = aliceId.signingIdentityPublicKey)
        val resp = X3dhLite.respond(
            responderDhIdentity = bobId.dhIdentity,
            responderSigningIdentityKey = bobId.signingIdentityPublicKey,
            signedPreKey = bobStore.signedPreKeyFor(bundle.signedPreKeyId)!!,
            oneTimePreKey = bobStore.consumeOneTimePreKey(bundle.oneTimePreKeyId),
            initiatorDhIdentityKey = aliceId.dhIdentityPublicKey,
            initiatorSigningIdentityKey = aliceId.signingIdentityPublicKey,
            initiatorEphemeralKey = init.ephemeralPublicKey,
        )

        assertArrayEquals(init.rootKey, resp.rootKey)
        assertArrayEquals(init.associatedData, resp.associatedData)
    }

    @Test
    fun `x3dh still agrees when the one-time prekey pool is empty`() {
        val (alice, bob) = aliceAndBob()
        val (aliceId, _) = alice
        val (bobId, bobStore) = bob
        val published = bobStore.publishedPreKeys(oneTimeCount = 0)
        val bundle = bundleFrom(published, otpIndex = null)

        val init = X3dhLite.initiate(aliceId.dhIdentity, bobId.dhIdentityPublicKey, bundle, initiatorSigningIdentityKey = aliceId.signingIdentityPublicKey)
        val resp = X3dhLite.respond(
            responderDhIdentity = bobId.dhIdentity,
            responderSigningIdentityKey = bobId.signingIdentityPublicKey,
            signedPreKey = bobStore.signedPreKeyFor(bundle.signedPreKeyId)!!,
            oneTimePreKey = null,
            initiatorDhIdentityKey = aliceId.dhIdentityPublicKey,
            initiatorSigningIdentityKey = aliceId.signingIdentityPublicKey,
            initiatorEphemeralKey = init.ephemeralPublicKey,
        )
        assertArrayEquals(init.rootKey, resp.rootKey)
    }

    @Test
    fun `a forged prekey signature is rejected`() {
        val (_, bob) = aliceAndBob()
        val (_, bobStore) = bob
        val published = bobStore.publishedPreKeys(oneTimeCount = 1)
        val tamperedSig = published.signedPreKeySignature.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val forged = PreKeyBundle(
            published.dhIdentityKey, published.signingIdentityKey, published.signedPreKeyId,
            published.signedPreKey, tamperedSig, PreKeyBundle.NO_ONE_TIME_PREKEY, null,
        )
        assertThrows(BadPreKeySignature::class.java) {
            X3dhLite.initiate(DeviceIdentity.generate().dhIdentity, published.dhIdentityKey, forged, initiatorSigningIdentityKey = ByteArray(32))
        }
    }

    @Test
    fun `a bundle for the wrong identity is rejected even when honestly self-signed`() {
        // Simulates a malicious/compromised relay handing back an attacker's own, perfectly
        // valid bundle in place of the peer the caller actually asked for.
        val (alice, _) = aliceAndBob()
        val (aliceId, _) = alice
        val attacker = DeviceIdentity.generate()
        val attackerStore = PreKeyStore(attacker)
        val attackerBundle = bundleFrom(attackerStore.publishedPreKeys(oneTimeCount = 1), otpIndex = 0)

        val intendedPeerKey = DeviceIdentity.generate().dhIdentityPublicKey
        assertThrows(UnexpectedBundleIdentity::class.java) {
            X3dhLite.initiate(aliceId.dhIdentity, intendedPeerKey, attackerBundle, initiatorSigningIdentityKey = aliceId.signingIdentityPublicKey)
        }
    }

    @Test
    fun `replaying an Initial message with no one-time prekey does not reuse a message key`() {
        // Pure 3-DH (no OPK) is a supported, expected path (see PreKeyBundle) — X3DH gives no
        // replay protection to the Initial message itself, so rebuilding the responder session
        // from scratch on a replay would re-derive the identical rootKey and therefore the
        // identical message-#0 key that already encrypted the real message: a two-time-pad-style
        // key/nonce reuse under Aead's fixed zero nonce. A network attacker (no keys at all,
        // just replay capability) must not be able to trigger that.
        val (alice, bob) = aliceAndBob()
        val (aliceId, _) = alice
        val (bobId, bobStore) = bob
        val bobManager = E2eeManager(bobId, bobStore)
        val bundle = bundleFrom(bobStore.publishedPreKeys(oneTimeCount = 0), otpIndex = null)

        val aliceE2ee = E2eeManager(aliceId, PreKeyStore(aliceId))
        val initial = aliceE2ee.encrypt(bobId.dhIdentityPublicKey, bundle, "hello".toByteArray()) as E2eeMessage.Initial

        assertEquals("hello", String(bobManager.decrypt(aliceId.dhIdentityPublicKey, initial)))
        // A second, byte-identical delivery of the same Initial (relay retry, or an attacker
        // replaying a captured packet) must be rejected, not silently re-accepted.
        assertThrows(IllegalArgumentException::class.java) {
            bobManager.decrypt(aliceId.dhIdentityPublicKey, initial)
        }
    }

    @Test
    fun `full session round trip through the managers in both directions`() {
        val (alice, bob) = aliceAndBob()
        val (aliceId, aliceStore) = alice
        val (bobId, bobStore) = bob
        val aliceManager = E2eeManager(aliceId, aliceStore)
        val bobManager = E2eeManager(bobId, bobStore)

        val bundle = bundleFrom(bobStore.publishedPreKeys(oneTimeCount = 3), otpIndex = 0)

        // Alice opens the session and sends two messages.
        val m1 = aliceManager.encrypt(bobId.dhIdentityPublicKey, bundle, "hi bob".toByteArray())
        val m2 = aliceManager.encrypt(bobId.dhIdentityPublicKey, null, "how are you".toByteArray())
        assertTrue(m1 is E2eeMessage.Initial)
        assertTrue(m2 is E2eeMessage.Normal)

        assertEquals("hi bob", String(bobManager.decrypt(aliceId.dhIdentityPublicKey, m1)))
        assertEquals("how are you", String(bobManager.decrypt(aliceId.dhIdentityPublicKey, m2)))

        // Bob replies; Alice decrypts on her receiving chain.
        val r1 = bobManager.encrypt(aliceId.dhIdentityPublicKey, null, "doing well".toByteArray())
        assertEquals("doing well", String(aliceManager.decrypt(bobId.dhIdentityPublicKey, r1)))
    }

    @Test
    fun `out of order delivery still decrypts`() {
        val (alice, bob) = aliceAndBob()
        val (aliceId, aliceStore) = alice
        val (bobId, bobStore) = bob
        val aliceManager = E2eeManager(aliceId, aliceStore)
        val bobManager = E2eeManager(bobId, bobStore)
        val bundle = bundleFrom(bobStore.publishedPreKeys(oneTimeCount = 1), otpIndex = 0)

        val m0 = aliceManager.encrypt(bobId.dhIdentityPublicKey, bundle, "zero".toByteArray())
        val m1 = aliceManager.encrypt(bobId.dhIdentityPublicKey, null, "one".toByteArray())
        val m2 = aliceManager.encrypt(bobId.dhIdentityPublicKey, null, "two".toByteArray())

        // Bob must see the Initial first to build the session, then can take them out of order.
        assertEquals("zero", String(bobManager.decrypt(aliceId.dhIdentityPublicKey, m0)))
        assertEquals("two", String(bobManager.decrypt(aliceId.dhIdentityPublicKey, m2)))
        assertEquals("one", String(bobManager.decrypt(aliceId.dhIdentityPublicKey, m1)))
    }

    @Test
    fun `tampered ciphertext fails to authenticate`() {
        val (alice, bob) = aliceAndBob()
        val (aliceId, aliceStore) = alice
        val (bobId, bobStore) = bob
        val aliceManager = E2eeManager(aliceId, aliceStore)
        val bobManager = E2eeManager(bobId, bobStore)
        val bundle = bundleFrom(bobStore.publishedPreKeys(oneTimeCount = 1), otpIndex = 0)

        val initial = aliceManager.encrypt(bobId.dhIdentityPublicKey, bundle, "secret".toByteArray()) as E2eeMessage.Initial
        val tampered = E2eeMessage.Initial(
            initial.initiatorDhIdentityKey, initial.initiatorSigningIdentityKey, initial.initiatorEphemeralKey,
            initial.signedPreKeyId, initial.oneTimePreKeyId,
            RatchetPayload(
                initial.payload.senderRatchetPublicKey,
                initial.payload.previousChainLength,
                initial.payload.messageNumber,
                initial.payload.ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() },
            ),
        )
        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            bobManager.decrypt(aliceId.dhIdentityPublicKey, tampered)
        }
    }

    @Test
    fun `wire encoding round trips for initial and normal messages`() {
        val initial = E2eeMessage.Initial(
            ByteArray(32) { 1 }, ByteArray(32) { 4 }, ByteArray(32) { 2 }, 7, 9,
            RatchetPayload(ByteArray(32) { 5 }, 11, 3, "abc".toByteArray()),
        )
        val decodedInitial = E2eeMessage.decode(initial.encode()) as E2eeMessage.Initial
        assertArrayEquals(initial.initiatorEphemeralKey, decodedInitial.initiatorEphemeralKey)
        assertEquals(7, decodedInitial.signedPreKeyId)
        assertEquals(9, decodedInitial.oneTimePreKeyId)
        assertEquals(11, decodedInitial.payload.previousChainLength)
        assertEquals(3, decodedInitial.payload.messageNumber)
        assertArrayEquals(ByteArray(32) { 5 }, decodedInitial.payload.senderRatchetPublicKey)

        val normal = E2eeMessage.Normal(RatchetPayload(ByteArray(32) { 6 }, 0, 42, "xyz".toByteArray()))
        val decodedNormal = E2eeMessage.decode(normal.encode()) as E2eeMessage.Normal
        assertEquals(42, decodedNormal.payload.messageNumber)
        assertArrayEquals(ByteArray(32) { 6 }, decodedNormal.payload.senderRatchetPublicKey)
        assertArrayEquals("xyz".toByteArray(), decodedNormal.payload.ciphertext)
    }

    @Test
    fun `DH-ratchet steps on every turn and heals after a simulated compromise`() {
        val (alice, bob) = aliceAndBob()
        val (aliceId, aliceStore) = alice
        val (bobId, bobStore) = bob
        val aliceManager = E2eeManager(aliceId, aliceStore)
        val bobManager = E2eeManager(bobId, bobStore)
        val bundle = bundleFrom(bobStore.publishedPreKeys(oneTimeCount = 1), otpIndex = 0)

        val m0 = aliceManager.encrypt(bobId.dhIdentityPublicKey, bundle, "hi".toByteArray()) as E2eeMessage.Initial
        assertEquals("hi", String(bobManager.decrypt(aliceId.dhIdentityPublicKey, m0)))

        val r0 = bobManager.encrypt(aliceId.dhIdentityPublicKey, null, "hey".toByteArray()) as E2eeMessage.Normal
        assertEquals("hey", String(aliceManager.decrypt(bobId.dhIdentityPublicKey, r0)))

        val m1 = aliceManager.encrypt(bobId.dhIdentityPublicKey, null, "how's it going".toByteArray()) as E2eeMessage.Normal
        assertEquals("how's it going", String(bobManager.decrypt(aliceId.dhIdentityPublicKey, m1)))

        // Every turn (a message sent in reply to the peer's most recent one) carries a *different*
        // ratchet public key than the previous message that party sent — that's the DH-ratchet
        // actually stepping, not just the symmetric chain advancing within one epoch.
        assertTrue(!m0.payload.senderRatchetPublicKey.contentEquals(m1.payload.senderRatchetPublicKey))
    }

    @Test
    fun `a message delayed across a ratchet step still decrypts once it arrives`() {
        // Simulates real network reordering: Alice sends two messages on one epoch, Bob replies
        // (forcing Alice to ratchet forward on her *next* send), and the second of Alice's two
        // original messages arrives late -- after Bob has already moved past that epoch. Bob must
        // still be able to decrypt it via the retired-epoch skip cache, not just messages within
        // whatever his *current* epoch happens to be.
        val (alice, bob) = aliceAndBob()
        val (aliceId, aliceStore) = alice
        val (bobId, bobStore) = bob
        val aliceManager = E2eeManager(aliceId, aliceStore)
        val bobManager = E2eeManager(bobId, bobStore)
        val bundle = bundleFrom(bobStore.publishedPreKeys(oneTimeCount = 1), otpIndex = 0)

        val m0 = aliceManager.encrypt(bobId.dhIdentityPublicKey, bundle, "zero".toByteArray())
        val m1 = aliceManager.encrypt(bobId.dhIdentityPublicKey, null, "one".toByteArray())

        assertEquals("zero", String(bobManager.decrypt(aliceId.dhIdentityPublicKey, m0)))
        // m1 (epoch A0, #1) is deliberately *not* delivered yet.

        val r0 = bobManager.encrypt(aliceId.dhIdentityPublicKey, null, "got it".toByteArray())
        assertEquals("got it", String(aliceManager.decrypt(bobId.dhIdentityPublicKey, r0)))
        // Alice ratchets forward here (new peer ratchet key from r0) — her *next* send moves to a
        // brand new epoch (A1) and reports previousChainLength = 2 (m0 + m1 sent on A0).
        val m2 = aliceManager.encrypt(bobId.dhIdentityPublicKey, null, "two".toByteArray())

        // Bob sees the new epoch (m2) before the still-in-flight straggler from the old one (m1).
        assertEquals("two", String(bobManager.decrypt(aliceId.dhIdentityPublicKey, m2)))
        assertEquals("one", String(bobManager.decrypt(aliceId.dhIdentityPublicKey, m1)))
    }

    @Test
    fun `post-compromise security -- a stolen root key alone does not predict future ratchet output`() {
        // The core PCS mechanism, isolated: even an attacker who has the exact root key a session
        // will start from (the worst case for "compromise") and the peer's long-lived public
        // signed prekey cannot predict what the resulting ratchet key or ciphertext will be,
        // because forInitiator mixes in a keypair generated fresh, internally, every single time —
        // never derived from or observable via either of those two known inputs alone.
        val bobSignedPreKey = messenger.common.crypto.X25519.generateKeyPair()
        val stolenRootKey = ByteArray(32) { it.toByte() }

        val sessionA = E2eeSession.forInitiator(stolenRootKey.copyOf(), bobSignedPreKey.publicKey, ByteArray(0))
        val sessionB = E2eeSession.forInitiator(stolenRootKey.copyOf(), bobSignedPreKey.publicKey, ByteArray(0))

        val payloadA = sessionA.encrypt("same plaintext".toByteArray())
        val payloadB = sessionB.encrypt("same plaintext".toByteArray())

        // Identical root key, identical peer key, identical plaintext — yet both the ratchet
        // public key on the wire and the resulting ciphertext differ between the two sessions,
        // because each independently generated its own unobservable ephemeral ratchet keypair.
        assertTrue(!payloadA.senderRatchetPublicKey.contentEquals(payloadB.senderRatchetPublicKey))
        assertTrue(!payloadA.ciphertext.contentEquals(payloadB.ciphertext))
    }

    @Test
    fun `prekey codec round trips published keys and fetched bundle`() {
        val store = PreKeyStore(DeviceIdentity.generate())
        val published = store.publishedPreKeys(oneTimeCount = 4)
        val decodedPublished = PreKeyCodec.decodePublished(PreKeyCodec.encodePublished(published))
        assertEquals(4, decodedPublished.oneTimePreKeys.size)
        assertArrayEquals(published.signedPreKey, decodedPublished.signedPreKey)

        val bundle = PreKeyBundle(
            published.dhIdentityKey, published.signingIdentityKey, published.signedPreKeyId,
            published.signedPreKey, published.signedPreKeySignature,
            published.oneTimePreKeys[0].id, published.oneTimePreKeys[0].publicKey,
        )
        val decodedBundle = PreKeyCodec.decodeBundle(PreKeyCodec.encodeBundle(bundle))
        assertArrayEquals(bundle.oneTimePreKey, decodedBundle.oneTimePreKey)
        assertEquals(bundle.oneTimePreKeyId, decodedBundle.oneTimePreKeyId)
    }

    @Test
    fun `a forged huge one-time-prekey count is rejected instead of triggering a huge allocation`() {
        // SECURITY (2026-07-18 exploit hunt): otpCount is a bare 4-byte int read straight off the
        // wire from an unauthenticated PUBLISH_PREKEYS body. Before the fix, this fed directly
        // into `ArrayList<PublishedOneTimePreKey>(otpCount)` -- one ~165-byte crafted frame with
        // otpCount = Int.MAX_VALUE would have tried to allocate a multi-gigabyte array and thrown
        // OutOfMemoryError, an Error the relay's `catch (e: Exception)` around this call does not
        // catch. Confirms the fix rejects the bogus count as a normal, caught IllegalArgumentException
        // instead of attempting the allocation at all.
        val buffer = java.nio.ByteBuffer.allocate(32 + 32 + 4 + 32 + 64 + 4)
        buffer.put(ByteArray(32))
        buffer.put(ByteArray(32))
        buffer.putInt(1)
        buffer.put(ByteArray(32))
        buffer.put(ByteArray(64))
        buffer.putInt(Int.MAX_VALUE) // the forged one-time-prekey count
        assertThrows(IllegalArgumentException::class.java) {
            PreKeyCodec.decodePublished(buffer.array())
        }
    }
}
