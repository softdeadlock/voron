package messenger.common.e2ee

import java.security.SecureRandom
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters

/**
 * ML-KEM-1024 (FIPS 203), the post-quantum half of the hybrid X3DH handshake -- same parameter
 * set Signal's PQXDH uses. No JDK-provided implementation exists (unlike Curve25519/Ed25519,
 * which the rest of e2ee/ gets for free from the JDK's own providers), so this wraps
 * BouncyCastle's lightweight crypto API directly rather than its heavier JCE/Provider layer.
 */
object PqKem {
    private val PARAMS = MLKEMParameters.ml_kem_1024

    data class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

    /** [ciphertext] goes on the wire to the decapsulating party; [sharedSecret] never does. */
    data class Encapsulation(val ciphertext: ByteArray, val sharedSecret: ByteArray)

    fun generateKeyPair(): KeyPair {
        val generator = MLKEMKeyPairGenerator()
        generator.init(MLKEMKeyGenerationParameters(SecureRandom(), PARAMS))
        val pair = generator.generateKeyPair()
        val publicKey = pair.public as MLKEMPublicKeyParameters
        val privateKey = pair.private as MLKEMPrivateKeyParameters
        return KeyPair(publicKey.encoded, privateKey.encoded)
    }

    fun encapsulate(publicKeyBytes: ByteArray): Encapsulation {
        val publicKey = MLKEMPublicKeyParameters(PARAMS, publicKeyBytes)
        val generator = MLKEMGenerator(SecureRandom())
        val encapsulated = generator.generateEncapsulated(publicKey)
        return Encapsulation(ciphertext = encapsulated.encapsulation, sharedSecret = encapsulated.secret)
    }

    fun decapsulate(privateKeyBytes: ByteArray, ciphertext: ByteArray): ByteArray {
        val privateKey = MLKEMPrivateKeyParameters(PARAMS, privateKeyBytes)
        val extractor = MLKEMExtractor(privateKey)
        return extractor.extractSecret(ciphertext)
    }
}
