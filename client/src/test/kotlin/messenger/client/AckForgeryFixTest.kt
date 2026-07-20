package messenger.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
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
import kotlinx.coroutines.withTimeoutOrNull
import messenger.common.client.ApplicationMessage
import messenger.common.client.MessengerClient
import messenger.common.e2ee.DeviceIdentity
import messenger.common.protocol.RoutingEnvelope
import messenger.common.protocol.TransportFrame
import messenger.common.transport.NoiseIkResponderHandshake
import messenger.common.transport.NoiseStaticKeyPair
import messenger.server.configureMessengerServer
import messenger.server.routing.Mailbox
import messenger.server.routing.PreKeyDirectory
import messenger.server.routing.PushRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the ack-forgery fix: DELIVERY_ACK/READ_ACK now ride an [messenger.common.e2ee.E2eeMessage],
 * not a bare [RoutingEnvelope], so a relay can no longer forge one without a valid ratchet ciphertext.
 * See exploit/AckForgeryExploit.kt for the original PoC this is adapted from.
 */
class AckForgeryFixTest {

    @Test
    fun `a forged raw-envelope delivery ack is dropped, not accepted`() {
        val port = 18099
        val relayIdentity = NoiseStaticKeyPair.generate()
        val forgedMessageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH) { (it + 1).toByte() }
        val forgedPeerIdentity = DeviceIdentity.generate()

        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            install(io.ktor.server.websocket.WebSockets)
            routing {
                webSocket("/v1/connect") {
                    val firstFrame = incoming.receive()
                    require(firstFrame is Frame.Binary)
                    val responder = NoiseIkResponderHandshake(relayIdentity)
                    responder.consumeMessage1(firstFrame.readBytes())
                    val (message2, transportSession) = responder.createMessage2()
                    send(Frame.Binary(fin = true, data = message2))

                    // Old (pre-fix) wire shape: a bare RoutingEnvelope, never wrapped in an
                    // E2eeMessage — exactly what a malicious relay could forge before the fix.
                    val forgedEnvelope = RoutingEnvelope.encode(forgedPeerIdentity.dhIdentityPublicKey, forgedMessageId)
                    val forgedFrame = TransportFrame.encode(TransportFrame.DELIVERY_ACK, forgedEnvelope)
                    delay(300)
                    send(Frame.Binary(fin = true, data = transportSession.encrypt(forgedFrame)))
                    delay(1500)
                    close(CloseReason(CloseReason.Codes.NORMAL, "test done"))
                }
            }
        }
        server.start(wait = false)
        Thread.sleep(500)

        val victimIdentity = DeviceIdentity.generate()
        val httpClient = HttpClient(CIO) { install(WebSockets) }
        val victimClient = MessengerClient(victimIdentity, httpClient)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            runBlocking {
                val received = CompletableDeferred<Unit>()
                val collector = scope.launch {
                    victimClient.deliveryAcks.collect { received.complete(Unit) }
                }
                victimClient.connect("ws://127.0.0.1:$port/v1/connect", relayIdentity.publicKey, scope)
                val result = withTimeoutOrNull(2500) { received.await() }
                assertNull(result) { "forged delivery ack must never reach the deliveryAcks flow" }
                collector.cancel()
            }
        } finally {
            httpClient.close()
            scope.cancel()
            server.stop(500, 1000)
        }
    }

    @Test
    fun `a legitimate delivery ack still round-trips through a real relay`() {
        val relayPort = ServerSocket(0).use { it.localPort }
        val keyFile = Files.createTempDirectory("ack-fix-test").resolve("identity.key").toFile()

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

                val ackReceived = CompletableDeferred<String>()
                val ackCollector = clientScope.launch {
                    alice.deliveryAcks.collect { ackReceived.complete(it.messageId) }
                }

                lateinit var sentMessageId: String
                withTimeout(5_000) {
                    while (true) {
                        try {
                            sentMessageId = alice.sendMessage(bobIdentity.dhIdentityPublicKey, "ping".toByteArray())
                            break
                        } catch (e: NoSuchElementException) {
                            delay(100)
                        }
                    }
                }

                assertEquals(sentMessageId, withTimeout(5_000) { ackReceived.await() })
                ackCollector.cancel()
            }
        } finally {
            httpClient.close()
            clientScope.cancel()
            relayEngine.stop(500, 1000)
        }
    }
}
