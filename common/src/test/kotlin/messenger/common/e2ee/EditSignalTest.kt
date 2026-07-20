package messenger.common.e2ee

import messenger.common.client.ApplicationMessage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EditSignalTest {

    @Test
    fun `encode-decode round-trips the new text`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH) { it.toByte() }
        val decoded = EditSignal.decode(EditSignal.encode(messageId, "actually, let's meet at 6pm"))
        assertArrayEquals(messageId, decoded.messageId)
        assertEquals("actually, let's meet at 6pm", decoded.newText)
    }

    @Test
    fun `rejects a message id of the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) {
            EditSignal.encode(ByteArray(4), "hi")
        }
    }

    @Test
    fun `rejects a payload too short to contain a message id`() {
        assertThrows(IllegalArgumentException::class.java) {
            EditSignal.decode(ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH - 1))
        }
    }
}
