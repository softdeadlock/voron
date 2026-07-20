package messenger.common.transport

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NoiseIkHandshakeTest {

    @Test
    fun `handshake completes and derives a working transport session`() {
        val relayKeyPair = NoiseStaticKeyPair.generate()
        val deviceKeyPair = NoiseStaticKeyPair.generate()

        val initiator = NoiseIkInitiatorHandshake(deviceKeyPair, relayKeyPair.publicKey)
        val responder = NoiseIkResponderHandshake(relayKeyPair)

        val message1 = initiator.createMessage1()
        val message1Payload = responder.consumeMessage1(message1)
        assertArrayEquals(ByteArray(0), message1Payload)
        assertArrayEquals(deviceKeyPair.publicKey, responder.remoteStaticPublicKey)

        val (message2, responderSession) = responder.createMessage2()
        val initiatorSession = initiator.consumeMessage2(message2)

        val clientToRelay = initiatorSession.encrypt("hello relay".toByteArray())
        assertArrayEquals("hello relay".toByteArray(), responderSession.decrypt(clientToRelay))

        val relayToClient = responderSession.encrypt("hello device".toByteArray())
        assertArrayEquals("hello device".toByteArray(), initiatorSession.decrypt(relayToClient))
    }

    @Test
    fun `responder rejects a message from an unexpected relay identity`() {
        val realRelayKeyPair = NoiseStaticKeyPair.generate()
        val impostorRelayKeyPair = NoiseStaticKeyPair.generate()
        val deviceKeyPair = NoiseStaticKeyPair.generate()

        // Client pins the real relay's key but the message is intercepted and
        // answered by an impostor claiming to be a different relay identity.
        val initiator = NoiseIkInitiatorHandshake(deviceKeyPair, realRelayKeyPair.publicKey)
        val impostorResponder = NoiseIkResponderHandshake(impostorRelayKeyPair)

        val message1 = initiator.createMessage1()

        assertThrows(javax.crypto.BadPaddingException::class.java) {
            impostorResponder.consumeMessage1(message1)
        }
    }
}
