package messenger.common.backup

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Generates and derives a symmetric key from a human-writable recovery phrase for
 * [BackupArchive]. Words come from the standard BIP39 English wordlist (2048 words, ~11 bits
 * each, bundled as a classpath resource) so [WORD_COUNT] words carry well over 128 bits of
 * entropy — comfortably more than an offline PBKDF2 brute-force needs to be infeasible, without
 * requiring the user to remember or safely store an actual 256-bit key.
 */
object RecoveryPhrase {
    const val WORD_COUNT = 12

    // OWASP's 2023 password-storage cheat sheet recommendation for PBKDF2-HMAC-SHA256. The phrase
    // itself already has far more entropy than a human password, so this is defense in depth
    // against a stolen archive file rather than the primary line of defense.
    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_LENGTH_BITS = 256

    private val words: List<String> by lazy {
        val stream = requireNotNull(RecoveryPhrase::class.java.getResourceAsStream("/messenger/common/backup/bip39-english.txt")) {
            "bip39-english.txt resource missing from common's classpath"
        }
        val loaded = stream.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
        check(loaded.size == 2048) { "expected 2048 BIP39 words, found ${loaded.size}" }
        loaded
    }

    /** Picks [WORD_COUNT] words uniformly at random (with replacement, like a real BIP39 seed phrase can repeat words). */
    fun generate(): List<String> {
        val random = SecureRandom()
        return List(WORD_COUNT) { words[random.nextInt(words.size)] }
    }

    /** Normalizes user-typed words the same way on generate and on restore, so extra whitespace or different casing from manual retyping still matches. */
    fun normalize(phrase: List<String>): List<String> = phrase.map { it.trim().lowercase() }

    /** Derives a 256-bit key from [phrase] and [salt] — the same phrase with a different salt (a fresh one per [BackupArchive.encrypt] call) always derives a different key, so two backups never share key material even if the user reuses a phrase by mistake. */
    fun deriveKey(phrase: List<String>, salt: ByteArray): ByteArray {
        val joined = normalize(phrase).joinToString(" ")
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(joined.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return factory.generateSecret(spec).encoded
    }
}
