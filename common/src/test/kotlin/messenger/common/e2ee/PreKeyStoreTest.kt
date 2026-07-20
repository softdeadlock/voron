package messenger.common.e2ee

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreKeyStoreTest {

    /** In-memory stand-in for a real SecureStore/File-backed persistence pair. */
    private class FakeBacking {
        var bytes: ByteArray? = null
        fun load(): ByteArray? = bytes
        fun save(newBytes: ByteArray) {
            bytes = newBytes
        }
    }

    private fun identity() = DeviceIdentity.generate()

    @Test
    fun `restart with no persisted state generates a fresh signed prekey with id 1`() {
        val store = PreKeyStore(identity())
        val published = store.publishedPreKeys(0)
        assertEquals(1, published.signedPreKeyId)
    }

    @Test
    fun `a restart within the rotation window reuses the same signed prekey and id`() {
        val backing = FakeBacking()
        var clock = 0L
        val id = identity()

        val first = PreKeyStore(id, backing::load, backing::save, now = { clock })
        val firstPublished = first.publishedPreKeys(0)

        // "Restart": a brand new PreKeyStore instance reading back what the first one persisted,
        // a day later — well inside the 7-day rotation window.
        clock += 24L * 60 * 60 * 1000
        val second = PreKeyStore(id, backing::load, backing::save, now = { clock })
        val secondPublished = second.publishedPreKeys(0)

        assertEquals(firstPublished.signedPreKeyId, secondPublished.signedPreKeyId)
        assertArrayEquals(firstPublished.signedPreKey, secondPublished.signedPreKey)
        // The reused key must still complete a handshake against the peer's original id lookup.
        assertNotNull(second.signedPreKeyFor(firstPublished.signedPreKeyId))
    }

    @Test
    fun `a signed prekey older than the rotation interval is rotated to a new id on restart`() {
        val backing = FakeBacking()
        var clock = 0L
        val id = identity()

        val first = PreKeyStore(id, backing::load, backing::save, now = { clock })
        val firstPublished = first.publishedPreKeys(0)

        clock += PreKeyStore.ROTATION_INTERVAL_MILLIS + 1
        val second = PreKeyStore(id, backing::load, backing::save, now = { clock })
        val secondPublished = second.publishedPreKeys(0)

        assertTrue(secondPublished.signedPreKeyId != firstPublished.signedPreKeyId)
        assertTrue(!firstPublished.signedPreKey.contentEquals(secondPublished.signedPreKey))
    }

    @Test
    fun `the old signed prekey still answers signedPreKeyFor during its grace period`() {
        val backing = FakeBacking()
        var clock = 0L
        val id = identity()

        val first = PreKeyStore(id, backing::load, backing::save, now = { clock })
        val firstPublished = first.publishedPreKeys(0)

        clock += PreKeyStore.ROTATION_INTERVAL_MILLIS + 1
        val second = PreKeyStore(id, backing::load, backing::save, now = { clock })
        second.publishedPreKeys(0)

        // A peer that started a handshake against the just-retired key just before rotation must
        // still be able to complete it.
        val oldKey = second.signedPreKeyFor(firstPublished.signedPreKeyId)
        assertNotNull(oldKey)
        assertArrayEquals(firstPublished.signedPreKey, oldKey!!.publicKey)
    }

    @Test
    fun `the old signed prekey stops answering once its grace period has elapsed`() {
        val backing = FakeBacking()
        var clock = 0L
        val id = identity()

        val first = PreKeyStore(id, backing::load, backing::save, now = { clock })
        val firstPublished = first.publishedPreKeys(0)

        clock += PreKeyStore.ROTATION_INTERVAL_MILLIS + PreKeyStore.GRACE_PERIOD_MILLIS + 1
        val second = PreKeyStore(id, backing::load, backing::save, now = { clock })
        second.publishedPreKeys(0)

        assertNull(second.signedPreKeyFor(firstPublished.signedPreKeyId))
    }

    @Test
    fun `an unconsumed one-time prekey survives a restart`() {
        val backing = FakeBacking()
        val id = identity()

        val first = PreKeyStore(id, backing::load, backing::save)
        val published = first.publishedPreKeys(3)
        val otpId = published.oneTimePreKeys.first().id

        // "Restart" before any peer consumed the prekey a peer already fetched the public half of.
        val second = PreKeyStore(id, backing::load, backing::save)
        val recovered = second.consumeOneTimePreKey(otpId)

        assertNotNull(recovered)
        assertArrayEquals(published.oneTimePreKeys.first().publicKey, recovered!!.publicKey)
        // One-time really means one-time: a second consume (e.g. after a further restart) must fail.
        val third = PreKeyStore(id, backing::load, backing::save)
        assertNull(third.consumeOneTimePreKey(otpId))
    }

    @Test
    fun `consumed one-time prekeys are not recoverable after a restart`() {
        val backing = FakeBacking()
        val id = identity()

        val first = PreKeyStore(id, backing::load, backing::save)
        val published = first.publishedPreKeys(3)
        val otpId = published.oneTimePreKeys.first().id
        assertNotNull(first.consumeOneTimePreKey(otpId))

        val second = PreKeyStore(id, backing::load, backing::save)
        assertNull(second.consumeOneTimePreKey(otpId))
    }
}
