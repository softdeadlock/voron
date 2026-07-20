package messenger.server.routing

import messenger.common.e2ee.PreKeyBundle
import messenger.common.e2ee.PublishedPreKeys

/**
 * Storage contracts for the relay's three pieces of durable-ish state. Each has an in-memory
 * implementation ([Mailbox], [PreKeyDirectory], [PushRegistry] — lost on restart, used when no
 * database is configured) and a Postgres-backed one (`messenger.server.db.Sql*`, used when
 * `DATABASE_URL` is set) — [Routes.kt][configureRouting] talks to these interfaces only, so it
 * doesn't care which backing is wired up.
 */
interface MailboxStore {
    fun enqueue(deviceHex: String, frame: ByteArray)
    fun drain(deviceHex: String): List<ByteArray>
}

interface PreKeyDirectoryStore {
    fun publish(deviceKeyHex: String, published: PublishedPreKeys)
    fun fetch(deviceKeyHex: String): PreKeyBundle?
}

interface PushEndpointStore {
    fun register(deviceHex: String, endpointUrl: String)
    fun lookup(deviceHex: String): String?
}
