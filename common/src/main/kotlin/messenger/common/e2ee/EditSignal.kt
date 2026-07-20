package messenger.common.e2ee

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import messenger.common.client.ApplicationMessage

/**
 * The E2EE plaintext for replacing the text of an already-sent message — parallel to
 * [ReactionSignal], referencing an existing [ApplicationMessage.MESSAGE_ID_LENGTH]-byte message ID.
 *
 * Wire layout: `[8-byte messageId][remaining UTF-8 new text]`. No version/history is carried —
 * this fully replaces the message's displayed text, same as most messengers' "edit" feature.
 */
object EditSignal {
    fun encode(messageId: ByteArray, newText: String): ByteArray {
        require(messageId.size == ApplicationMessage.MESSAGE_ID_LENGTH) { "message id must be ${ApplicationMessage.MESSAGE_ID_LENGTH} bytes" }
        val textBytes = newText.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(ApplicationMessage.MESSAGE_ID_LENGTH + textBytes.size)
        buffer.put(messageId)
        buffer.put(textBytes)
        return buffer.array()
    }

    class Decoded(val messageId: ByteArray, val newText: String)

    fun decode(bytes: ByteArray): Decoded {
        require(bytes.size >= ApplicationMessage.MESSAGE_ID_LENGTH) { "edit signal too short to contain a message id" }
        val messageId = bytes.copyOfRange(0, ApplicationMessage.MESSAGE_ID_LENGTH)
        val newText = String(bytes, ApplicationMessage.MESSAGE_ID_LENGTH, bytes.size - ApplicationMessage.MESSAGE_ID_LENGTH, StandardCharsets.UTF_8)
        return Decoded(messageId, newText)
    }
}
