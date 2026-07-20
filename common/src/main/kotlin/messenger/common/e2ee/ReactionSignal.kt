package messenger.common.e2ee

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import messenger.common.client.ApplicationMessage

/**
 * The E2EE plaintext for setting or clearing a reaction on an already-sent message — parallel to
 * [CallSignal]/[FileSignal] but referencing an existing [ApplicationMessage.MESSAGE_ID_LENGTH]-byte
 * message ID instead of carrying its own.
 *
 * Wire layout: `[8-byte messageId][remaining UTF-8 emoji]`. An empty remaining payload means "clear
 * my reaction on this message" — sending the same emoji again is how the sender's own UI toggles a
 * reaction off, since there's no separate "clear" frame type.
 */
object ReactionSignal {
    fun encode(messageId: ByteArray, emoji: String): ByteArray {
        require(messageId.size == ApplicationMessage.MESSAGE_ID_LENGTH) { "message id must be ${ApplicationMessage.MESSAGE_ID_LENGTH} bytes" }
        val emojiBytes = emoji.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(ApplicationMessage.MESSAGE_ID_LENGTH + emojiBytes.size)
        buffer.put(messageId)
        buffer.put(emojiBytes)
        return buffer.array()
    }

    class Decoded(val messageId: ByteArray, val emoji: String)

    fun decode(bytes: ByteArray): Decoded {
        require(bytes.size >= ApplicationMessage.MESSAGE_ID_LENGTH) { "reaction signal too short to contain a message id" }
        val messageId = bytes.copyOfRange(0, ApplicationMessage.MESSAGE_ID_LENGTH)
        val emoji = String(bytes, ApplicationMessage.MESSAGE_ID_LENGTH, bytes.size - ApplicationMessage.MESSAGE_ID_LENGTH, StandardCharsets.UTF_8)
        return Decoded(messageId, emoji)
    }
}
