package messenger.common.e2ee

import messenger.common.client.ApplicationMessage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReactionSignalTest {

    @Test
    fun `encode-decode round-trips a set reaction`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH) { it.toByte() }
        val encoded = ReactionSignal.encode(messageId, "👍")
        val decoded = ReactionSignal.decode(encoded)
        assertArrayEquals(messageId, decoded.messageId)
        assertEquals("👍", decoded.emoji)
    }

    @Test
    fun `an empty emoji round-trips as clearing the reaction`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH) { (it + 1).toByte() }
        val decoded = ReactionSignal.decode(ReactionSignal.encode(messageId, ""))
        assertEquals("", decoded.emoji)
    }

    @Test
    fun `rejects a message id of the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReactionSignal.encode(ByteArray(4), "👍")
        }
    }

    @Test
    fun `rejects a payload too short to contain a message id`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReactionSignal.decode(ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH - 1))
        }
    }
}
