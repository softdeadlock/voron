package messenger.server.routing

import java.util.ArrayDeque
import java.util.Collections
import messenger.common.e2ee.PreKeyBundle
import messenger.common.e2ee.PublishedOneTimePreKey
import messenger.common.e2ee.PublishedPreKeys

/**
 * The relay's prekey directory: it stores each device's published public
 * prekeys and hands out one one-time prekey per fetch, popping it so it is
 * never reused. Everything here is public key material — the relay never
 * holds any private key or plaintext.
 *
 * In-memory only (lost on relay restart) — used when no `DATABASE_URL` is configured; otherwise
 * [messenger.server.db.SqlPreKeyDirectory] is used instead. Devices republish their whole bundle
 * on every connect, so this is self-healing either way as long as the recipient reconnects before
 * someone tries to start a session with them.
 *
 * SECURITY (2026-07-18 exploit hunt): LRU-capped at [maxDevices] distinct keys, same reasoning as
 * [Mailbox]/[PushRegistry] — publishing under a device key only requires completing a Noise
 * handshake with a freshly-generated keypair, something the relay never gatekeeps, so without a
 * cap here an attacker could loop "generate keypair, connect, publish" indefinitely and grow this
 * map — and the JVM heap behind it — without bound. This was the one store in the file that had
 * been left uncapped; Mailbox and PushRegistry already had this exact protection.
 */
class PreKeyDirectory(maxDevices: Int = 10_000) : PreKeyDirectoryStore {

    private class Entry(val published: PublishedPreKeys) {
        val oneTimePreKeys = ArrayDeque(published.oneTimePreKeys)
    }

    private val entries = Collections.synchronizedMap(lruCappedMap<String, Entry>(maxDevices))

    override fun publish(deviceKeyHex: String, published: PublishedPreKeys) {
        entries[deviceKeyHex] = Entry(published)
    }

    /** Builds a fetchable bundle for [deviceKeyHex], consuming one one-time prekey if any remain. */
    override fun fetch(deviceKeyHex: String): PreKeyBundle? {
        val entry = entries[deviceKeyHex] ?: return null
        val oneTime: PublishedOneTimePreKey? = synchronized(entry.oneTimePreKeys) {
            entry.oneTimePreKeys.pollFirst()
        }
        val published = entry.published
        return PreKeyBundle(
            dhIdentityKey = published.dhIdentityKey,
            signingIdentityKey = published.signingIdentityKey,
            signedPreKeyId = published.signedPreKeyId,
            signedPreKey = published.signedPreKey,
            signedPreKeySignature = published.signedPreKeySignature,
            oneTimePreKeyId = oneTime?.id ?: PreKeyBundle.NO_ONE_TIME_PREKEY,
            oneTimePreKey = oneTime?.publicKey,
        )
    }
}
