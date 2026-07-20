package messenger.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.websocket.WebSockets
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import messenger.server.db.RelayDatabase
import messenger.server.db.SqlMailbox
import messenger.server.db.SqlPreKeyDirectory
import messenger.server.db.SqlPushRegistry
import messenger.server.identity.RelayIdentity
import messenger.server.routing.ConnectionRegistry
import messenger.server.routing.Mailbox
import messenger.server.routing.MailboxStore
import messenger.server.routing.PreKeyDirectory
import messenger.server.routing.PreKeyDirectoryStore
import messenger.server.routing.PushEndpointStore
import messenger.server.routing.PushNotifier
import messenger.server.routing.PushRegistry
import messenger.server.routing.TurnCredentialsIssuer
import messenger.server.routing.configureOnionNode
import messenger.server.routing.configureRouting
import org.slf4j.LoggerFactory

private val REAP_INTERVAL_MILLIS = 10 * 60 * 1000L

private val logger = LoggerFactory.getLogger("messenger.server.Application")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

/**
 * A single deployable jar serves several different roles, picked by the `ONION_ROLE` env var at
 * boot — the relay's mailbox/E2EE-routing logic (unset, the default), or a forwarding-only onion
 * hop that never touches message content. "guard"/"middle" are the original fixed 2-hop chain
 * (kept as-is so already-deployed instances need no env var changes); "hop" is the generic form
 * used for any additional hop prepended to that chain (see [messenger.android.data.OnionConfig]'s
 * `ENTRY_HOST`), where forwarding direction is config-driven instead of hardcoded by role name.
 */
fun Application.module() {
    val keyFile = File(System.getenv("MESSENGER_RELAY_KEY_PATH") ?: "relay_identity.key")

    when (System.getenv("ONION_ROLE")) {
        "guard" -> {
            val identity = RelayIdentity.loadOrCreate(keyFile)
            val nextHop = requireNotNull(System.getenv("ONION_NEXT_HOP_WS_URL")) { "ONION_NEXT_HOP_WS_URL required for guard role" }
            configureOnionNode(identity, nextHop, forwardEphemeralKey = true)
        }
        "middle" -> {
            val identity = RelayIdentity.loadOrCreate(keyFile)
            val nextHop = requireNotNull(System.getenv("ONION_NEXT_HOP_WS_URL")) { "ONION_NEXT_HOP_WS_URL required for middle role" }
            configureOnionNode(identity, nextHop, forwardEphemeralKey = false)
        }
        "hop" -> {
            val identity = RelayIdentity.loadOrCreate(keyFile)
            val nextHop = requireNotNull(System.getenv("ONION_NEXT_HOP_WS_URL")) { "ONION_NEXT_HOP_WS_URL required for hop role" }
            val forwardEphemeralKey = requireNotNull(System.getenv("ONION_FORWARD_EPHEMERAL_KEY")?.toBooleanStrictOrNull()) {
                "ONION_FORWARD_EPHEMERAL_KEY (true/false) required for hop role"
            }
            configureOnionNode(identity, nextHop, forwardEphemeralKey)
        }
        else -> {
            val databaseUrl = System.getenv("DATABASE_URL")
            val (preKeys, mailbox, pushRegistry) = if (databaseUrl != null) {
                val dataSource = RelayDatabase.connect(databaseUrl)
                RelayDatabase.initSchema(dataSource)
                logger.info("connected to Postgres — mailbox/prekeys/push registrations survive a relay restart")
                val sqlPreKeys = SqlPreKeyDirectory(dataSource)
                val sqlMailbox = SqlMailbox(dataSource)
                val sqlPushRegistry = SqlPushRegistry(dataSource)
                // SECURITY (2026-07-18 exploit hunt): each of these three stores' own device-count
                // cap only takes effect via this periodic sweep, not on every write (an aggregate
                // query per message/publish would be wasteful on the hot path) — without actually
                // scheduling it, the cap those classes implement is dead code and the tables grow
                // without bound exactly like the in-memory stores would without their LRU eviction.
                launch {
                    while (isActive) {
                        delay(REAP_INTERVAL_MILLIS)
                        runCatching { sqlMailbox.reapStaleDevices() }
                            .onFailure { logger.warn("mailbox reap failed", it) }
                        runCatching { sqlPreKeys.reapStaleDevices() }
                            .onFailure { logger.warn("prekey directory reap failed", it) }
                        runCatching { sqlPushRegistry.reapStaleDevices() }
                            .onFailure { logger.warn("push registry reap failed", it) }
                    }
                }
                Triple<PreKeyDirectoryStore, MailboxStore, PushEndpointStore>(sqlPreKeys, sqlMailbox, sqlPushRegistry)
            } else {
                logger.warn(
                    "DATABASE_URL not set — using in-memory storage; mailboxed messages, prekeys, and " +
                        "push registrations will all be lost on the next restart/redeploy",
                )
                Triple<PreKeyDirectoryStore, MailboxStore, PushEndpointStore>(PreKeyDirectory(), Mailbox(), PushRegistry())
            }
            // Both unset (the default) means calls fall back to STUN-only rather than fail --
            // see TurnCredentialsIssuer's doc for why the API secret lives here, never in the app.
            val meteredAppName = System.getenv("METERED_APP_NAME")
            val meteredSecretKey = System.getenv("METERED_SECRET_KEY")
            val turnCredentialsIssuer = if (meteredAppName != null && meteredSecretKey != null) {
                TurnCredentialsIssuer(meteredAppName, meteredSecretKey)
            } else {
                logger.warn("METERED_APP_NAME/METERED_SECRET_KEY not set — calls will be STUN-only (no TURN fallback)")
                null
            }
            configureMessengerServer(keyFile, preKeys, mailbox, pushRegistry, turnCredentialsIssuer)
        }
    }
}

fun Application.configureMessengerServer(
    relayIdentityKeyFile: File,
    preKeys: PreKeyDirectoryStore,
    mailbox: MailboxStore,
    pushRegistry: PushEndpointStore,
    turnCredentialsIssuer: TurnCredentialsIssuer? = null,
) {
    val relayIdentity = RelayIdentity.loadOrCreate(relayIdentityKeyFile)
    val registry = ConnectionRegistry()
    val pushNotifier = PushNotifier()

    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
        // Ktor's own default is Long.MAX_VALUE — effectively unbounded. Legitimate traffic never
        // needs anywhere close to this: file chunks are capped at FileSignal.CHUNK_SIZE (16 KiB)
        // plus E2EE/envelope overhead, and even a maximally-sized prekey publish (now bounded to
        // MAX_ONE_TIME_PREKEYS, see PreKeyCodec) is well under 64 KiB. Without a cap, one client
        // sending a single multi-gigabyte frame forces the server to buffer it before any of our
        // own code — including the frame-size checks in the codecs — ever gets a chance to run.
        maxFrameSize = 1L * 1024 * 1024
    }

    configureRouting(relayIdentity, registry, preKeys, mailbox, pushRegistry, pushNotifier, turnCredentialsIssuer)
}
