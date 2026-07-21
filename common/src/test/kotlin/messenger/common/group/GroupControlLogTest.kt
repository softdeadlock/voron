package messenger.common.group

import kotlin.random.Random
import messenger.common.crypto.Ed25519Signatures
import messenger.common.util.hexToByteArray
import messenger.common.util.toHex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroupControlLogTest {

    private class Identity {
        val dhKey: ByteArray = Random.nextBytes(32)
        val signingKeyPair: Ed25519Signatures.SigningKeyPair = Ed25519Signatures.generateKeyPair()
    }

    private fun fakeGroupId(): ByteArray = Random.nextBytes(GROUP_ID_LENGTH)

    private fun genesis(groupId: ByteArray, owner: Identity, name: String = "Test Group") =
        GroupControlEvent.create(groupId, GENESIS_HASH, GroupEventType.CREATE_GROUP, owner.dhKey, owner.signingKeyPair, GroupEventPayloads.encodeCreateGroup(name))

    private fun addMember(groupId: ByteArray, log: GroupControlLog, signer: Identity, target: Identity) =
        GroupControlEvent.create(groupId, log.headHash(), GroupEventType.ADD_MEMBER, signer.dhKey, signer.signingKeyPair, GroupEventPayloads.encodeAddMember(target.dhKey, target.signingKeyPair.publicKey))

    @Test
    fun `genesis event makes the creator the owner`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val log = GroupControlLog(groupId)

        assertTrue(log.ingest(genesis(groupId, owner, "Book Club")))

        assertEquals("Book Club", log.state.name)
        assertEquals(GroupRole.OWNER, log.state.members.getValue(owner.dhKey.toHex()).role)
    }

    @Test
    fun `owner can add a member and the member can be removed by an admin with permission`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val alice = Identity()
        val log = GroupControlLog(groupId)
        log.ingest(genesis(groupId, owner))
        log.ingest(addMember(groupId, log, owner, alice))

        assertTrue(log.state.members.containsKey(alice.dhKey.toHex()))

        val promote = GroupControlEvent.create(
            groupId, log.headHash(), GroupEventType.PROMOTE_ADMIN, owner.dhKey, owner.signingKeyPair,
            GroupEventPayloads.encodePromoteAdmin(alice.dhKey, AdminPermission.REMOVE_MEMBERS),
        )
        log.ingest(promote)
        assertEquals(GroupRole.ADMIN, log.state.members.getValue(alice.dhKey.toHex()).role)

        val bob = Identity()
        log.ingest(addMember(groupId, log, owner, bob))
        val remove = GroupControlEvent.create(
            groupId, log.headHash(), GroupEventType.REMOVE_MEMBER, alice.dhKey, alice.signingKeyPair,
            GroupEventPayloads.encodeMemberKey(bob.dhKey),
        )
        assertTrue(log.ingest(remove))
        assertFalse(log.state.members.containsKey(bob.dhKey.toHex()))
    }

    @Test
    fun `a plain member without permission cannot add or remove anyone`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val alice = Identity()
        val bob = Identity()
        val log = GroupControlLog(groupId)
        log.ingest(genesis(groupId, owner))
        log.ingest(addMember(groupId, log, owner, alice))

        // Alice is a plain MEMBER (never promoted) trying to add Bob -- should be silently rejected.
        val forgedAdd = addMember(groupId, log, alice, bob)
        log.ingest(forgedAdd)

        assertFalse(log.state.members.containsKey(bob.dhKey.toHex()))
    }

    @Test
    fun `invite-links-enabled lets any existing member add someone`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val alice = Identity()
        val bob = Identity()
        val log = GroupControlLog(groupId)
        log.ingest(genesis(groupId, owner))
        log.ingest(addMember(groupId, log, owner, alice))

        val enableInvites = GroupControlEvent.create(
            groupId, log.headHash(), GroupEventType.SET_INVITE_LINKS_ENABLED, owner.dhKey, owner.signingKeyPair,
            GroupEventPayloads.encodeBoolean(true),
        )
        log.ingest(enableInvites)

        // Alice is still a plain MEMBER, but invite links are now on for the whole group.
        log.ingest(addMember(groupId, log, alice, bob))

        assertTrue(log.state.members.containsKey(bob.dhKey.toHex()))
    }

    @Test
    fun `only the owner can promote, demote, or transfer ownership`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val alice = Identity()
        val bob = Identity()
        val log = GroupControlLog(groupId)
        log.ingest(genesis(groupId, owner))
        log.ingest(addMember(groupId, log, owner, alice))
        log.ingest(addMember(groupId, log, owner, bob))

        // Alice (plain member) tries to promote Bob to admin -- rejected.
        val forgedPromote = GroupControlEvent.create(
            groupId, log.headHash(), GroupEventType.PROMOTE_ADMIN, alice.dhKey, alice.signingKeyPair,
            GroupEventPayloads.encodePromoteAdmin(bob.dhKey, AdminPermission.ADD_MEMBERS),
        )
        log.ingest(forgedPromote)
        assertEquals(GroupRole.MEMBER, log.state.members.getValue(bob.dhKey.toHex()).role)

        // The real owner transfers ownership to Alice.
        val transfer = GroupControlEvent.create(
            groupId, log.headHash(), GroupEventType.TRANSFER_OWNERSHIP, owner.dhKey, owner.signingKeyPair,
            GroupEventPayloads.encodeMemberKey(alice.dhKey),
        )
        assertTrue(log.ingest(transfer))
        assertEquals(GroupRole.OWNER, log.state.members.getValue(alice.dhKey.toHex()).role)
        assertEquals(GroupRole.ADMIN, log.state.members.getValue(owner.dhKey.toHex()).role)

        // The old owner (now just an admin) can no longer promote anyone.
        val oldOwnerPromotes = GroupControlEvent.create(
            groupId, log.headHash(), GroupEventType.PROMOTE_ADMIN, owner.dhKey, owner.signingKeyPair,
            GroupEventPayloads.encodePromoteAdmin(bob.dhKey, AdminPermission.ADD_MEMBERS),
        )
        log.ingest(oldOwnerPromotes)
        assertEquals(GroupRole.MEMBER, log.state.members.getValue(bob.dhKey.toHex()).role)
    }

    @Test
    fun `the owner cannot be removed via REMOVE_MEMBER, only replaced via TRANSFER_OWNERSHIP`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val log = GroupControlLog(groupId)
        log.ingest(genesis(groupId, owner))

        val bogusRemove = GroupControlEvent.create(
            groupId, log.headHash(), GroupEventType.REMOVE_MEMBER, owner.dhKey, owner.signingKeyPair,
            GroupEventPayloads.encodeMemberKey(owner.dhKey),
        )
        log.ingest(bogusRemove)

        assertTrue(log.state.members.containsKey(owner.dhKey.toHex()))
    }

    @Test
    fun `a member can leave voluntarily`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val alice = Identity()
        val log = GroupControlLog(groupId)
        log.ingest(genesis(groupId, owner))
        log.ingest(addMember(groupId, log, owner, alice))

        val leave = GroupControlEvent.create(groupId, log.headHash(), GroupEventType.LEAVE_GROUP, alice.dhKey, alice.signingKeyPair, ByteArray(0))
        assertTrue(log.ingest(leave))
        assertFalse(log.state.members.containsKey(alice.dhKey.toHex()))
    }

    @Test
    fun `a group with no owner or admin left is reported as frozen`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val log = GroupControlLog(groupId)
        log.ingest(genesis(groupId, owner))
        assertFalse(log.state.isFrozen)

        log.ingest(GroupControlEvent.create(groupId, log.headHash(), GroupEventType.LEAVE_GROUP, owner.dhKey, owner.signingKeyPair, ByteArray(0)))
        assertTrue(log.state.isFrozen)
    }

    @Test
    fun `a forged event with an invalid signature is rejected outright`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val attacker = Ed25519Signatures.generateKeyPair()
        val log = GroupControlLog(groupId)
        log.ingest(genesis(groupId, owner))

        // Claims to be signed by owner's signing key but is actually signed by someone else's.
        val forged = GroupControlEvent(
            groupId, log.headHash(), GroupEventType.SET_ANNOUNCEMENT_MODE, owner.dhKey, owner.signingKeyPair.publicKey,
            GroupEventPayloads.encodeBoolean(true),
            signature = Ed25519Signatures.sign(attacker.privateKey, "not the real signed bytes".toByteArray()),
        )

        assertFalse(log.ingest(forged))
        assertFalse(log.state.announcementMode)
    }

    @Test
    fun `impersonating a member's dhKey with a different (attacker-owned) signing key is rejected`() {
        // A GroupControlEvent's signerSigningPublicKey is self-declared -- verifySignature() alone
        // only proves the attacker's own keypair is internally consistent, not that it's really the
        // key owner.dhKey's real device was ever pinned to. Without GroupControlLog checking the
        // declared key against the member's *pinned* one, an attacker who merely knows the owner's
        // public dhKey (not secret -- it's the routing address shared in every invite/QR) could sign
        // with a keypair of their own choosing and impersonate the owner completely.
        val groupId = fakeGroupId()
        val owner = Identity()
        val bob = Identity()
        val attackerKeyPair = Ed25519Signatures.generateKeyPair()
        val log = GroupControlLog(groupId)
        log.ingest(genesis(groupId, owner))
        log.ingest(addMember(groupId, log, owner, bob))

        // Internally consistent (attackerKeyPair signed its own content correctly) but claims to be
        // signed by the real owner's dhKey -- must be rejected because attackerKeyPair.publicKey
        // doesn't match owner's pinned signingKey from genesis.
        val impersonatingRemove = GroupControlEvent.create(
            groupId, log.headHash(), GroupEventType.REMOVE_MEMBER, owner.dhKey, attackerKeyPair,
            GroupEventPayloads.encodeMemberKey(bob.dhKey),
        )
        assertTrue(log.ingest(impersonatingRemove)) // accepted into the log (self-consistent signature)...
        assertTrue(log.state.members.containsKey(bob.dhKey.toHex())) // ...but never authorized/applied.

        // Same attack against a non-owner privileged member's dhKey, pinned at ADD_MEMBER time (not
        // genesis) -- promote bob for real first, then have the attacker impersonate *him*.
        log.ingest(
            GroupControlEvent.create(
                groupId, log.headHash(), GroupEventType.PROMOTE_ADMIN, owner.dhKey, owner.signingKeyPair,
                GroupEventPayloads.encodePromoteAdmin(bob.dhKey, AdminPermission.ADD_MEMBERS),
            ),
        )
        assertEquals(GroupRole.ADMIN, log.state.members.getValue(bob.dhKey.toHex()).role)

        val mallory = Identity()
        val impersonatingAdd = GroupControlEvent.create(
            groupId, log.headHash(), GroupEventType.ADD_MEMBER, bob.dhKey, attackerKeyPair,
            GroupEventPayloads.encodeAddMember(mallory.dhKey, mallory.signingKeyPair.publicKey),
        )
        log.ingest(impersonatingAdd)
        assertFalse(log.state.members.containsKey(mallory.dhKey.toHex()))
    }

    @Test
    fun `an event for a different group is rejected`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val log = GroupControlLog(groupId)
        log.ingest(genesis(groupId, owner))

        val wrongGroupEvent = genesis(fakeGroupId(), owner)
        assertFalse(log.ingest(wrongGroupEvent))
    }

    @Test
    fun `ingesting the same event twice is idempotent`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val alice = Identity()
        val log = GroupControlLog(groupId)
        log.ingest(genesis(groupId, owner))
        val add = addMember(groupId, log, owner, alice)

        assertTrue(log.ingest(add))
        assertTrue(log.ingest(add))
        assertEquals(2, log.state.members.size)
    }

    @Test
    fun `concurrent forked events resolve deterministically to the same winner regardless of ingestion order`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val alice = Identity()
        val bob = Identity()
        val logA = GroupControlLog(groupId)
        val logB = GroupControlLog(groupId)
        val genesisEvent = genesis(groupId, owner)
        logA.ingest(genesisEvent)
        logB.ingest(genesisEvent)

        // Two admins, both offline from each other, each add a different member on top of the
        // exact same head -- a genuine fork.
        val addAlice = addMember(groupId, logA, owner, alice)
        val addBob = addMember(groupId, logA, owner, bob) // same prevEventHash as addAlice

        // Log A ingests alice-then-bob; log B ingests bob-then-alice -- opposite order.
        logA.ingest(addAlice)
        logA.ingest(addBob)
        logB.ingest(addBob)
        logB.ingest(addAlice)

        // Both logs must converge to the exact same canonical member set despite the opposite
        // ingestion order.
        assertEquals(logA.state.members.keys, logB.state.members.keys)
        // Exactly one of the two forked adds is canonical, not both -- a real fork, not a case
        // where they coincidentally don't conflict.
        assertEquals(2, logA.state.members.size)
    }

    @Test
    fun `eventsInCanonicalOrder replays to the same state a fresh log would reach by ingesting it directly`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val alice = Identity()
        val log = GroupControlLog(groupId)
        log.ingest(genesis(groupId, owner))
        log.ingest(addMember(groupId, log, owner, alice))

        val replayed = GroupControlLog(groupId)
        for (event in log.eventsInCanonicalOrder()) replayed.ingest(event)

        assertEquals(log.state, replayed.state)
    }

    // CRASH (found via live device testing, 2026-07-21): a joining member pins the invite's signed
    // genesis hash via expectGenesis() before the CREATE_GROUP event carrying that hash has
    // necessarily arrived -- ingesting some other, later control event first (plausible: network
    // reordering, or the creator hasn't synced genesis to a relay this device already has a
    // connection through) used to crash with a bare NoSuchElementException out of recompute().
    @Test
    fun `ingesting a non-genesis event before the pinned genesis event has arrived does not crash`() {
        val groupId = fakeGroupId()
        val owner = Identity()
        val alice = Identity()
        val log = GroupControlLog(groupId)

        // Pin the genesis hash the way a joiner does from GroupInvite.genesisHash, before ever
        // ingesting anything.
        val genesisEvent = genesis(groupId, owner)
        log.expectGenesis(genesisEvent.hashHex().hexToByteArray())

        // Some later event referencing that not-yet-ingested genesis as its prevEventHash arrives
        // first -- must not crash, and must not fabricate any state out of it.
        val addBeforeGenesis = GroupControlEvent.create(
            groupId, genesisEvent.hashHex().hexToByteArray(), GroupEventType.ADD_MEMBER, owner.dhKey, owner.signingKeyPair,
            GroupEventPayloads.encodeAddMember(alice.dhKey, alice.signingKeyPair.publicKey),
        )
        log.ingest(addBeforeGenesis)
        assertEquals(GroupState.empty(groupId), log.state)

        // Once genesis actually arrives, the log recovers and reaches the same state either order would.
        log.ingest(genesisEvent)
        assertTrue(log.state.members.containsKey(owner.dhKey.toHex()))
        assertTrue(log.state.members.containsKey(alice.dhKey.toHex()))
    }
}
