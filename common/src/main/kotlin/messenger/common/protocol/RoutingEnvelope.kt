package messenger.common.protocol

/**
 * Wire format for a frame carried over an already-authenticated Noise_IK
 * transport session: a 32-byte peer static-key header followed by an
 * opaque payload.
 *
 * Client -> relay: header is the *recipient's* static key, payload is the
 * (still E2E-encrypted, relay-opaque) application ciphertext.
 * Relay -> client: header is the *sender's* static key, same payload,
 * forwarded unmodified. The relay only ever reads the header.
 */
object RoutingEnvelope {
    const val PEER_KEY_LENGTH = 32

    fun encode(peerStaticPublicKey: ByteArray, payload: ByteArray): ByteArray {
        require(peerStaticPublicKey.size == PEER_KEY_LENGTH) { "peer static key must be $PEER_KEY_LENGTH bytes" }
        val out = ByteArray(PEER_KEY_LENGTH + payload.size)
        peerStaticPublicKey.copyInto(out)
        payload.copyInto(out, destinationOffset = PEER_KEY_LENGTH)
        return out
    }

    data class Decoded(val peerStaticPublicKey: ByteArray, val payload: ByteArray)

    fun decode(frame: ByteArray): Decoded {
        require(frame.size >= PEER_KEY_LENGTH) { "frame too short to contain a routing header" }
        return Decoded(
            peerStaticPublicKey = frame.copyOfRange(0, PEER_KEY_LENGTH),
            payload = frame.copyOfRange(PEER_KEY_LENGTH, frame.size),
        )
    }
}
