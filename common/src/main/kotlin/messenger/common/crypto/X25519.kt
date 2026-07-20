package messenger.common.crypto

import com.southernstorm.noise.protocol.Noise
import messenger.common.transport.NoiseStaticKeyPair

/**
 * Raw X25519 (Curve25519) Diffie-Hellman on 32-byte keys, delegated to the
 * audited noise-java Curve25519 implementation. Kept separate from the
 * Noise handshake layer so the same 32-byte device key can serve both as
 * the transport static key and as the X3DH DH-identity key.
 */
object X25519 {
    const val KEY_LENGTH = 32

    fun generateKeyPair(): NoiseStaticKeyPair = NoiseStaticKeyPair.generate()

    /**
     * Computes DH(privateKey, publicKey) as a 32-byte shared secret.
     *
     * Rejects an all-zero result: per RFC 7748 §6.1, a malicious peer can send a
     * low-order public key that forces the shared secret to be all-zero regardless
     * of our own private key, which would let a forged "peer" pin a known,
     * attacker-controlled session key. noise-java doesn't validate this itself.
     */
    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_LENGTH) { "private key must be $KEY_LENGTH bytes" }
        require(publicKey.size == KEY_LENGTH) { "public key must be $KEY_LENGTH bytes" }
        val priv = Noise.createDH("25519")
        val pub = Noise.createDH("25519")
        try {
            priv.setPrivateKey(privateKey, 0)
            pub.setPublicKey(publicKey, 0)
            val shared = ByteArray(priv.sharedKeyLength)
            priv.calculate(shared, 0, pub)
            check(shared.any { it != 0.toByte() }) { "X25519 produced an all-zero shared secret (low-order public key)" }
            return shared
        } finally {
            priv.destroy()
            pub.destroy()
        }
    }
}
