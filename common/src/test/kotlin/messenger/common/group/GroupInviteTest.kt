package messenger.common.group

import kotlin.random.Random
import messenger.common.crypto.Ed25519Signatures
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroupInviteTest {

    @Test
    fun `a created invite parses back to the same fields and verifies`() {
        val groupId = Random.nextBytes(GROUP_ID_LENGTH)
        val inviterDhKey = Random.nextBytes(32)
        val signingKeyPair = Ed25519Signatures.generateKeyPair()
        val expiry = 1_800_000_000_000L

        val link = GroupInvite.create(groupId, inviterDhKey, signingKeyPair, expiry, Random.nextBytes(32))
        val parsed = requireNotNull(GroupInvite.parse(link))

        assertArrayEquals(groupId, parsed.groupId)
        assertArrayEquals(inviterDhKey, parsed.inviterDhKey)
        assertEquals(expiry, parsed.expiresAtMillis)
        assertTrue(parsed.verifySignature())
    }

    @Test
    fun `an invite is expired once now is past its expiry`() {
        val link = GroupInvite.create(Random.nextBytes(GROUP_ID_LENGTH), Random.nextBytes(32), Ed25519Signatures.generateKeyPair(), 1000L, Random.nextBytes(32))
        val parsed = requireNotNull(GroupInvite.parse(link))

        assertFalse(parsed.isExpired(999L))
        assertTrue(parsed.isExpired(1000L))
        assertTrue(parsed.isExpired(2000L))
    }

    @Test
    fun `a tampered invite fails signature verification`() {
        val groupId = Random.nextBytes(GROUP_ID_LENGTH)
        val link = GroupInvite.create(groupId, Random.nextBytes(32), Ed25519Signatures.generateKeyPair(), 1_800_000_000_000L, Random.nextBytes(32))
        val parsed = requireNotNull(GroupInvite.parse(link))

        // Forge an invite claiming the same signing key but a different group id.
        val forged = GroupInvite.Invite(
            groupId = Random.nextBytes(GROUP_ID_LENGTH),
            inviterDhKey = parsed.inviterDhKey,
            inviterSigningPublicKey = parsed.inviterSigningPublicKey,
            expiresAtMillis = parsed.expiresAtMillis,
            genesisHash = parsed.genesisHash,
            signature = parsed.signature,
        )

        assertFalse(forged.verifySignature())
    }

    @Test
    fun `an invite round-trips through raw bytes the same way it does through link text`() {
        val groupId = Random.nextBytes(GROUP_ID_LENGTH)
        val inviterDhKey = Random.nextBytes(32)
        val signingKeyPair = Ed25519Signatures.generateKeyPair()
        val link = GroupInvite.create(groupId, inviterDhKey, signingKeyPair, 1_800_000_000_000L, Random.nextBytes(32))
        val fromLink = requireNotNull(GroupInvite.parse(link))

        val fromBytes = requireNotNull(GroupInvite.decode(fromLink.encode()))

        assertArrayEquals(fromLink.groupId, fromBytes.groupId)
        assertArrayEquals(fromLink.inviterDhKey, fromBytes.inviterDhKey)
        assertArrayEquals(fromLink.inviterSigningPublicKey, fromBytes.inviterSigningPublicKey)
        assertEquals(fromLink.expiresAtMillis, fromBytes.expiresAtMillis)
        assertTrue(fromBytes.verifySignature())
    }

    @Test
    fun `non-voron-group strings parse to null`() {
        assertNull(GroupInvite.parse("voron:abcdef"))
        assertNull(GroupInvite.parse("https://example.com"))
        assertNull(GroupInvite.parse("voron-group:not-valid-base64!!!"))
        assertNull(GroupInvite.parse("voron-group:${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(10))}"))
    }
}
