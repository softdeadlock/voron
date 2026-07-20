package messenger.common.e2ee

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TurnCredentialsCodecTest {

    @Test
    fun `found credentials round-trip through encode and decode`() {
        val credentials = TurnCredentials("1721500000:voron", "aBase64LookingPassword==")
        val decoded = TurnCredentialsCodec.decode(TurnCredentialsCodec.encodeFound(credentials))

        assertEquals(credentials.username, decoded?.username)
        assertEquals(credentials.password, decoded?.password)
    }

    @Test
    fun `not-found encodes to a single byte and decodes to null`() {
        val encoded = TurnCredentialsCodec.encodeNotFound()
        assertEquals(1, encoded.size)
        assertNull(TurnCredentialsCodec.decode(encoded))
    }

    @Test
    fun `empty or truncated bodies decode to null instead of throwing`() {
        assertNull(TurnCredentialsCodec.decode(ByteArray(0)))
        assertNull(TurnCredentialsCodec.decode(byteArrayOf(1, 0, 5))) // claims a 5-byte username but has none
    }
}
