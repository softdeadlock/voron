package messenger.server

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import messenger.common.protocol.RoutingEnvelope
import messenger.common.protocol.TransportFrame
import messenger.common.transport.NoiseIkInitiatorHandshake
import messenger.common.transport.NoiseStaticKeyPair
import messenger.server.routing.Mailbox
import messenger.server.routing.PreKeyDirectory
import messenger.server.routing.PushRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RelayIntegrationTest {

    @Test
    fun `two devices complete the Noise_IK handshake and relay routes a message between them`() = testApplication {
        val keyFile = java.nio.file.Files.createTempDirectory("relay-identity-test").resolve("identity.key").toFile()
        application { configureMessengerServer(keyFile, PreKeyDirectory(), Mailbox(), PushRegistry()) }

        val client = createClient { install(WebSockets) }

        val serverInfoBody = client.get("/v1/server-info").bodyAsText()
        val relayStaticPublicKey = Base64.getDecoder().decode(
            Regex(""""staticPublicKey":"([^"]+)"""").find(serverInfoBody)!!.groupValues[1],
        )

        val deviceA = NoiseStaticKeyPair.generate()
        val deviceB = NoiseStaticKeyPair.generate()

        val deviceBReady = CompletableDeferred<Unit>()
        val deviceBReceived = CompletableDeferred<ByteArray>()

        coroutineScope {
            val deviceBJob = launch {
                client.webSocket("/v1/connect") {
                    val initiator = NoiseIkInitiatorHandshake(deviceB, relayStaticPublicKey)
                    send(Frame.Binary(fin = true, data = initiator.createMessage1()))
                    val message2 = (incoming.receive() as Frame.Binary).readBytes()
                    val session = initiator.consumeMessage2(message2)

                    deviceBReady.complete(Unit)

                    val inboundFrame = incoming.receive() as Frame.Binary
                    val plaintext = session.decrypt(inboundFrame.readBytes())
                    val decoded = TransportFrame.decode(plaintext)
                    val envelope = RoutingEnvelope.decode(decoded.body)
                    deviceBReceived.complete(envelope.payload)
                }
            }

            withTimeout(5_000) { deviceBReady.await() }

            client.webSocket("/v1/connect") {
                val initiator = NoiseIkInitiatorHandshake(deviceA, relayStaticPublicKey)
                send(Frame.Binary(fin = true, data = initiator.createMessage1()))
                val message2 = (incoming.receive() as Frame.Binary).readBytes()
                val session = initiator.consumeMessage2(message2)

                val envelope = RoutingEnvelope.encode(deviceB.publicKey, "hello from A".toByteArray())
                val frame = TransportFrame.encode(TransportFrame.ROUTE, envelope)
                send(Frame.Binary(fin = true, data = session.encrypt(frame)))
            }

            val payload = withTimeout(5_000) { deviceBReceived.await() }
            assertEquals("hello from A", String(payload))

            deviceBJob.cancel()
        }
    }

    @Test
    fun `a message sent while the recipient is offline is delivered on their next connect`() = testApplication {
        val keyFile = java.nio.file.Files.createTempDirectory("relay-identity-test-mailbox").resolve("identity.key").toFile()
        application { configureMessengerServer(keyFile, PreKeyDirectory(), Mailbox(), PushRegistry()) }

        val client = createClient { install(WebSockets) }
        val serverInfoBody = client.get("/v1/server-info").bodyAsText()
        val relayStaticPublicKey = Base64.getDecoder().decode(
            Regex(""""staticPublicKey":"([^"]+)"""").find(serverInfoBody)!!.groupValues[1],
        )

        val deviceA = NoiseStaticKeyPair.generate()
        val deviceB = NoiseStaticKeyPair.generate()

        // Device B is never connected while A sends — the relay must queue it.
        client.webSocket("/v1/connect") {
            val initiator = NoiseIkInitiatorHandshake(deviceA, relayStaticPublicKey)
            send(Frame.Binary(fin = true, data = initiator.createMessage1()))
            val message2 = (incoming.receive() as Frame.Binary).readBytes()
            val session = initiator.consumeMessage2(message2)

            val envelope = RoutingEnvelope.encode(deviceB.publicKey, "sent while you were away".toByteArray())
            val frame = TransportFrame.encode(TransportFrame.ROUTE, envelope)
            send(Frame.Binary(fin = true, data = session.encrypt(frame)))
        }

        // Now B connects for the first time and should receive the queued frame immediately.
        client.webSocket("/v1/connect") {
            val initiator = NoiseIkInitiatorHandshake(deviceB, relayStaticPublicKey)
            send(Frame.Binary(fin = true, data = initiator.createMessage1()))
            val message2 = (incoming.receive() as Frame.Binary).readBytes()
            val session = initiator.consumeMessage2(message2)

            val inboundFrame = withTimeout(5_000) { incoming.receive() as Frame.Binary }
            val plaintext = session.decrypt(inboundFrame.readBytes())
            val envelope = RoutingEnvelope.decode(TransportFrame.decode(plaintext).body)
            assertEquals("sent while you were away", String(envelope.payload))
        }
    }
}
