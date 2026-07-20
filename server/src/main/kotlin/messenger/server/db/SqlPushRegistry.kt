package messenger.server.db

import javax.sql.DataSource
import messenger.server.routing.PushEndpointStore
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("messenger.server.db.SqlPushRegistry")

private const val MAX_ENDPOINT_URL_LENGTH = 2048

/**
 * Postgres-backed [PushEndpointStore], same validation rules as the in-memory
 * [messenger.server.routing.PushRegistry] — including the device-count cap, enforced by
 * [reapStaleDevices] rather than on every insert (same reasoning as [SqlMailbox]/[SqlPreKeyDirectory]).
 */
class SqlPushRegistry(private val dataSource: DataSource) : PushEndpointStore {

    override fun register(deviceHex: String, endpointUrl: String) {
        dataSource.connection.use { connection ->
            if (endpointUrl.isEmpty() || endpointUrl.length > MAX_ENDPOINT_URL_LENGTH) {
                connection.prepareStatement("DELETE FROM push_endpoints WHERE device_hex = ?").use { delete ->
                    delete.setString(1, deviceHex)
                    delete.executeUpdate()
                }
                return
            }
            connection.prepareStatement(
                """
                INSERT INTO push_endpoints (device_hex, endpoint_url, updated_at) VALUES (?, ?, now())
                ON CONFLICT (device_hex) DO UPDATE SET endpoint_url = excluded.endpoint_url, updated_at = excluded.updated_at
                """.trimIndent(),
            ).use { upsert ->
                upsert.setString(1, deviceHex)
                upsert.setString(2, endpointUrl)
                upsert.executeUpdate()
            }
        }
    }

    override fun lookup(deviceHex: String): String? {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT endpoint_url FROM push_endpoints WHERE device_hex = ?").use { select ->
                select.setString(1, deviceHex)
                select.executeQuery().use { rows -> return if (rows.next()) rows.getString(1) else null }
            }
        }
    }

    /** Deletes push registrations for the least-recently-registered devices beyond [maxDevices] distinct keys. */
    fun reapStaleDevices(maxDevices: Int = 10_000) = runStaleDeviceReap(
        dataSource, logger, "push registrations", maxDevices = maxDevices,
        deleteSql = """
            DELETE FROM push_endpoints WHERE device_hex IN (
                SELECT device_hex FROM push_endpoints ORDER BY updated_at DESC OFFSET ?
            )
        """.trimIndent(),
    )
}
