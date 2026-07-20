package messenger.common.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RoutingEnvelopeTest {

    @Test
    fun `round trips peer key and payload`() {
        val key = ByteArray(32) { it.toByte() }
        val payload = "opaque ciphertext".toByteArray()

        val frame = RoutingEnvelope.encode(key, payload)
        val decoded = RoutingEnvelope.decode(frame)

        assertArrayEquals(key, decoded.peerStaticPublicKey)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `rejects a frame too short to contain a header`() {
        assertThrows(IllegalArgumentException::class.java) {
            RoutingEnvelope.decode(ByteArray(10))
        }
    }
}
