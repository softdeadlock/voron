package messenger.common.backup

import java.nio.ByteBuffer
import java.security.SecureRandom
import messenger.common.crypto.Aead

/**
 * Encrypts/decrypts a named set of byte blobs (device identity, prekey store state, contacts,
 * message history, ...) as one portable archive, protected by a key derived from a
 * [RecoveryPhrase] rather than the device Keystore that normally guards this data on Android —
 * Keystore keys are deliberately non-exportable, so a real "survive losing this phone" backup has
 * no choice but to re-encrypt everything under a key the user can carry themselves (as the phrase).
 *
 * Wire format: `[1 byte version][16 byte salt][12 byte nonce][ChaCha20-Poly1305 ciphertext]`,
 * where the plaintext is a simple concatenation of `[2-byte name length][name utf8][4-byte data
 * length][data]` per section — this class only ever sees opaque bytes; each section's real byte
 * layout is owned entirely by its own class ([messenger.common.e2ee.DeviceIdentity],
 * [messenger.common.e2ee.PreKeyStore]) or whatever Android store persists it.
 */
object BackupArchive {
    private const val FORMAT_VERSION: Byte = 1
    private const val SALT_LENGTH = 16
    private val EMPTY_AAD = ByteArray(0)

    fun encrypt(sections: Map<String, ByteArray>, phrase: List<String>): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val nonce = ByteArray(Aead.NONCE_LENGTH).also { random.nextBytes(it) }
        val key = RecoveryPhrase.deriveKey(phrase, salt)

        val ciphertext = Aead.encrypt(key, nonce, EMPTY_AAD, encodeSections(sections))

        val out = ByteBuffer.allocate(1 + SALT_LENGTH + Aead.NONCE_LENGTH + ciphertext.size)
        out.put(FORMAT_VERSION)
        out.put(salt)
        out.put(nonce)
        out.put(ciphertext)
        return out.array()
    }

    /** @throws javax.crypto.AEADBadTagException (via [Aead.decrypt]) if [phrase] is wrong or [archive] is corrupt/tampered. */
    fun decrypt(archive: ByteArray, phrase: List<String>): Map<String, ByteArray> {
        val buffer = ByteBuffer.wrap(archive)
        require(buffer.get() == FORMAT_VERSION) { "unsupported backup archive format" }
        val salt = ByteArray(SALT_LENGTH).also { buffer.get(it) }
        val nonce = ByteArray(Aead.NONCE_LENGTH).also { buffer.get(it) }
        val ciphertext = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val key = RecoveryPhrase.deriveKey(phrase, salt)
        return decodeSections(Aead.decrypt(key, nonce, EMPTY_AAD, ciphertext))
    }

    private fun encodeSections(sections: Map<String, ByteArray>): ByteArray {
        val size = sections.entries.sumOf { (name, data) -> 2 + name.toByteArray(Charsets.UTF_8).size + 4 + data.size }
        val buffer = ByteBuffer.allocate(size)
        for ((name, data) in sections) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            buffer.putShort(nameBytes.size.toShort())
            buffer.put(nameBytes)
            buffer.putInt(data.size)
            buffer.put(data)
        }
        return buffer.array()
    }

    private fun decodeSections(plaintext: ByteArray): Map<String, ByteArray> {
        val buffer = ByteBuffer.wrap(plaintext)
        val sections = LinkedHashMap<String, ByteArray>()
        while (buffer.hasRemaining()) {
            val nameLength = buffer.short.toInt() and 0xFFFF
            val nameBytes = ByteArray(nameLength).also { buffer.get(it) }
            val dataLength = buffer.int
            val data = ByteArray(dataLength).also { buffer.get(it) }
            sections[String(nameBytes, Charsets.UTF_8)] = data
        }
        return sections
    }
}
