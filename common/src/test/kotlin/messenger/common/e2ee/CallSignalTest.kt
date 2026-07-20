package messenger.common.e2ee

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallSignalTest {

    @Test
    fun `encode then decode round-trips callId, kind, and payload`() {
        val callId = UUID.randomUUID()
        val encoded = CallSignal.encode(callId, CallSignal.RING, "v=0\r\no=- sdp offer body")

        val decoded = CallSignal.decode(encoded)

        assertEquals(callId, decoded.callId)
        assertEquals(CallSignal.RING, decoded.kind)
        assertEquals("v=0\r\no=- sdp offer body", decoded.payload)
    }

    @Test
    fun `round-trips an empty payload such as a hangup with no reason string`() {
        val callId = UUID.randomUUID()
        val encoded = CallSignal.encode(callId, CallSignal.HANGUP, "")

        val decoded = CallSignal.decode(encoded)

        assertEquals(callId, decoded.callId)
        assertEquals(CallSignal.HANGUP, decoded.kind)
        assertEquals("", decoded.payload)
    }
}
