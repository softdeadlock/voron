package messenger.server.routing

import kotlinx.coroutines.channels.Channel
import messenger.common.transport.NoiseTransportSession

/**
 * A connected, handshake-complete device. [outgoing] carries plaintext
 * routing-envelope frames destined for this device; a single writer
 * coroutine per connection drains it, encrypts with [transportSession]
 * and pushes it onto the WebSocket, so nonce ordering on the transport
 * cipher is never raced.
 */
class Connection(
    val staticPublicKeyHex: String,
    val transportSession: NoiseTransportSession,
    outgoingCapacity: Int = 64,
) {
    val outgoing = Channel<ByteArray>(capacity = outgoingCapacity)

    fun close() {
        outgoing.close()
        transportSession.destroy()
    }
}
