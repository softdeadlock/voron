package messenger.common.backup

import javax.crypto.AEADBadTagException
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BackupArchiveTest {

    @Test
    fun `encrypt then decrypt round-trips all sections exactly`() {
        val phrase = RecoveryPhrase.generate()
        val sections = mapOf(
            "device_identity" to Random.nextBytes(128),
            "signed_prekeys" to Random.nextBytes(400),
            "contacts" to "alice\tbob".toByteArray(),
            "messages" to "hello\tworld".toByteArray(),
        )

        val archive = BackupArchive.encrypt(sections, phrase)
        val decoded = BackupArchive.decrypt(archive, phrase)

        assertEquals(sections.keys, decoded.keys)
        for (name in sections.keys) {
            assertArrayEquals(sections.getValue(name), decoded.getValue(name), "section '$name' mismatch")
        }
    }

    @Test
    fun `an empty section round-trips too`() {
        val phrase = RecoveryPhrase.generate()
        val sections = mapOf("avatar" to ByteArray(0))

        val decoded = BackupArchive.decrypt(BackupArchive.encrypt(sections, phrase), phrase)

        assertArrayEquals(ByteArray(0), decoded.getValue("avatar"))
    }

    @Test
    fun `decrypting with the wrong phrase fails authentication instead of silently returning garbage`() {
        val sections = mapOf("device_identity" to Random.nextBytes(128))
        val archive = BackupArchive.encrypt(sections, RecoveryPhrase.generate())

        assertThrows(AEADBadTagException::class.java) {
            BackupArchive.decrypt(archive, RecoveryPhrase.generate())
        }
    }

    @Test
    fun `a corrupted archive fails authentication rather than decoding to wrong data`() {
        val phrase = RecoveryPhrase.generate()
        val archive = BackupArchive.encrypt(mapOf("device_identity" to Random.nextBytes(128)), phrase)
        archive[archive.size - 1] = (archive[archive.size - 1] + 1).toByte()

        assertThrows(AEADBadTagException::class.java) {
            BackupArchive.decrypt(archive, phrase)
        }
    }

    @Test
    fun `two encrypts of the same sections and phrase produce different ciphertext`() {
        val phrase = RecoveryPhrase.generate()
        val sections = mapOf("device_identity" to Random.nextBytes(128))

        val first = BackupArchive.encrypt(sections, phrase)
        val second = BackupArchive.encrypt(sections, phrase)

        assertEquals(false, first.contentEquals(second))
    }
}
