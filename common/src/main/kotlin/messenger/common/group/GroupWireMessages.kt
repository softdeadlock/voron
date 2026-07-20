package messenger.common.group

import java.nio.ByteBuffer

/** 16-byte random group identifier, generated once by whoever creates the group. */
const val GROUP_ID_LENGTH = 16

/**
 * Distributed 1:1 (over the existing pairwise X3DH+Double-Ratchet session, exactly like a normal
 * direct message) to every group member whenever the sender starts a fresh sender-key epoch — see
 * [GroupCryptoSession.rekeySelf] for when that happens. Carries the raw chain key a
 * [messenger.common.e2ee.SendingChain]/[messenger.common.e2ee.ReceivingChain] pair is seeded from,
 * so this message must never be sent anywhere except inside an already-E2EE pairwise envelope.
 */
class GroupSenderKeyMessage(val groupId: ByteArray, val epoch: Int, val chainKey: ByteArray) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(GROUP_ID_LENGTH + 4 + chainKey.size)
        buffer.put(groupId)
        buffer.putInt(epoch)
        buffer.put(chainKey)
        return buffer.array()
    }

    companion object {
        fun decode(bytes: ByteArray): GroupSenderKeyMessage {
            val buffer = ByteBuffer.wrap(bytes)
            val groupId = ByteArray(GROUP_ID_LENGTH).also { buffer.get(it) }
            val epoch = buffer.int
            val chainKey = ByteArray(buffer.remaining()).also { buffer.get(it) }
            return GroupSenderKeyMessage(groupId, epoch, chainKey)
        }
    }
}

/**
 * An actual group message, encrypted once under the sender's own sender-key chain (see
 * [GroupCryptoSession.encrypt]) and then fanned out unmodified inside a separate pairwise E2EE
 * envelope to every current member — every recipient decrypts the exact same [ciphertext] with
 * their own copy of the sender's chain, so this is the only per-message crypto work the sender
 * does regardless of group size.
 */
class GroupCiphertextMessage(
    val groupId: ByteArray,
    val senderDhKey: ByteArray,
    val epoch: Int,
    val counter: Int,
    val ciphertext: ByteArray,
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(GROUP_ID_LENGTH + senderDhKey.size + 4 + 4 + ciphertext.size)
        buffer.put(groupId)
        buffer.put(senderDhKey)
        buffer.putInt(epoch)
        buffer.putInt(counter)
        buffer.put(ciphertext)
        return buffer.array()
    }

    companion object {
        private const val DH_KEY_LENGTH = 32

        fun decode(bytes: ByteArray): GroupCiphertextMessage {
            val buffer = ByteBuffer.wrap(bytes)
            val groupId = ByteArray(GROUP_ID_LENGTH).also { buffer.get(it) }
            val senderDhKey = ByteArray(DH_KEY_LENGTH).also { buffer.get(it) }
            val epoch = buffer.int
            val counter = buffer.int
            val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }
            return GroupCiphertextMessage(groupId, senderDhKey, epoch, counter, ciphertext)
        }
    }
}
