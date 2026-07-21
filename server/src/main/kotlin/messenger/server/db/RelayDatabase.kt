package messenger.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.sql.DataSource

/**
 * Connects to whatever Postgres instance `DATABASE_URL` points at (a Neon/Supabase/Render
 * connection string of the form `postgres[ql]://user:password@host/dbname?sslmode=require`) and
 * creates the relay's tables if they don't exist yet. Used instead of the in-memory stores
 * ([messenger.server.routing.Mailbox] etc.) whenever that env var is present, so mailboxed
 * messages and published prekeys survive a relay restart/redeploy instead of vanishing with it.
 */
object RelayDatabase {
    fun connect(rawUrl: String): DataSource {
        val normalized = if (rawUrl.startsWith("postgres://")) "postgresql://" + rawUrl.removePrefix("postgres://") else rawUrl
        val uri = URI(normalized)
        // userInfo is percent-encoded in a URL (a password with @/:/% must be escaped there), so
        // decode it back before handing it to the driver — otherwise a rotated credential with a
        // special char would authenticate with the literal %XX text and silently fail the connect.
        val (rawUser, rawPassword) = (uri.userInfo ?: "").split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val user = URLDecoder.decode(rawUser, StandardCharsets.UTF_8)
        val password = URLDecoder.decode(rawPassword, StandardCharsets.UTF_8)
        // SECURITY: this used to be `uri.query ?: "sslmode=require"` -- correct only when the URL's
        // query string is entirely absent. Any query at all (a pooler param a managed Postgres
        // provider tacks on, e.g. Neon/Supabase's own connection-string extras) replaced the
        // fallback outright, silently dropping TLS enforcement and letting pgjdbc's default
        // sslmode=prefer fall back to a cleartext connection if the server allows it. Now: keep the
        // caller's query string as-is if they set their own sslmode (they may need verify-ca with a
        // specific root cert for a given provider), otherwise always append verify-full -- not just
        // require, which encrypts but never checks the server's certificate against anything, so an
        // on-path MITM between the relay and its database is otherwise undetectable.
        val existingParams = (uri.query ?: "").split("&").filter { it.isNotEmpty() }
        val hasSslMode = existingParams.any { it.substringBefore("=") == "sslmode" }
        val query = if (hasSslMode) uri.query!! else (existingParams + "sslmode=verify-full").joinToString("&")
        val jdbcUrl = buildString {
            append("jdbc:postgresql://")
            append(uri.host)
            if (uri.port != -1) append(":${uri.port}")
            append(uri.path)
            append("?").append(query)
        }
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            maximumPoolSize = 5
            poolName = "voron-relay-db"
        }
        return HikariDataSource(config)
    }

    fun initSchema(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS mailbox_frames (
                        id BIGSERIAL PRIMARY KEY,
                        device_hex TEXT NOT NULL,
                        frame BYTEA NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_mailbox_frames_device ON mailbox_frames(device_hex, id);

                    CREATE TABLE IF NOT EXISTS prekey_bundles (
                        device_hex TEXT PRIMARY KEY,
                        dh_identity_key BYTEA NOT NULL,
                        signing_identity_key BYTEA NOT NULL,
                        signed_prekey_id INTEGER NOT NULL,
                        signed_prekey BYTEA NOT NULL,
                        signed_prekey_signature BYTEA NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    );

                    CREATE TABLE IF NOT EXISTS one_time_prekeys (
                        id BIGSERIAL PRIMARY KEY,
                        device_hex TEXT NOT NULL REFERENCES prekey_bundles(device_hex) ON DELETE CASCADE,
                        key_id INTEGER NOT NULL,
                        public_key BYTEA NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS idx_one_time_prekeys_device ON one_time_prekeys(device_hex, id);

                    CREATE TABLE IF NOT EXISTS push_endpoints (
                        device_hex TEXT PRIMARY KEY,
                        endpoint_url TEXT NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    );
                    """.trimIndent(),
                )
            }
        }
    }
}
