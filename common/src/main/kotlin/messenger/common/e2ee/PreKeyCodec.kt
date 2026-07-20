package messenger.common.e2ee

import java.nio.ByteBuffer

/** Compact binary serialization for prekey material exchanged with the relay's directory. */
object PreKeyCodec {
    // Clients publish ~20 by default (see PreKeyStore.publishedPreKeys) — this is generous
    // headroom, not a tuned limit, purely to bound the allocation below.
    private const val MAX_ONE_TIME_PREKEYS = 1000

    /** The `dhIdentityKey/signingIdentityKey/signedPreKeyId/signedPreKey/signedPreKeySignature` header shared by every published-prekeys/bundle wire shape. */
    private class IdentityHeader(
        val dhIdentityKey: ByteArray,
        val signingIdentityKey: ByteArray,
        val signedPreKeyId: Int,
        val signedPreKey: ByteArray,
        val signedPreKeySignature: ByteArray,
    ) {
        fun encodeInto(buffer: ByteBuffer) {
            buffer.put(dhIdentityKey)
            buffer.put(signingIdentityKey)
            buffer.putInt(signedPreKeyId)
            buffer.put(signedPreKey)
            buffer.put(signedPreKeySignature)
        }

        companion object {
            const val LENGTH = 32 + 32 + 4 + 32 + 64

            fun decodeFrom(buffer: ByteBuffer) = IdentityHeader(
                dhIdentityKey = ByteArray(32).also { buffer.get(it) },
                signingIdentityKey = ByteArray(32).also { buffer.get(it) },
                signedPreKeyId = buffer.int,
                signedPreKey = ByteArray(32).also { buffer.get(it) },
                signedPreKeySignature = ByteArray(64).also { buffer.get(it) },
            )
        }
    }

    fun encodePublished(published: PublishedPreKeys): ByteArray {
        val buffer = ByteBuffer.allocate(
            IdentityHeader.LENGTH + 4 + published.oneTimePreKeys.size * (4 + 32),
        )
        IdentityHeader(
            published.dhIdentityKey, published.signingIdentityKey,
            published.signedPreKeyId, published.signedPreKey, published.signedPreKeySignature,
        ).encodeInto(buffer)
        buffer.putInt(published.oneTimePreKeys.size)
        for (otp in published.oneTimePreKeys) {
            buffer.putInt(otp.id)
            buffer.put(otp.publicKey)
        }
        return buffer.array()
    }

    fun decodePublished(bytes: ByteArray): PublishedPreKeys {
        val buffer = ByteBuffer.wrap(bytes)
        val header = IdentityHeader.decodeFrom(buffer)
        val otpCount = buffer.int
        // SECURITY: otpCount comes straight off the wire from a client — an unauthenticated
        // publisher could set it to e.g. Int.MAX_VALUE, and `ArrayList(otpCount)` allocates that
        // capacity immediately, before a single byte of the (attacker-controlled, arbitrarily
        // short) body is actually read. That's an OutOfMemoryError from one small malicious
        // frame, an Error (not Exception) that the server's `catch (e: Exception)` around
        // decodePublished does NOT catch — a real, one-packet DoS against the whole relay
        // process. Bounding it here, in the shared codec, protects every caller.
        require(otpCount in 0..MAX_ONE_TIME_PREKEYS) { "one-time prekey count out of range: $otpCount" }
        val otps = ArrayList<PublishedOneTimePreKey>(otpCount)
        repeat(otpCount) {
            val id = buffer.int
            val key = ByteArray(32).also { buffer.get(it) }
            otps += PublishedOneTimePreKey(id, key)
        }
        return PublishedPreKeys(
            header.dhIdentityKey, header.signingIdentityKey,
            header.signedPreKeyId, header.signedPreKey, header.signedPreKeySignature, otps,
        )
    }

    fun encodeBundle(bundle: PreKeyBundle): ByteArray {
        val hasOtp = bundle.oneTimePreKey != null
        val buffer = ByteBuffer.allocate(IdentityHeader.LENGTH + 4 + 1 + if (hasOtp) 32 else 0)
        IdentityHeader(
            bundle.dhIdentityKey, bundle.signingIdentityKey,
            bundle.signedPreKeyId, bundle.signedPreKey, bundle.signedPreKeySignature,
        ).encodeInto(buffer)
        buffer.putInt(bundle.oneTimePreKeyId)
        buffer.put(if (hasOtp) 1.toByte() else 0.toByte())
        if (hasOtp) buffer.put(bundle.oneTimePreKey)
        return buffer.array()
    }

    fun decodeBundle(bytes: ByteArray): PreKeyBundle {
        val buffer = ByteBuffer.wrap(bytes)
        val header = IdentityHeader.decodeFrom(buffer)
        val opkId = buffer.int
        val hasOtp = buffer.get().toInt() != 0
        val otp = if (hasOtp) ByteArray(32).also { buffer.get(it) } else null
        return PreKeyBundle(
            header.dhIdentityKey, header.signingIdentityKey,
            header.signedPreKeyId, header.signedPreKey, header.signedPreKeySignature, opkId, otp,
        )
    }
}
