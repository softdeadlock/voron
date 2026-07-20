package messenger.server.routing

import messenger.common.e2ee.DeviceIdentity
import messenger.common.e2ee.PreKeyStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import messenger.common.e2ee.PreKeyBundle
import messenger.common.util.toHex

class PreKeyDirectoryTest {

    @Test
    fun `fetch on an unpublished device returns null`() {
        val directory = PreKeyDirectory()
        assertNull(directory.fetch(ByteArray(32).toHex()))
    }

    @Test
    fun `one-time prekeys are consumed and never repeated`() {
        val directory = PreKeyDirectory()
        val identity = DeviceIdentity.generate()
        val store = PreKeyStore(identity)
        val published = store.publishedPreKeys(oneTimeCount = 2)
        val deviceHex = identity.dhIdentityPublicKey.toHex()
        directory.publish(deviceHex, published)

        val first = directory.fetch(deviceHex)!!
        val second = directory.fetch(deviceHex)!!
        val third = directory.fetch(deviceHex)!!

        assertNotEquals(first.oneTimePreKeyId, PreKeyBundle.NO_ONE_TIME_PREKEY)
        assertNotEquals(second.oneTimePreKeyId, PreKeyBundle.NO_ONE_TIME_PREKEY)
        assertNotEquals(first.oneTimePreKeyId, second.oneTimePreKeyId)

        // Pool of 2 is exhausted; third fetch still returns the signed prekey, no one-time.
        assertEquals(PreKeyBundle.NO_ONE_TIME_PREKEY, third.oneTimePreKeyId)
        assertNull(third.oneTimePreKey)
        assertArrayEquals(published.signedPreKey, third.signedPreKey)
    }
}
