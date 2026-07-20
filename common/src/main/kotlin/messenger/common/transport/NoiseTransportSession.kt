package messenger.common.transport

import com.southernstorm.noise.protocol.CipherStatePair

/**
 * Post-handshake transport channel: authenticated encryption of opaque
 * frames (the E2E-encrypted application payload is just bytes to this layer).
 * One instance is bound to a single connection and is not thread-safe across
 * concurrent encrypt calls from multiple threads without external
 * synchronization of send order (Noise cipher state is a sequential nonce
 * counter).
 */
class NoiseTransportSession(private val ciphers: CipherStatePair) {

    fun encrypt(plaintext: ByteArray): ByteArray {
        val sender = ciphers.sender
        val out = ByteArray(plaintext.size + sender.macLength)
        val len = sender.encryptWithAd(null, plaintext, 0, out, 0, plaintext.size)
        return out.copyOf(len)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray {
        val receiver = ciphers.receiver
        val out = ByteArray(ciphertext.size)
        val len = receiver.decryptWithAd(null, ciphertext, 0, out, 0, ciphertext.size)
        return out.copyOf(len)
    }

    fun destroy() {
        ciphers.destroy()
    }
}
