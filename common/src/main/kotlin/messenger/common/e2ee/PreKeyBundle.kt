package messenger.common.e2ee

/**
 * The public prekey material a peer needs to open an asynchronous session
 * with a device it has never spoken to (the X3DH "prekey bundle").
 *
 * [oneTimePreKey] is optional: the relay hands out a fresh one-time prekey
 * per fetch and removes it, giving the very first message a one-time DH.
 * When the pool is exhausted the bundle is served without one (the signed
 * prekey still provides forward secrecy, just without the one-time layer).
 */
class PreKeyBundle(
    val dhIdentityKey: ByteArray,
    val signingIdentityKey: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKey: ByteArray,
    val signedPreKeySignature: ByteArray,
    val oneTimePreKeyId: Int,
    val oneTimePreKey: ByteArray?,
) {
    init {
        require(dhIdentityKey.size == 32)
        require(signingIdentityKey.size == 32)
        require(signedPreKey.size == 32)
        require(signedPreKeySignature.size == 64)
        require((oneTimePreKeyId == NO_ONE_TIME_PREKEY) == (oneTimePreKey == null)) {
            "oneTimePreKey and its id must be present or absent together"
        }
        require(oneTimePreKey == null || oneTimePreKey.size == 32)
    }

    companion object {
        const val NO_ONE_TIME_PREKEY = -1
    }
}
