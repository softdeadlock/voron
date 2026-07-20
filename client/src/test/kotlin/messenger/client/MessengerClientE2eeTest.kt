package messenger.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import messenger.common.client.MessengerClient
import messenger.common.e2ee.DeviceIdentity
import messenger.server.configureMessengerServer
import messenger.server.routing.Mailbox
import messenger.server.routing.PreKeyDirectory
import messenger.server.routing.PushRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Exercises the whole stack over real localhost sockets: a real relay
 * process, two real [MessengerClient]s doing Noise_IK transport handshakes,
 * prekey publish/fetch, X3DH session establishment and the symmetric
 * ratchet — nothing is faked or short-circuited.
 */
class MessengerClientE2eeTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `two clients establish an E2EE session and exchange messages through a real relay`() {
        val relayPort = freePort()
        val keyFile = Files.createTempDirectory("relay-e2e-test").resolve("identity.key").toFile()

        val relayEngine = embeddedServer(Netty, port = relayPort, host = "127.0.0.1") {
            configureMessengerServer(keyFile, PreKeyDirectory(), Mailbox(), PushRegistry())
        }
        relayEngine.start(wait = false)

        val clientScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val httpClient = HttpClient(CIO) { install(WebSockets) }

        try {
            runBlocking {
                val serverInfo = httpClient.get("http://127.0.0.1:$relayPort/v1/server-info").bodyAsText()
                val relayStaticPublicKey = Base64.getDecoder().decode(
                    Regex(""""staticPublicKey":"([^"]+)"""").find(serverInfo)!!.groupValues[1],
                )

                val aliceIdentity = DeviceIdentity.generate()
                val bobIdentity = DeviceIdentity.generate()
                val alice = MessengerClient(aliceIdentity, httpClient)
                val bob = MessengerClient(bobIdentity, httpClient)

                val relayUrl = "ws://127.0.0.1:$relayPort/v1/connect"
                alice.connect(relayUrl, relayStaticPublicKey, clientScope)
                bob.connect(relayUrl, relayStaticPublicKey, clientScope)
                bob.publishPreKeys()

                val bobReceived = CompletableDeferred<String>()
                val bobCollector = clientScope.launch {
                    bob.incomingMessages.collect { bobReceived.complete(String(it.plaintext)) }
                }

                withTimeout(5_000) {
                    // Bob's PUBLISH_PREKEYS frame is in flight asynchronously; retry the
                    // first send until the relay's directory has caught up.
                    while (true) {
                        try {
                            alice.sendMessage(bobIdentity.dhIdentityPublicKey, "hello bob, this is alice".toByteArray())
                            break
                        } catch (e: NoSuchElementException) {
                            delay(100)
                        }
                    }
                }

                assertEquals("hello bob, this is alice", withTimeout(5_000) { bobReceived.await() })
                bobCollector.cancel()

                val aliceReceived = CompletableDeferred<String>()
                val aliceCollector = clientScope.launch {
                    alice.incomingMessages.collect { aliceReceived.complete(String(it.plaintext)) }
                }
                bob.sendMessage(aliceIdentity.dhIdentityPublicKey, "hi alice!".toByteArray())
                assertEquals("hi alice!", withTimeout(5_000) { aliceReceived.await() })
                aliceCollector.cancel()
            }
        } finally {
            httpClient.close()
            clientScope.cancel()
            relayEngine.stop(500, 1000)
        }
    }
}
