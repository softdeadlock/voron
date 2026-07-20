package messenger.common.transport

import com.southernstorm.noise.protocol.HandshakeState

/**
 * Noise_IK_25519_ChaChaPoly_BLAKE2b handshake for the client<->relay
 * transport channel (WireGuard-style: 1-RTT, initiator already knows the
 * responder's static public key out-of-band).
 *
 * This secures the connection between a client and the relay it is talking
 * to. It is independent of, and sits underneath, whatever end-to-end
 * encryption (1:1 Noise ratchet or MLS) protects the actual message
 * content — the relay terminates this layer and only ever sees opaque
 * application frames.
 */
private const val PROTOCOL_NAME = "Noise_IK_25519_ChaChaPoly_BLAKE2b"

/** Scratch buffer size for handshake messages; payload here is tiny (empty or key-id hints). */
private const val HANDSHAKE_BUFFER_SIZE = 4096

class NoiseIkInitiatorHandshake(
    localStaticKeyPair: NoiseStaticKeyPair,
    remoteStaticPublicKey: ByteArray,
) {
    private val handshake = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR).apply {
        localKeyPair.setPrivateKey(localStaticKeyPair.privateKey, 0)
        remotePublicKey.setPublicKey(remoteStaticPublicKey, 0)
        start()
    }

    /** First and only message the initiator sends; carries the encrypted local static key. */
    fun createMessage1(payload: ByteArray = ByteArray(0)): ByteArray {
        check(handshake.action == HandshakeState.WRITE_MESSAGE) { "handshake not ready to write message 1" }
        val buffer = ByteArray(payload.size + HANDSHAKE_BUFFER_SIZE)
        val len = handshake.writeMessage(buffer, 0, payload, 0, payload.size)
        return buffer.copyOf(len)
    }

    /** Consumes the responder's reply and completes the handshake. */
    fun consumeMessage2(message: ByteArray): NoiseTransportSession {
        check(handshake.action == HandshakeState.READ_MESSAGE) { "handshake not expecting message 2" }
        val payload = ByteArray(message.size)
        handshake.readMessage(message, 0, message.size, payload, 0)
        check(handshake.action == HandshakeState.SPLIT) { "handshake did not complete after message 2" }
        return NoiseTransportSession(handshake.split())
    }
}

class NoiseIkResponderHandshake(
    localStaticKeyPair: NoiseStaticKeyPair,
) {
    private val handshake = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER).apply {
        localKeyPair.setPrivateKey(localStaticKeyPair.privateKey, 0)
        start()
    }

    /** The initiator's authenticated static public key, available only after [consumeMessage1]. */
    lateinit var remoteStaticPublicKey: ByteArray
        private set

    /** Consumes the initiator's first message, authenticating their static key. */
    fun consumeMessage1(message: ByteArray): ByteArray {
        check(handshake.action == HandshakeState.READ_MESSAGE) { "handshake not expecting message 1" }
        val payload = ByteArray(message.size)
        val len = handshake.readMessage(message, 0, message.size, payload, 0)
        val remote = handshake.remotePublicKey ?: error("Noise_IK responder did not learn a remote static key")
        remoteStaticPublicKey = ByteArray(remote.publicKeyLength).also { remote.getPublicKey(it, 0) }
        return payload.copyOf(len)
    }

    /** Sends the reply and completes the handshake. Call after [consumeMessage1]. */
    fun createMessage2(payload: ByteArray = ByteArray(0)): Pair<ByteArray, NoiseTransportSession> {
        check(handshake.action == HandshakeState.WRITE_MESSAGE) { "handshake not ready to write message 2" }
        val buffer = ByteArray(payload.size + HANDSHAKE_BUFFER_SIZE)
        val len = handshake.writeMessage(buffer, 0, payload, 0, payload.size)
        check(handshake.action == HandshakeState.SPLIT) { "handshake did not complete after message 2" }
        return buffer.copyOf(len) to NoiseTransportSession(handshake.split())
    }
}
