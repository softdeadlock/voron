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

    /**
     * 60 decimal digits, identical regardless of which side (A or B) calls this. [signingKeyA]/
     * [signingKeyB] are each side's Ed25519 signing identity — optional (null before a signing key
     * has actually been pinned for that peer, see [E2eeManager.pinnedSigningIdentityKey]) since a
     * fresh conversation may not have one yet, but including them once available is what makes this
     * number actually catch a signing-key substitution: [keyA]/[keyB] alone only cover the DH
     * identity keys, so a relay swapping *just* the signing key a peer's bundle is signed with (see
     * X3dhSigningKeySpoofExploitTest, which documents exactly this gap) left the safety number
     * unchanged even though the two sides no longer agree on who's allowed to sign that peer's
     * prekeys — out-of-band verification is supposed to catch precisely this kind of substitution.
     */
    fun compute(keyA: ByteArray, keyB: ByteArray, signingKeyA: ByteArray? = null, signingKeyB: ByteArray? = null): String {
        val aFirst = keyA.toHex() <= keyB.toHex()
        val (dh1, dh2) = if (aFirst) keyA to keyB else keyB to keyA
        val (sign1, sign2) = if (aFirst) signingKeyA to signingKeyB else signingKeyB to signingKeyA
        val digest = MessageDigest.getInstance("SHA-256")
        val h1 = digest.digest(dh1 + (sign1 ?: ByteArray(0)) + dh2 + (sign2 ?: ByteArray(0)))
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
