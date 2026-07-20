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
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import messenger.common.client.IncomingGroupMessage
import messenger.common.client.MessengerClient
import messenger.common.e2ee.DeviceIdentity
import messenger.common.group.GROUP_ID_LENGTH
import messenger.server.configureMessengerServer
import messenger.server.routing.Mailbox
import messenger.server.routing.PreKeyDirectory
import messenger.server.routing.PushRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * End-to-end group messaging over a real relay — the "sender keys" scheme in
 * [messenger.common.group.GroupCryptoSession], distributed entirely over real pairwise E2EE
 * sessions established the normal way (X3DH via a real prekey fetch), exactly like a real client
 * would use it. No group-specific server support exists or is needed: the relay only ever sees
 * ordinary pairwise ROUTE-shaped traffic.
 */
class GroupMessagingTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun sendUntilSessionReady(send: suspend () -> Unit) {
        withTimeout(15_000) {
            while (true) {
                try {
                    send()
                    return@withTimeout
                } catch (e: NoSuchElementException) {
                    delay(100)
                }
            }
        }
    }

    @Test
    fun `a group message from one sender fans out and decrypts identically for every other member`() {
        val relayPort = freePort()
        val keyFile = Files.createTempDirectory("relay-group-test").resolve("identity.key").toFile()
        val relayEngine = embeddedServer(Netty, port = relayPort, host = "127.0.0.1") {
            configureMessengerServer(keyFile, PreKeyDirectory(), Mailbox(), PushRegistry())
        }
        relayEngine.start(wait = false)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val httpClient = HttpClient(CIO) { install(WebSockets) }

        try {
            runBlocking {
                val relayStaticPublicKey = fetchRelayKey(httpClient, relayPort)
                val relayUrl = "ws://127.0.0.1:$relayPort/v1/connect"

                val alice = MessengerClient(DeviceIdentity.generate(), httpClient)
                val bob = MessengerClient(DeviceIdentity.generate(), httpClient)
                val carol = MessengerClient(DeviceIdentity.generate(), httpClient)
                alice.connect(relayUrl, relayStaticPublicKey, scope)
                bob.connect(relayUrl, relayStaticPublicKey, scope)
                carol.connect(relayUrl, relayStaticPublicKey, scope)
                bob.publishPreKeys()
                carol.publishPreKeys()

                val groupId = Random.nextBytes(GROUP_ID_LENGTH)
                val bobKey = bob.identity.dhIdentityPublicKey
                val carolKey = carol.identity.dhIdentityPublicKey

                val bobReceived = CompletableDeferred<IncomingGroupMessage>()
                val carolReceived = CompletableDeferred<IncomingGroupMessage>()
                val bobCollector = scope.launch { bob.groupMessages.collect { bobReceived.complete(it) } }
                val carolCollector = scope.launch { carol.groupMessages.collect { carolReceived.complete(it) } }

                val senderKey = alice.createOrRekeyGroup(groupId)
                sendUntilSessionReady { alice.sendGroupSenderKey(bobKey, senderKey) }
                sendUntilSessionReady { alice.sendGroupSenderKey(carolKey, senderKey) }
                alice.sendGroupMessage(groupId, listOf(bobKey, carolKey), "hello group".toByteArray())

                val bobResult = withTimeout(15_000) { bobReceived.await() }
                val carolResult = withTimeout(15_000) { carolReceived.await() }

                assertEquals("hello group", String(bobResult.plaintext))
                assertEquals("hello group", String(carolResult.plaintext))
                assertEquals(alice.identity.dhIdentityPublicKey.toList(), bobResult.senderDhIdentityKey.toList())
                assertEquals(alice.identity.dhIdentityPublicKey.toList(), carolResult.senderDhIdentityKey.toList())
                assertEquals(groupId.toList(), bobResult.groupId.toList())

                bobCollector.cancelAndJoin()
                carolCollector.cancelAndJoin()
                alice.close()
                bob.close()
                carol.close()
            }
        } finally {
            httpClient.close()
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            relayEngine.stop(500, 1000)
        }
    }

    private suspend fun fetchRelayKey(httpClient: HttpClient, relayPort: Int): ByteArray {
        val serverInfo = httpClient.get("http://127.0.0.1:$relayPort/v1/server-info").bodyAsText()
        return Base64.getDecoder().decode(Regex(""""staticPublicKey":"([^"]+)"""").find(serverInfo)!!.groupValues[1])
    }
}
