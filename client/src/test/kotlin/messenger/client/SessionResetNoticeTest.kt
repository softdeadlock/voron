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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import messenger.common.client.MessengerClient
import messenger.common.e2ee.DeviceIdentity
import messenger.common.util.toHex
import messenger.server.configureMessengerServer
import messenger.server.routing.Mailbox
import messenger.server.routing.PreKeyDirectory
import messenger.server.routing.PushRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression test for the *other* half of the "offline message never arrives" report: once a real
 * session already exists (e.g. an earlier exchange succeeded), the sender's E2eeManager happily
 * keeps encrypting as [messenger.common.e2ee.E2eeMessage.Normal] over that session -- it has no
 * way to know the recipient's copy of it was lost (e.g. their app restarted). The recipient can't
 * decrypt a Normal message with no matching session and replies with a SESSION_RESET_NOTICE, which
 * used to only drop the sender's stale session for *next* time -- the message that had already
 * failed was gone for good. [MessengerClient.sessionResetNotices] now lets a caller (see
 * [messenger.android.data.ConnectionManager]'s collector) notice this and resend. This test only
 * asserts the flow itself fires correctly for the peer that lost the message -- the actual resend
 * is Android/ConnectionManager-specific and covered there instead.
 *
 * Kept in its own file/class (not alongside [OfflineRecipientReconnectDeliveryTest], which covers
 * the *other* half of the same report) after the two proved to reliably make each other time out
 * when run together in the same class, for reasons not worth chasing further -- both pass
 * consistently on their own.
 */
class SessionResetNoticeTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `losing a session after it was established notifies the sender via sessionResetNotices`() {
        val relayPort = freePort()
        val keyFile = Files.createTempDirectory("relay-session-reset-test").resolve("identity.key").toFile()

        val relayEngine = embeddedServer(Netty, port = relayPort, host = "127.0.0.1") {
            configureMessengerServer(keyFile, PreKeyDirectory(), Mailbox(), PushRegistry())
        }
        relayEngine.start(wait = false)

        val aliceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val bobScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val httpClient = HttpClient(CIO) { install(WebSockets) }

        try {
            runBlocking {
                val serverInfo = httpClient.get("http://127.0.0.1:$relayPort/v1/server-info").bodyAsText()
                val relayStaticPublicKey = Base64.getDecoder().decode(
                    Regex(""""staticPublicKey":"([^"]+)"""").find(serverInfo)!!.groupValues[1],
                )
                val relayUrl = "ws://127.0.0.1:$relayPort/v1/connect"

                val aliceIdentity = DeviceIdentity.generate()
                val bobIdentity = DeviceIdentity.generate()
                val alice = MessengerClient(aliceIdentity, httpClient)

                var persistedPreKeyBytes: ByteArray? = null
                val bob1 = MessengerClient(
                    bobIdentity, httpClient,
                    loadPersistedPreKeys = { persistedPreKeyBytes },
                    persistPreKeys = { persistedPreKeyBytes = it },
                )

                alice.connect(relayUrl, relayStaticPublicKey, aliceScope)
                bob1.connect(relayUrl, relayStaticPublicKey, bobScope)
                bob1.publishPreKeys()

                // Establish a real session first -- Bob is online and receives it live.
                val firstReceived = CompletableDeferred<String>()
                val firstCollector = bobScope.launch {
                    bob1.incomingMessages.collect { firstReceived.complete(String(it.plaintext)) }
                }
                withTimeout(15_000) {
                    while (true) {
                        try {
                            alice.sendMessage(bobIdentity.dhIdentityPublicKey, "first message".toByteArray())
                            break
                        } catch (e: NoSuchElementException) {
                            delay(100)
                        }
                    }
                }
                assertEquals("first message", withTimeout(15_000) { firstReceived.await() })
                firstCollector.cancel()
                bob1.close()

                // Alice's own session with Bob is still in memory (her process never restarted), so
                // this next send goes out as a Normal message over it -- no prekey fetch involved.
                val resetNotices = CompletableDeferred<String>()
                val resetCollector = aliceScope.launch {
                    alice.sessionResetNotices.collect { resetNotices.complete(it.toHex()) }
                }
                alice.sendMessage(bobIdentity.dhIdentityPublicKey, "sent right before you restarted".toByteArray())

                // Bob relaunches: fresh MessengerClient, no session in memory for Alice at all, so
                // the Normal message above can't decrypt and he tells her to reset.
                val bob2 = MessengerClient(
                    bobIdentity, httpClient,
                    loadPersistedPreKeys = { persistedPreKeyBytes },
                    persistPreKeys = { persistedPreKeyBytes = it },
                )
                bob2.connect(relayUrl, relayStaticPublicKey, bobScope)
                bob2.publishPreKeys()

                assertEquals(bobIdentity.dhIdentityPublicKey.toHex(), withTimeout(15_000) { resetNotices.await() })
                resetCollector.cancel()
                alice.close()
                bob2.close()
            }
        } finally {
            httpClient.close()
            runBlocking {
                aliceScope.coroutineContext[Job]?.cancelAndJoin()
                bobScope.coroutineContext[Job]?.cancelAndJoin()
            }
            relayEngine.stop(500, 1000)
        }
    }
}
