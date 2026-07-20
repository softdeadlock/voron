package messenger.common.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 signatures over 32-byte raw keys, used to sign prekeys so a
 * fetched bundle can be authenticated against the sender's long-term
 * signing identity. The JDK wraps Ed25519 keys in X.509/PKCS#8; since the
 * ASN.1 headers are fixed-length for this algorithm we splice the raw
 * 32-byte key in and out for a compact wire format.
 */
object Ed25519Signatures {
    const val KEY_LENGTH = 32
    const val SIGNATURE_LENGTH = 64

    // Fixed ASN.1 prefixes for Ed25519 SubjectPublicKeyInfo / PrivateKeyInfo.
    private val PUBLIC_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )
    private val PRIVATE_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
        0x04, 0x22, 0x04, 0x20,
    )

    class SigningKeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPair(): SigningKeyPair {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pubEnc = kp.public.encoded
        val prvEnc = kp.private.encoded
        return SigningKeyPair(
            privateKey = prvEnc.copyOfRange(prvEnc.size - KEY_LENGTH, prvEnc.size),
            publicKey = pubEnc.copyOfRange(pubEnc.size - KEY_LENGTH, pubEnc.size),
        )
    }

    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == KEY_LENGTH) { "private key must be $KEY_LENGTH bytes" }
        val key = KeyFactory.getInstance("Ed25519")
            .generatePrivate(PKCS8EncodedKeySpec(PRIVATE_PREFIX + privateKey))
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(key)
        sig.update(message)
        return sig.sign()
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        require(publicKey.size == KEY_LENGTH) { "public key must be $KEY_LENGTH bytes" }
        val key = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(PUBLIC_PREFIX + publicKey))
        val sig = Signature.getInstance("Ed25519")
        sig.initVerify(key)
        sig.update(message)
        return try {
            sig.verify(signature)
        } catch (e: java.security.SignatureException) {
            false
        }
    }
}
