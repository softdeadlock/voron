package messenger.common.e2ee

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The E2EE plaintext for call signaling (ring/answer/ICE/hangup) — parallel to
 * [messenger.common.client.ApplicationMessage] but without a display name, avatar, or
 * message ID, none of which are meaningful for SDP/ICE payloads.
 *
 * Wire layout: `[16-byte callId][1-byte kind][remaining UTF-8 payload]`.
 */
object CallSignal {
    const val RING: Byte = 0x01
    const val ANSWER: Byte = 0x02
    const val ICE_CANDIDATE: Byte = 0x03
    const val HANGUP: Byte = 0x04

    const val HANGUP_DECLINED: Byte = 0x00
    const val HANGUP_ENDED: Byte = 0x01
    const val HANGUP_BUSY: Byte = 0x02
    const val HANGUP_TIMEOUT: Byte = 0x03

    private const val CALL_ID_LENGTH = 16
    private const val HEADER_LENGTH = CALL_ID_LENGTH + 1

    fun encode(callId: UUID, kind: Byte, payload: String): ByteArray {
        val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(HEADER_LENGTH + payloadBytes.size)
        buffer.putLong(callId.mostSignificantBits)
        buffer.putLong(callId.leastSignificantBits)
        buffer.put(kind)
        buffer.put(payloadBytes)
        return buffer.array()
    }

    class Decoded(val callId: UUID, val kind: Byte, val payload: String)

    fun decode(bytes: ByteArray): Decoded {
        require(bytes.size >= HEADER_LENGTH) { "call signal too short to contain a header" }
        val buffer = ByteBuffer.wrap(bytes)
        val callId = UUID(buffer.long, buffer.long)
        val kind = buffer.get()
        val payload = String(bytes, HEADER_LENGTH, bytes.size - HEADER_LENGTH, StandardCharsets.UTF_8)
        return Decoded(callId, kind, payload)
    }
}
