package messenger.common.e2ee

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PqKemTest {
    @Test
    fun `encapsulated secret matches decapsulated secret`() {
        val keyPair = PqKem.generateKeyPair()
        val encapsulation = PqKem.encapsulate(keyPair.publicKey)
        val decapsulated = PqKem.decapsulate(keyPair.privateKey, encapsulation.ciphertext)
        assertArrayEquals(encapsulation.sharedSecret, decapsulated)
    }

    @Test
    fun `different key pairs produce different shared secrets`() {
        val keyPairA = PqKem.generateKeyPair()
        val keyPairB = PqKem.generateKeyPair()
        val encapsulationA = PqKem.encapsulate(keyPairA.publicKey)
        val encapsulationB = PqKem.encapsulate(keyPairB.publicKey)
        assertFalse(encapsulationA.sharedSecret.contentEquals(encapsulationB.sharedSecret))
    }
}
