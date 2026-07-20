package messenger.common.group

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GroupCryptoSessionTest {

    private fun fakeDhKey(): ByteArray = Random.nextBytes(32)
    private fun fakeGroupId(): ByteArray = Random.nextBytes(GROUP_ID_LENGTH)

    @Test
    fun `a member's messages decrypt correctly for another member who has their sender key`() {
        val groupId = fakeGroupId()
        val aliceKey = fakeDhKey()
        val bobKey = fakeDhKey()

        val alice = GroupCryptoSession(groupId, aliceKey)
        val bob = GroupCryptoSession(groupId, bobKey)

        val aliceSenderKey = alice.rekeySelf()
        bob.receiveSenderKey(aliceKey, aliceSenderKey)

        val ciphertext = alice.encrypt("hello group".toByteArray())
        val plaintext = bob.decrypt(aliceKey, ciphertext)

        assertArrayEquals("hello group".toByteArray(), plaintext)
    }

    @Test
    fun `multiple messages in a row decrypt in order via the advancing chain`() {
        val groupId = fakeGroupId()
        val aliceKey = fakeDhKey()
        val alice = GroupCryptoSession(groupId, aliceKey)
        val bob = GroupCryptoSession(groupId, fakeDhKey())
        bob.receiveSenderKey(aliceKey, alice.rekeySelf())

        val messages = listOf("one", "two", "three")
        val ciphertexts = messages.map { alice.encrypt(it.toByteArray()) }

        ciphertexts.zip(messages).forEach { (ciphertext, expected) ->
            assertArrayEquals(expected.toByteArray(), bob.decrypt(aliceKey, ciphertext))
        }
    }

    @Test
    fun `out-of-order delivery still decrypts, same as the 1-1 ratchet's skipped-message support`() {
        val groupId = fakeGroupId()
        val aliceKey = fakeDhKey()
        val alice = GroupCryptoSession(groupId, aliceKey)
        val bob = GroupCryptoSession(groupId, fakeDhKey())
        bob.receiveSenderKey(aliceKey, alice.rekeySelf())

        val first = alice.encrypt("first".toByteArray())
        val second = alice.encrypt("second".toByteArray())

        assertArrayEquals("second".toByteArray(), bob.decrypt(aliceKey, second))
        assertArrayEquals("first".toByteArray(), bob.decrypt(aliceKey, first))
    }

    @Test
    fun `decrypting from a member with no known sender key throws NoSuchElementException`() {
        val groupId = fakeGroupId()
        val aliceKey = fakeDhKey()
        val alice = GroupCryptoSession(groupId, aliceKey)
        val bob = GroupCryptoSession(groupId, fakeDhKey())
        alice.rekeySelf()
        // Bob never called receiveSenderKey for Alice.
        val ciphertext = alice.encrypt("hello".toByteArray())

        assertThrows(NoSuchElementException::class.java) { bob.decrypt(aliceKey, ciphertext) }
    }

    @Test
    fun `rekeying invalidates messages under the old epoch for anyone who only has the new key`() {
        val groupId = fakeGroupId()
        val aliceKey = fakeDhKey()
        val alice = GroupCryptoSession(groupId, aliceKey)
        val bob = GroupCryptoSession(groupId, fakeDhKey())

        bob.receiveSenderKey(aliceKey, alice.rekeySelf())
        val oldEpochMessage = alice.encrypt("before rekey".toByteArray())

        // Alice rekeys (e.g. someone was removed from the group) and redistributes -- simulating
        // Bob receiving the new key and forgetting the old one, exactly like the real GroupManager
        // does (one sender key on file per member, not a history of all past epochs).
        bob.receiveSenderKey(aliceKey, alice.rekeySelf())

        assertThrows(IllegalArgumentException::class.java) { bob.decrypt(aliceKey, oldEpochMessage) }
    }

    @Test
    fun `an out-of-order (stale) rekey distribution arriving after a newer one is ignored, not a downgrade`() {
        val groupId = fakeGroupId()
        val aliceKey = fakeDhKey()
        val alice = GroupCryptoSession(groupId, aliceKey)
        val bob = GroupCryptoSession(groupId, fakeDhKey())

        val oldEpochKey = alice.rekeySelf()
        val newEpochKey = alice.rekeySelf()
        // Bob receives the new epoch's key first (e.g. a mailbox/reconnect re-delivered the older
        // distribution message out of order), then the stale one arrives late.
        bob.receiveSenderKey(aliceKey, newEpochKey)
        bob.receiveSenderKey(aliceKey, oldEpochKey)

        val currentMessage = alice.encrypt("current epoch".toByteArray())
        assertArrayEquals("current epoch".toByteArray(), bob.decrypt(aliceKey, currentMessage))
    }

    @Test
    fun `a member added after a rekey can decrypt messages under the new epoch`() {
        val groupId = fakeGroupId()
        val aliceKey = fakeDhKey()
        val alice = GroupCryptoSession(groupId, aliceKey)
        val bob = GroupCryptoSession(groupId, fakeDhKey())
        val carol = GroupCryptoSession(groupId, fakeDhKey())

        bob.receiveSenderKey(aliceKey, alice.rekeySelf())
        alice.encrypt("before carol joined".toByteArray())

        // Carol joins -- Alice rekeys and redistributes to everyone (Bob + Carol).
        val newKey = alice.rekeySelf()
        bob.receiveSenderKey(aliceKey, newKey)
        carol.receiveSenderKey(aliceKey, newKey)

        val afterJoin = alice.encrypt("after carol joined".toByteArray())

        assertArrayEquals("after carol joined".toByteArray(), carol.decrypt(aliceKey, afterJoin))
        assertArrayEquals("after carol joined".toByteArray(), bob.decrypt(aliceKey, afterJoin))
    }

    @Test
    fun `encrypting before any rekeySelf call throws instead of using an absent chain`() {
        val alice = GroupCryptoSession(fakeGroupId(), fakeDhKey())

        assertThrows(IllegalStateException::class.java) { alice.encrypt("oops".toByteArray()) }
    }

    @Test
    fun `a ciphertext for a different group is rejected even with a matching sender key on file`() {
        val aliceKey = fakeDhKey()
        val groupA = GroupCryptoSession(fakeGroupId(), aliceKey)
        val groupB = GroupCryptoSession(fakeGroupId(), aliceKey)
        val bob = GroupCryptoSession(groupA.groupId, fakeDhKey())
        bob.receiveSenderKey(aliceKey, groupA.rekeySelf())
        groupB.rekeySelf()

        // Forge a ciphertext claiming to be for groupA's id but actually encrypted under groupB's
        // AAD/key -- decrypt() should reject it outright via the groupId equality check.
        val wrongGroupMessage = GroupCiphertextMessage(groupB.groupId, aliceKey, 0, 0, ByteArray(16))
        assertThrows(IllegalArgumentException::class.java) { bob.decrypt(aliceKey, wrongGroupMessage) }
    }

    @Test
    fun `currentSenderKeyMessageOrNull is null before any rekeySelf call`() {
        val alice = GroupCryptoSession(fakeGroupId(), fakeDhKey())
        assertEquals(null, alice.currentSenderKeyMessageOrNull())
    }

    @Test
    fun `currentSenderKeyMessageOrNull re-derives a key that still decrypts messages already sent under it`() {
        val groupId = fakeGroupId()
        val aliceKey = fakeDhKey()
        val alice = GroupCryptoSession(groupId, aliceKey)
        alice.rekeySelf()
        val firstMessage = alice.encrypt("sent before resend".toByteArray())

        // Bob "restarted" and never got the original distribution -- he asks for and receives the
        // re-derived key instead.
        val bob = GroupCryptoSession(groupId, fakeDhKey())
        val resent = requireNotNull(alice.currentSenderKeyMessageOrNull())
        bob.receiveSenderKey(aliceKey, resent)

        assertArrayEquals("sent before resend".toByteArray(), bob.decrypt(aliceKey, firstMessage))
    }

    @Test
    fun `wire encode-decode round-trips both message types`() {
        val senderKeyMsg = GroupSenderKeyMessage(fakeGroupId(), 3, Random.nextBytes(32))
        val decodedSenderKey = GroupSenderKeyMessage.decode(senderKeyMsg.encode())
        assertArrayEquals(senderKeyMsg.groupId, decodedSenderKey.groupId)
        assertEquals(senderKeyMsg.epoch, decodedSenderKey.epoch)
        assertArrayEquals(senderKeyMsg.chainKey, decodedSenderKey.chainKey)

        val ciphertextMsg = GroupCiphertextMessage(fakeGroupId(), fakeDhKey(), 2, 5, Random.nextBytes(48))
        val decodedCiphertext = GroupCiphertextMessage.decode(ciphertextMsg.encode())
        assertArrayEquals(ciphertextMsg.groupId, decodedCiphertext.groupId)
        assertArrayEquals(ciphertextMsg.senderDhKey, decodedCiphertext.senderDhKey)
        assertEquals(ciphertextMsg.epoch, decodedCiphertext.epoch)
        assertEquals(ciphertextMsg.counter, decodedCiphertext.counter)
        assertArrayEquals(ciphertextMsg.ciphertext, decodedCiphertext.ciphertext)
    }
}
