package messenger.common.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (RFC 5869) over HMAC-SHA256. Used to turn raw DH secrets into
 * uniformly-random key material and to run the symmetric ratchet.
 *
 * This is the standard, spec-defined construction — not a bespoke KDF.
 */
object Hkdf {
    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        // RFC 5869: if salt is empty, it is set to HashLen zero bytes.
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        mac.init(SecretKeySpec(effectiveSalt, HMAC))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * HASH_LEN) { "cannot expand to more than ${255 * HASH_LEN} bytes" }
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(prk, HMAC))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val toCopy = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, toCopy)
            offset += toCopy
            counter++
        }
        return out
    }

    /** Convenience: extract-then-expand with an empty salt. */
    fun derive(ikm: ByteArray, info: String, length: Int): ByteArray =
        expand(extract(ByteArray(0), ikm), info.toByteArray(), length)

    /** Single-block HMAC-SHA256, used by the per-message ratchet steps. */
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(data)
    }
}
