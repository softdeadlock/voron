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

class RouteCallSignalTest {

    /** Builds a Connection with a real (looped-back) transport session so encrypt/decrypt work. */
    private fun connectionFor(device: NoiseStaticKeyPair, relay: NoiseStaticKeyPair, capacity: Int = 64): Connection {
        val initiator = NoiseIkInitiatorHandshake(device, relay.publicKey)
        val responder = NoiseIkResponderHandshake(relay)
        responder.consumeMessage1(initiator.createMessage1())
        val (message2, relaySide) = responder.createMessage2()
        initiator.consumeMessage2(message2)
        return Connection(device.publicKey.toHex(), relaySide, outgoingCapacity = capacity)
    }

    @Test
    fun `call signal to an online recipient is delivered live, tagged CALL_SIGNAL`() {
        val relayKey = NoiseStaticKeyPair.generate()
        val sender = connectionFor(NoiseStaticKeyPair.generate(), relayKey)
        val recipientDevice = NoiseStaticKeyPair.generate()
        val recipient = connectionFor(recipientDevice, relayKey)

        val registry = ConnectionRegistry()
        registry.register(recipient)

        routeCallSignal(
            sender,
            RoutingEnvelope.encode(recipientDevice.publicKey, "ring".toByteArray()),
            registry,
            Mailbox(),
            PushRegistry(),
            PushNotifier(),
        )

        val live = recipient.outgoing.tryReceive().getOrNull()!!
        val decoded = TransportFrame.decode(live)
        assertEquals(TransportFrame.CALL_SIGNAL, decoded.type)
        val envelope = RoutingEnvelope.decode(decoded.body)
        assertArrayEquals("ring".toByteArray(), envelope.payload)
    }

    @Test
    fun `call signal to an offline recipient with no push endpoint gets an immediate CALL_UNAVAILABLE reply, not a mailbox entry`() {
        val relayKey = NoiseStaticKeyPair.generate()
        val sender = connectionFor(NoiseStaticKeyPair.generate(), relayKey)
        val recipientKey = NoiseStaticKeyPair.generate().publicKey

        val registry = ConnectionRegistry() // nobody registered: recipient is offline
        val mailbox = Mailbox()

        routeCallSignal(
            sender,
            RoutingEnvelope.encode(recipientKey, "ring".toByteArray()),
            registry,
            mailbox,
            PushRegistry(),
            PushNotifier(),
        )

        val reply = sender.outgoing.tryReceive().getOrNull()!!
        val decoded = TransportFrame.decode(reply)
        assertEquals(TransportFrame.CALL_UNAVAILABLE, decoded.type)
        assertArrayEquals(recipientKey, decoded.body)
        assertEquals(0, mailbox.drain(recipientKey.toHex()).size)
    }

    @Test
    fun `call signal to an offline recipient with a registered push endpoint is mailboxed instead of rejected`() {
        val relayKey = NoiseStaticKeyPair.generate()
        val sender = connectionFor(NoiseStaticKeyPair.generate(), relayKey)
        val recipientKey = NoiseStaticKeyPair.generate().publicKey

        val registry = ConnectionRegistry() // nobody registered: recipient is offline
        val mailbox = Mailbox()
        val pushRegistry = PushRegistry()
        pushRegistry.register(recipientKey.toHex(), "https://push.example/endpoint-id")

        routeCallSignal(
            sender,
            RoutingEnvelope.encode(recipientKey, "ring".toByteArray()),
            registry,
            mailbox,
            pushRegistry,
            PushNotifier(),
        )

        // No immediate CALL_UNAVAILABLE — the caller should keep ringing while the push wakes the callee.
        assertEquals(null, sender.outgoing.tryReceive().getOrNull())
        val queued = mailbox.drain(recipientKey.toHex())
        assertEquals(1, queued.size)
        val queuedEnvelope = RoutingEnvelope.decode(TransportFrame.decode(queued[0]).body)
        assertArrayEquals("ring".toByteArray(), queuedEnvelope.payload)
    }
}
