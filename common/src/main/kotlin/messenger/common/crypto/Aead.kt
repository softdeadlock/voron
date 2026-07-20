package messenger.common.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20-Poly1305 AEAD via the JDK provider. Keys are 32 bytes, nonces
 * 12 bytes. Every message key produced by the ratchet is used exactly
 * once, so a fixed all-zero nonce is safe here (the safety argument is
 * key-uniqueness, not nonce-uniqueness).
 */
object Aead {
    private const val TRANSFORM = "ChaCha20-Poly1305"
    private const val KEY_ALG = "ChaCha20"
    const val KEY_LENGTH = 32
    const val NONCE_LENGTH = 12

    val ZERO_NONCE: ByteArray get() = ByteArray(NONCE_LENGTH)

    /**
     * A nonce built from a monotonically increasing per-direction counter (big-endian, right-
     * aligned) — safe as long as the same key is never reused across two different counter
     * sequences. Used by the onion circuit layers, where one key serves an entire connection's
     * worth of frames instead of a single message (unlike the ratchet's one-key-per-message keys,
     * which lean on the all-zero [ZERO_NONCE] instead).
     */
    fun counterNonce(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH)
        for (i in 0 until 8) {
            nonce[NONCE_LENGTH - 1 - i] = ((counter shr (8 * i)) and 0xFF).toByte()
        }
        return nonce
    }

    fun encrypt(key: ByteArray, nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_LENGTH) { "key must be $KEY_LENGTH bytes" }
        require(nonce.size == NONCE_LENGTH) { "nonce must be $NONCE_LENGTH bytes" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, KEY_ALG), IvParameterSpec(nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == KEY_LENGTH) { "key must be $KEY_LENGTH bytes" }
        require(nonce.size == NONCE_LENGTH) { "nonce must be $NONCE_LENGTH bytes" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, KEY_ALG), IvParameterSpec(nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext)
    }
}
