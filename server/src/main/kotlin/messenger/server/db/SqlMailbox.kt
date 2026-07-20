package messenger.server.db

import javax.sql.DataSource
import messenger.server.routing.MailboxStore
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("messenger.server.db.SqlMailbox")

/**
 * Postgres-backed [MailboxStore]: same behavior/caps as the in-memory [messenger.server.routing.Mailbox],
 * but frames queued for an offline device survive a relay restart instead of being lost with it —
 * the entire point of adding a database, since "message sent while the recipient hadn't opened the
 * app in days" is exactly the case a redeploy in the meantime used to silently eat.
 *
 * [reapStaleDevices] enforces the same overall-device cap [Mailbox] had (LRU eviction), but as a
 * periodic sweep rather than an on-insert check — an aggregate query per message would be wasteful
 * on the hot path, and unlike the in-memory map this doesn't grow the process's heap while it waits.
 */
class SqlMailbox(private val dataSource: DataSource, private val maxPerDevice: Int = 200) : MailboxStore {

    override fun enqueue(deviceHex: String, frame: ByteArray) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO mailbox_frames (device_hex, frame) VALUES (?, ?)").use { insert ->
                insert.setString(1, deviceHex)
                insert.setBytes(2, frame)
                insert.executeUpdate()
            }
            connection.prepareStatement(
                """
                DELETE FROM mailbox_frames
                WHERE device_hex = ? AND id NOT IN (
                    SELECT id FROM mailbox_frames WHERE device_hex = ? ORDER BY id DESC LIMIT ?
                )
                """.trimIndent(),
            ).use { trim ->
                trim.setString(1, deviceHex)
                trim.setString(2, deviceHex)
                trim.setInt(3, maxPerDevice)
                trim.executeUpdate()
            }
        }
    }

    /** Atomically removes and returns everything queued for [deviceHex], oldest first. */
    override fun drain(deviceHex: String): List<ByteArray> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                WITH removed AS (
                    DELETE FROM mailbox_frames WHERE device_hex = ? RETURNING id, frame
                )
                SELECT frame FROM removed ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, deviceHex)
                statement.executeQuery().use { rows ->
                    val frames = mutableListOf<ByteArray>()
                    while (rows.next()) frames.add(rows.getBytes("frame"))
                    return frames
                }
            }
        }
    }

    /** Evicts whole queues for the least-recently-touched devices beyond [maxDevices] distinct keys. */
    fun reapStaleDevices(maxDevices: Int = 10_000) = runStaleDeviceReap(
        dataSource, logger, "mailbox frames", maxDevices = maxDevices,
        deleteSql = """
            DELETE FROM mailbox_frames WHERE device_hex IN (
                SELECT device_hex FROM (
                    SELECT device_hex, MAX(created_at) AS last_touched
                    FROM mailbox_frames
                    GROUP BY device_hex
                    ORDER BY last_touched DESC
                    OFFSET ?
                ) stale
            )
        """.trimIndent(),
    )
}
