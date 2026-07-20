package messenger.server.db

import javax.sql.DataSource
import org.slf4j.Logger

/**
 * Shared boilerplate behind every SQL store's `reapStaleDevices`: run a single parameterized
 * DELETE (the caller's [deleteSql] must have exactly one `?`, bound to [maxDevices]) and log if it
 * actually removed anything. [deleteSql] itself differs per store (different table, and whether it
 * needs a GROUP BY to collapse multiple rows per device first) — only the "run it, log it" shape
 * is common.
 */
internal fun runStaleDeviceReap(dataSource: DataSource, logger: Logger, label: String, deleteSql: String, maxDevices: Int) {
    dataSource.connection.use { connection ->
        connection.prepareStatement(deleteSql).use { statement ->
            statement.setInt(1, maxDevices)
            val deleted = statement.executeUpdate()
            if (deleted > 0) logger.info("reaped $label for stale devices beyond the $maxDevices cap")
        }
    }
}
