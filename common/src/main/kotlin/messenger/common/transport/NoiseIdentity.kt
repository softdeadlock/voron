package messenger.common.transport

import com.southernstorm.noise.protocol.Noise

/**
 * A long-term Curve25519 keypair used as a Noise static identity
 * (device identity for clients, relay identity for the server).
 */
class NoiseStaticKeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {

    init {
        require(privateKey.size == 32) { "private key must be 32 bytes" }
        require(publicKey.size == 32) { "public key must be 32 bytes" }
    }

    companion object {
        fun generate(): NoiseStaticKeyPair {
            val dh = Noise.createDH("25519")
            dh.generateKeyPair()
            val priv = ByteArray(dh.privateKeyLength)
            val pub = ByteArray(dh.publicKeyLength)
            dh.getPrivateKey(priv, 0)
            dh.getPublicKey(pub, 0)
            dh.destroy()
            return NoiseStaticKeyPair(priv, pub)
        }

        /** Rederives the public key from a stored private key (e.g. loaded from disk). */
        fun fromPrivateKey(privateKey: ByteArray): NoiseStaticKeyPair {
            val dh = Noise.createDH("25519")
            dh.setPrivateKey(privateKey, 0)
            val pub = ByteArray(dh.publicKeyLength)
            dh.getPublicKey(pub, 0)
            dh.destroy()
            return NoiseStaticKeyPair(privateKey, pub)
        }
    }
}
