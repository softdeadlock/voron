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

class RouteOverflowTest {

    /** Builds a Connection with a real (looped-back) transport session so encrypt/decrypt work. */
    private fun connectionFor(device: NoiseStaticKeyPair, relay: NoiseStaticKeyPair, capacity: Int): Connection {
        val initiator = NoiseIkInitiatorHandshake(device, relay.publicKey)
        val responder = NoiseIkResponderHandshake(relay)
        responder.consumeMessage1(initiator.createMessage1())
        val (message2, relaySide) = responder.createMessage2()
        initiator.consumeMessage2(message2) // client side discarded; relay side is what Connection holds
        return Connection(device.publicKey.toHex(), relaySide, outgoingCapacity = capacity)
    }

    @Test
    fun `frames that overflow a live recipient channel land in the mailbox instead of vanishing`() {
        val relayKey = NoiseStaticKeyPair.generate()
        val sender = connectionFor(NoiseStaticKeyPair.generate(), relayKey, capacity = 64)
        val recipientDevice = NoiseStaticKeyPair.generate()
        // Tiny capacity and nobody draining: second frame must overflow.
        val recipient = connectionFor(recipientDevice, relayKey, capacity = 1)

        val registry = ConnectionRegistry()
        registry.register(recipient)
        val mailbox = Mailbox()

        fun envelopeTo(text: String) = RoutingEnvelope.encode(recipientDevice.publicKey, text.toByteArray())

        routeMessage(sender, envelopeTo("fits"), registry, mailbox, PushRegistry(), PushNotifier())
        routeMessage(sender, envelopeTo("overflows"), registry, mailbox, PushRegistry(), PushNotifier())

        // First frame is in the live channel...
        val live = recipient.outgoing.tryReceive().getOrNull()!!
        val liveEnvelope = RoutingEnvelope.decode(TransportFrame.decode(live).body)
        assertArrayEquals("fits".toByteArray(), liveEnvelope.payload)

        // ...second went to the mailbox rather than being silently dropped.
        val queued = mailbox.drain(recipientDevice.publicKey.toHex())
        assertEquals(1, queued.size)
        val queuedEnvelope = RoutingEnvelope.decode(TransportFrame.decode(queued[0]).body)
        assertArrayEquals("overflows".toByteArray(), queuedEnvelope.payload)
    }

    @Test
    fun `frames to a closed recipient channel are mailboxed`() {
        val relayKey = NoiseStaticKeyPair.generate()
        val sender = connectionFor(NoiseStaticKeyPair.generate(), relayKey, capacity = 64)
        val recipientDevice = NoiseStaticKeyPair.generate()
        val recipient = connectionFor(recipientDevice, relayKey, capacity = 8)

        val registry = ConnectionRegistry()
        registry.register(recipient)
        recipient.outgoing.close()

        val mailbox = Mailbox()
        routeMessage(
            sender,
            RoutingEnvelope.encode(recipientDevice.publicKey, "late".toByteArray()),
            registry,
            mailbox,
            PushRegistry(),
            PushNotifier(),
        )

        val queued = mailbox.drain(recipientDevice.publicKey.toHex())
        assertEquals(1, queued.size)
    }
}
