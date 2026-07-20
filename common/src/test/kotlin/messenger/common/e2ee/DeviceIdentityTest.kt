package messenger.common.e2ee

import java.nio.file.Files
import messenger.common.crypto.Ed25519Signatures
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceIdentityTest {

    @Test
    fun `loadOrCreate persists a new identity and reloads the same one`() {
        val file = Files.createTempDirectory("device-identity-test").resolve("identity.key").toFile()
        assertTrue(!file.exists())

        val first = DeviceIdentity.loadOrCreate(file)
        assertTrue(file.exists())

        val second = DeviceIdentity.loadOrCreate(file)

        assertArrayEquals(first.dhIdentityPublicKey, second.dhIdentityPublicKey)
        assertArrayEquals(first.dhIdentity.privateKey, second.dhIdentity.privateKey)
        assertArrayEquals(first.signingIdentityPublicKey, second.signingIdentityPublicKey)
        assertArrayEquals(first.signingIdentity.privateKey, second.signingIdentity.privateKey)

        // The reloaded signing key must still produce verifiable signatures.
        val message = "prekey material".toByteArray()
        val signature = Ed25519Signatures.sign(second.signingIdentity.privateKey, message)
        assertTrue(Ed25519Signatures.verify(second.signingIdentityPublicKey, message, signature))
    }

    @Test
    fun `two different files produce two independent identities`() {
        val dir = Files.createTempDirectory("device-identity-test-2")
        val a = DeviceIdentity.loadOrCreate(dir.resolve("a.key").toFile())
        val b = DeviceIdentity.loadOrCreate(dir.resolve("b.key").toFile())
        assertTrue(!a.dhIdentityPublicKey.contentEquals(b.dhIdentityPublicKey))
    }
}
