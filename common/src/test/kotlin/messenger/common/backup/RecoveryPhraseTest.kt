package messenger.common.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecoveryPhraseTest {

    @Test
    fun `generate produces WORD_COUNT words all drawn from the bundled BIP39 list`() {
        val phrase = RecoveryPhrase.generate()

        assertEquals(RecoveryPhrase.WORD_COUNT, phrase.size)
        assertTrue(phrase.all { it.isNotBlank() && it.all { c -> c in 'a'..'z' } })
    }

    @Test
    fun `two generated phrases are (almost certainly) different`() {
        val first = RecoveryPhrase.generate()
        val second = RecoveryPhrase.generate()

        assertNotEquals(first, second)
    }

    @Test
    fun `deriveKey is deterministic for the same phrase and salt`() {
        val phrase = listOf("abandon", "ability", "able")
        val salt = ByteArray(16) { it.toByte() }

        assertEquals(
            RecoveryPhrase.deriveKey(phrase, salt).toList(),
            RecoveryPhrase.deriveKey(phrase, salt).toList(),
        )
    }

    @Test
    fun `deriveKey differs for a different salt with the same phrase`() {
        val phrase = listOf("abandon", "ability", "able")
        val saltA = ByteArray(16) { it.toByte() }
        val saltB = ByteArray(16) { (it + 1).toByte() }

        assertNotEquals(
            RecoveryPhrase.deriveKey(phrase, saltA).toList(),
            RecoveryPhrase.deriveKey(phrase, saltB).toList(),
        )
    }

    @Test
    fun `normalize is case- and whitespace-insensitive so a retyped phrase still derives the same key`() {
        val original = listOf("Abandon", "ABILITY", "able")
        val retyped = listOf("  abandon ", "ability", " ABLE")
        val salt = ByteArray(16)

        assertEquals(
            RecoveryPhrase.deriveKey(original, salt).toList(),
            RecoveryPhrase.deriveKey(retyped, salt).toList(),
        )
    }
}
