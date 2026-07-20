package messenger.server.routing

import messenger.common.protocol.RoutingEnvelope
import messenger.common.protocol.TransportFrame
import messenger.common.transport.NoiseIkInitiatorHandshake
import messenger.common.transport.NoiseIkResponderHandshake
import messenger.common.transport.NoiseStaticKeyPair
import messenger.common.util.toHex
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RouteFileTransferTest {

    private fun connectionFor(device: NoiseStaticKeyPair, relay: NoiseStaticKeyPair, capacity: Int = 64): Connection {
        val initiator = NoiseIkInitiatorHandshake(device, relay.publicKey)
        val responder = NoiseIkResponderHandshake(relay)
        responder.consumeMessage1(initiator.createMessage1())
        val (message2, relaySide) = responder.createMessage2()
        initiator.consumeMessage2(message2)
        return Connection(device.publicKey.toHex(), relaySide, outgoingCapacity = capacity)
    }

    @Test
    fun `file transfer to an online recipient is delivered live, tagged FILE_TRANSFER`() {
        val relayKey = NoiseStaticKeyPair.generate()
        val sender = connectionFor(NoiseStaticKeyPair.generate(), relayKey)
        val recipientDevice = NoiseStaticKeyPair.generate()
        val recipient = connectionFor(recipientDevice, relayKey)

        val registry = ConnectionRegistry()
        registry.register(recipient)

        routeFileTransfer(sender, RoutingEnvelope.encode(recipientDevice.publicKey, "chunk".toByteArray()), registry)

        val live = recipient.outgoing.tryReceive().getOrNull()!!
        val decoded = TransportFrame.decode(live)
        assertEquals(TransportFrame.FILE_TRANSFER, decoded.type)
        assertArrayEquals("chunk".toByteArray(), RoutingEnvelope.decode(decoded.body).payload)
    }

    @Test
    fun `file transfer to an offline recipient replies FILE_UNAVAILABLE and is never stored`() {
        val relayKey = NoiseStaticKeyPair.generate()
        val sender = connectionFor(NoiseStaticKeyPair.generate(), relayKey)
        val recipientKey = NoiseStaticKeyPair.generate().publicKey

        val registry = ConnectionRegistry() // recipient offline

        routeFileTransfer(sender, RoutingEnvelope.encode(recipientKey, "chunk".toByteArray()), registry)

        val reply = sender.outgoing.tryReceive().getOrNull()!!
        val decoded = TransportFrame.decode(reply)
        assertEquals(TransportFrame.FILE_UNAVAILABLE, decoded.type)
        assertArrayEquals(recipientKey, decoded.body)
    }
}
