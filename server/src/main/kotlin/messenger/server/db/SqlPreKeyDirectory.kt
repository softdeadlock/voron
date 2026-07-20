package messenger.server.db

import javax.sql.DataSource
import messenger.common.e2ee.PreKeyBundle
import messenger.common.e2ee.PublishedPreKeys
import messenger.server.routing.PreKeyDirectoryStore
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("messenger.server.db.SqlPreKeyDirectory")

/**
 * Postgres-backed [PreKeyDirectoryStore]. Devices republish their whole prekey bundle on every
 * connect (see `ConnectionManager.openConnection`), so unlike the mailbox this isn't the only copy
 * of anything irreplaceable — but persisting it means a first-contact handshake started right
 * after a relay restart, before the recipient happens to reconnect, still finds their prekeys.
 *
 * [reapStaleDevices] bounds this the same way the in-memory [messenger.server.routing.PreKeyDirectory]
 * is LRU-capped — publishing under a device key only needs a fresh Noise handshake, which the relay
 * never gatekeeps, so without *some* cap an attacker could grow this table without bound.
 */
class SqlPreKeyDirectory(private val dataSource: DataSource) : PreKeyDirectoryStore {

    override fun publish(deviceKeyHex: String, published: PublishedPreKeys) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO prekey_bundles
                        (device_hex, dh_identity_key, signing_identity_key, signed_prekey_id, signed_prekey, signed_prekey_signature, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (device_hex) DO UPDATE SET
                        dh_identity_key = excluded.dh_identity_key,
                        signing_identity_key = excluded.signing_identity_key,
                        signed_prekey_id = excluded.signed_prekey_id,
                        signed_prekey = excluded.signed_prekey,
                        signed_prekey_signature = excluded.signed_prekey_signature,
                        updated_at = excluded.updated_at
                    """.trimIndent(),
                ).use { upsert ->
                    upsert.setString(1, deviceKeyHex)
                    upsert.setBytes(2, published.dhIdentityKey)
                    upsert.setBytes(3, published.signingIdentityKey)
                    upsert.setInt(4, published.signedPreKeyId)
                    upsert.setBytes(5, published.signedPreKey)
                    upsert.setBytes(6, published.signedPreKeySignature)
                    upsert.executeUpdate()
                }

                connection.prepareStatement("DELETE FROM one_time_prekeys WHERE device_hex = ?").use { delete ->
                    delete.setString(1, deviceKeyHex)
                    delete.executeUpdate()
                }

                connection.prepareStatement(
                    "INSERT INTO one_time_prekeys (device_hex, key_id, public_key) VALUES (?, ?, ?)",
                ).use { insert ->
                    for (oneTime in published.oneTimePreKeys) {
                        insert.setString(1, deviceKeyHex)
                        insert.setInt(2, oneTime.id)
                        insert.setBytes(3, oneTime.publicKey)
                        insert.addBatch()
                    }
                    insert.executeBatch()
                }

                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    /** Builds a fetchable bundle for [deviceKeyHex], atomically popping one one-time prekey if any remain. */
    override fun fetch(deviceKeyHex: String): PreKeyBundle? {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val bundleRow = connection.prepareStatement(
                    "SELECT dh_identity_key, signing_identity_key, signed_prekey_id, signed_prekey, signed_prekey_signature " +
                        "FROM prekey_bundles WHERE device_hex = ?",
                ).use { select ->
                    select.setString(1, deviceKeyHex)
                    select.executeQuery().use { rows ->
                        if (!rows.next()) null
                        else BundleRow(
                            dhIdentityKey = rows.getBytes(1),
                            signingIdentityKey = rows.getBytes(2),
                            signedPreKeyId = rows.getInt(3),
                            signedPreKey = rows.getBytes(4),
                            signedPreKeySignature = rows.getBytes(5),
                        )
                    }
                } ?: run { connection.commit(); return null }

                val oneTime = connection.prepareStatement(
                    """
                    WITH picked AS (
                        DELETE FROM one_time_prekeys WHERE id = (
                            SELECT id FROM one_time_prekeys WHERE device_hex = ? ORDER BY id LIMIT 1
                        )
                        RETURNING key_id, public_key
                    )
                    SELECT key_id, public_key FROM picked
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, deviceKeyHex)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getInt(1) to rows.getBytes(2) else null
                    }
                }

                connection.commit()

                return PreKeyBundle(
                    dhIdentityKey = bundleRow.dhIdentityKey,
                    signingIdentityKey = bundleRow.signingIdentityKey,
                    signedPreKeyId = bundleRow.signedPreKeyId,
                    signedPreKey = bundleRow.signedPreKey,
                    signedPreKeySignature = bundleRow.signedPreKeySignature,
                    oneTimePreKeyId = oneTime?.first ?: PreKeyBundle.NO_ONE_TIME_PREKEY,
                    oneTimePreKey = oneTime?.second,
                )
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    private class BundleRow(
        val dhIdentityKey: ByteArray,
        val signingIdentityKey: ByteArray,
        val signedPreKeyId: Int,
        val signedPreKey: ByteArray,
        val signedPreKeySignature: ByteArray,
    )

    /** Deletes prekey bundles (and their one-time prekeys, via ON DELETE CASCADE) for the least-recently-published devices beyond [maxDevices] distinct keys. */
    fun reapStaleDevices(maxDevices: Int = 10_000) = runStaleDeviceReap(
        dataSource, logger, "prekey bundles", maxDevices = maxDevices,
        deleteSql = """
            DELETE FROM prekey_bundles WHERE device_hex IN (
                SELECT device_hex FROM prekey_bundles ORDER BY updated_at DESC OFFSET ?
            )
        """.trimIndent(),
    )
}
