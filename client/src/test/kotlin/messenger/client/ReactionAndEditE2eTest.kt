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
import messenger.common.util.hexToByteArray
import messenger.server.configureMessengerServer
import messenger.server.routing.Mailbox
import messenger.server.routing.PreKeyDirectory
import messenger.server.routing.PushRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** End-to-end coverage for reactions/edits (Phase 3): two real [MessengerClient]s through a real relay. */
class ReactionAndEditE2eTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `a reaction and an edit both round-trip through a real relay`() {
        val relayPort = freePort()
        val keyFile = Files.createTempDirectory("reaction-edit-e2e-test").resolve("identity.key").toFile()

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

                val bobReceived = CompletableDeferred<Unit>()
                val bobCollector = clientScope.launch {
                    bob.incomingMessages.collect { bobReceived.complete(Unit) }
                }

                // Establish a real session first (a reaction/edit could in principle open one from
                // scratch, but exercising the common "react/edit on something already sent" path).
                lateinit var sentMessageId: String
                withTimeout(5_000) {
                    while (true) {
                        try {
                            sentMessageId = alice.sendMessage(bobIdentity.dhIdentityPublicKey, "dinner at 6?".toByteArray())
                            break
                        } catch (e: NoSuchElementException) {
                            delay(100)
                        }
                    }
                }
                withTimeout(5_000) { bobReceived.await() }
                bobCollector.cancel()

                val reactionReceived = CompletableDeferred<String>()
                val reactionCollector = clientScope.launch {
                    bob.reactions.collect { reactionReceived.complete(it.emoji) }
                }
                alice.sendReaction(bobIdentity.dhIdentityPublicKey, sentMessageId.hexToByteArray(), "👍")
                assertEquals("👍", withTimeout(5_000) { reactionReceived.await() })
                reactionCollector.cancel()

                val editReceived = CompletableDeferred<String>()
                val editCollector = clientScope.launch {
                    bob.messageEdits.collect { editReceived.complete(it.newText) }
                }
                alice.sendMessageEdit(bobIdentity.dhIdentityPublicKey, sentMessageId.hexToByteArray(), "dinner at 7 instead?")
                assertEquals("dinner at 7 instead?", withTimeout(5_000) { editReceived.await() })
                editCollector.cancel()
            }
        } finally {
            httpClient.close()
            clientScope.cancel()
            relayEngine.stop(500, 1000)
        }
    }
}
