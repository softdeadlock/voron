package messenger.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import messenger.common.client.MessengerClient
import messenger.common.e2ee.DeviceIdentity
import messenger.common.util.toHex
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("messenger.client.Main")

/**
 * Standalone client process: connects to a relay, publishes prekeys, and
 * serves a local test-UI page on [uiPort] so a browser tab can act as one
 * device (see [messenger.client.ui.ClientUiServer]).
 *
 * Usage: PORT=9001 RELAY_HOST=127.0.0.1 RELAY_PORT=8080 gradle :client:run
 * Or against a TLS relay: RELAY_HOST=voron-relay.onrender.com RELAY_TLS=true gradle :client:run
 */
fun main() = runBlocking {
    val relayHost = System.getenv("RELAY_HOST") ?: "127.0.0.1"
    val relayTls = System.getenv("RELAY_TLS")?.toBooleanStrictOrNull() ?: false
    val relayPort = System.getenv("RELAY_PORT")?.toIntOrNull() ?: if (relayTls) 443 else 8080
    val uiPort = System.getenv("PORT")?.toIntOrNull() ?: 9001
    val httpScheme = if (relayTls) "https" else "http"
    val wsScheme = if (relayTls) "wss" else "ws"
    val relayAuthority = if (relayTls) relayHost else "$relayHost:$relayPort"

    val httpClient = HttpClient(CIO) { install(WebSockets) }

    val serverInfoBody = httpClient.get("$httpScheme://$relayAuthority/v1/server-info").bodyAsText()
    val relayStaticPublicKey = Base64.getDecoder().decode(
        Regex(""""staticPublicKey":"([^"]+)"""").find(serverInfoBody)!!.groupValues[1],
    )

    // A dedicated multi-threaded scope for the client's read/write loops:
    // ClientUiServer.start() below blocks this function's own runBlocking
    // thread for the lifetime of the process, which would starve any
    // coroutine scheduled on that same (single-threaded) dispatcher.
    val clientScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Defaults to a per-UI-port file so running several demo instances from
    // the same working directory (e.g. two `gradle :client:run` with
    // different PORT) doesn't have them collide on one identity file.
    val identityKeyFile = File(System.getenv("DEVICE_IDENTITY_KEY_PATH") ?: "device_identity_$uiPort.key")
    val identity = DeviceIdentity.loadOrCreate(identityKeyFile)
    val displayName = System.getenv("DISPLAY_NAME") ?: identity.dhIdentityPublicKey.toHex().take(8)
    val client = MessengerClient(identity, httpClient, displayName)
    client.connect("$wsScheme://$relayAuthority/v1/connect", relayStaticPublicKey, clientScope)
    client.publishPreKeys()
    FileTransferHarness.installFromEnv(client, clientScope)

    logger.info("device ready: ${identity.dhIdentityPublicKey.toHex()}")

    messenger.client.ui.ClientUiServer(client, identity).start(uiPort)
}
