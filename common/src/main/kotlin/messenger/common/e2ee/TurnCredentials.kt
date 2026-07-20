package messenger.common.e2ee

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import messenger.common.protocol.TransportFrame

/**
 * A short-lived TURN username/password minted by the relay on request (see
 * [messenger.common.client.MessengerClient.fetchTurnCredentials]), never a credential the client
 * itself holds or ships baked in — the relay is the only party that ever holds the actual TURN
 * provider's API secret. Not E2E-encrypted: this is transport-provider configuration, not message
 * content, and only the relay (which minted it) and this device need to see it.
 */
class TurnCredentials(val username: String, val password: String)

/** Wire codec for [TransportFrame.TURN_CREDENTIALS_RESULT] — see that constant's doc for the exact layout. */
object TurnCredentialsCodec {
    fun encodeFound(credentials: TurnCredentials): ByteArray {
        val usernameBytes = credentials.username.toByteArray(StandardCharsets.UTF_8)
        val passwordBytes = credentials.password.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + 2 + usernameBytes.size + 2 + passwordBytes.size)
        buffer.put(TransportFrame.RESULT_FOUND)
        buffer.putShort(usernameBytes.size.toShort())
        buffer.put(usernameBytes)
        buffer.putShort(passwordBytes.size.toShort())
        buffer.put(passwordBytes)
        return buffer.array()
    }

    fun encodeNotFound(): ByteArray = byteArrayOf(TransportFrame.RESULT_NOT_FOUND)

    /** Returns null for [TransportFrame.RESULT_NOT_FOUND] or a malformed/truncated body — both treated as "unavailable" by callers. */
    fun decode(body: ByteArray): TurnCredentials? {
        if (body.isEmpty() || body[0] != TransportFrame.RESULT_FOUND) return null
        return try {
            val buffer = ByteBuffer.wrap(body, 1, body.size - 1)
            val usernameLength = buffer.short.toInt() and 0xFFFF
            val username = ByteArray(usernameLength).also { buffer.get(it) }
            val passwordLength = buffer.short.toInt() and 0xFFFF
            val password = ByteArray(passwordLength).also { buffer.get(it) }
            TurnCredentials(String(username, StandardCharsets.UTF_8), String(password, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }
}
