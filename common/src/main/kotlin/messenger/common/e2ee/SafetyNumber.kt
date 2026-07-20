package messenger.common.e2ee

import java.security.MessageDigest
import messenger.common.util.toHex

/**
 * A human-comparable fingerprint of a 1:1 conversation, independent of who
 * computes it (order of the two keys is normalized first). Not part of the
 * protocol itself — this exists purely so two people can compare it out of
 * band (in person, a phone call, another app) to catch a relay substituting
 * one of the keys, which nothing in the X3DH/ratchet exchange itself can
 * detect on its own.
 */
object SafetyNumber {
    private const val DIGIT_COUNT = 60

    /** 60 decimal digits, identical regardless of which side (A or B) calls this. */
    fun compute(keyA: ByteArray, keyB: ByteArray): String {
        val (first, second) = if (keyA.toHex() <= keyB.toHex()) keyA to keyB else keyB to keyA
        val digest = MessageDigest.getInstance("SHA-256")
        val h1 = digest.digest(first + second)
        val h2 = digest.digest(h1)
        val combined = h1 + h2
        val sb = StringBuilder(DIGIT_COUNT)
        for (b in combined) {
            if (sb.length >= DIGIT_COUNT) break
            sb.append((b.toInt() and 0xFF) % 10)
        }
        return sb.toString()
    }

    /** Splits into groups of 5 digits for display, e.g. "12345 67890 …". */
    fun format(digits: String): String = digits.chunked(5).joinToString(" ")
}
