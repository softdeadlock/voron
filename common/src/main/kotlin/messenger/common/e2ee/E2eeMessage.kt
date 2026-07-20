package messenger.common.e2ee

import java.nio.ByteBuffer

/**
 * The plaintext-to-the-relay-opaque E2EE payload carried inside a routing
 * envelope. Two shapes:
 *  - [Initial]: the first message of a session, carrying the X3DH header
 *    (initiator identity + ephemeral key + which prekeys were used) so the
 *    recipient can derive the root key, plus the first ratchet ciphertext.
 *  - [Normal]: every subsequent message — just the ratchet message number
 *    and ciphertext; the session is looked up by the sender's routing key.
 *
 * Both carry a [RatchetPayload], which since the DH-ratchet (2026-07-18) is
 * itself a full Double Ratchet header (sender's current ratchet public key +
 * previous-chain length), not just a bare message number.
 */
sealed interface E2eeMessage {
    fun encode(): ByteArray

    class Initial(
        val initiatorDhIdentityKey: ByteArray,
        val initiatorEphemeralKey: ByteArray,
        val signedPreKeyId: Int,
        val oneTimePreKeyId: Int,
        val payload: RatchetPayload,
    ) : E2eeMessage {
        override fun encode(): ByteArray {
            val buffer = ByteBuffer.allocate(1 + 32 + 32 + 4 + 4 + RatchetPayload.HEADER_LENGTH + payload.ciphertext.size)
            buffer.put(TYPE_INITIAL)
            buffer.put(initiatorDhIdentityKey)
            buffer.put(initiatorEphemeralKey)
            buffer.putInt(signedPreKeyId)
            buffer.putInt(oneTimePreKeyId)
            payload.encodeHeaderInto(buffer)
            buffer.put(payload.ciphertext)
            return buffer.array()
        }
    }

    class Normal(val payload: RatchetPayload) : E2eeMessage {
        override fun encode(): ByteArray {
            val buffer = ByteBuffer.allocate(1 + RatchetPayload.HEADER_LENGTH + payload.ciphertext.size)
            buffer.put(TYPE_NORMAL)
            payload.encodeHeaderInto(buffer)
            buffer.put(payload.ciphertext)
            return buffer.array()
        }
    }

    companion object {
        private const val TYPE_INITIAL: Byte = 0x01
        private const val TYPE_NORMAL: Byte = 0x02

        fun decode(bytes: ByteArray): E2eeMessage {
            require(bytes.isNotEmpty()) { "empty E2EE message" }
            val buffer = ByteBuffer.wrap(bytes)
            return when (val type = buffer.get()) {
                TYPE_INITIAL -> {
                    require(buffer.remaining() >= 32 + 32 + 4 + 4 + RatchetPayload.HEADER_LENGTH) { "truncated initial message" }
                    val ik = ByteArray(32).also { buffer.get(it) }
                    val ek = ByteArray(32).also { buffer.get(it) }
                    val spkId = buffer.int
                    val opkId = buffer.int
                    Initial(ik, ek, spkId, opkId, RatchetPayload.decodeFrom(buffer))
                }
                TYPE_NORMAL -> {
                    require(buffer.remaining() >= RatchetPayload.HEADER_LENGTH) { "truncated normal message" }
                    Normal(RatchetPayload.decodeFrom(buffer))
                }
                else -> throw IllegalArgumentException("unknown E2EE message type $type")
            }
        }
    }
}
