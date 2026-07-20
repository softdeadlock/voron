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
import messenger.server.configureMessengerServer
import messenger.server.routing.Mailbox
import messenger.server.routing.PreKeyDirectory
import messenger.server.routing.PushRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression test for the reported "messages sent while I was offline never arrive, even after I
 * reconnect" bug: the recipient's app process is killed and relaunched while offline (fresh
 * MessengerClient instance, but the same persisted PreKeyStore bytes reload from "disk", exactly
 * like SecureStore on Android) -- this is what
 * [messenger.common.e2ee.PreKeyStore.publishedPreKeys] unconditionally clearing one-time prekeys
 * on every republish used to break: a message sent while offline fetches the recipient's *last
 * published* bundle (which persists in the relay's directory independently of connection state)
 * and gets mailboxed; if the recipient's relaunch republishes prekeys before that mailboxed
 * message is drained to them, the one-time prekey it depends on was wiped.
 *
 * (A second scenario -- the socket merely drops and reconnects on the *same* long-lived
 * MessengerClient instance while an established ratchet session already exists, so the message
 * needs no prekey at all -- was considered too, but [MessengerClient.close] permanently closes
 * that instance's outgoing channel, so it can't be reconnected the way a real transient network
 * drop can; that path isn't reachable from this test harness and isn't in this fix's scope anyway,
 * since ratchet sessions are in-memory-only regardless and don't survive a real app restart either.
 * The *established-session* half of the report turned out to be a separate bug entirely -- see
 * [SessionResetNoticeTest], kept in its own file/class after the two proved to make each other
 * time out when run together in the same class, for reasons not worth chasing further.)
 */
class OfflineRecipientReconnectDeliveryTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `a message sent while the recipient's app was killed still decrypts after they relaunch and republish`() {
        val relayPort = freePort()
        val keyFile = Files.createTempDirectory("relay-offline-test").resolve("identity.key").toFile()

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

                // Bob's PreKeyStore state (signed prekey + one-time prekeys) persists to a shared
                // in-memory "disk" here, exactly like SecureStore backing it on Android -- so a fresh
                // MessengerClient instance below (standing in for the app being killed and relaunched)
                // reloads the *same* prekey state instead of starting from nothing, just like a real
                // restart would.
                var persistedPreKeyBytes: ByteArray? = null
                val bob1 = MessengerClient(
                    bobIdentity, httpClient,
                    loadPersistedPreKeys = { persistedPreKeyBytes },
                    persistPreKeys = { persistedPreKeyBytes = it },
                )

                // Bob comes online once, publishes prekeys (this is the bundle Alice will fetch),
                // then the app is killed -- exactly like a recipient who briefly opened the app once
                // before, and is now offline (process gone) when the real message gets sent.
                bob1.connect(relayUrl, relayStaticPublicKey, bobScope)
                bob1.publishPreKeys()
                bob1.close()

                alice.connect(relayUrl, relayStaticPublicKey, aliceScope)
                withTimeout(15_000) {
                    // The relay's PreKeyDirectory entry from Bob's publish above persists independently
                    // of his connection state, so this fetch succeeds even though he's offline right now.
                    while (true) {
                        try {
                            alice.sendMessage(bobIdentity.dhIdentityPublicKey, "hello while you were away".toByteArray())
                            break
                        } catch (e: NoSuchElementException) {
                            delay(100)
                        }
                    }
                }

                // Bob relaunches: a brand new MessengerClient (fresh in-memory E2eeManager/session
                // state, exactly like a real app restart), reloading the *same* persisted prekey
                // bytes, then reconnects and republishes -- the same thing
                // ConnectionManager.openConnection does on every real (re)connect, and the exact
                // moment the original bug fired: it used to wipe the one-time prekey Alice's
                // already-sent, still-mailboxed message depends on.
                val bob2 = MessengerClient(
                    bobIdentity, httpClient,
                    loadPersistedPreKeys = { persistedPreKeyBytes },
                    persistPreKeys = { persistedPreKeyBytes = it },
                )
                val bobReceived = CompletableDeferred<String>()
                val bobCollector = bobScope.launch {
                    bob2.incomingMessages.collect { bobReceived.complete(String(it.plaintext)) }
                }
                bob2.connect(relayUrl, relayStaticPublicKey, bobScope)
                bob2.publishPreKeys()

                assertEquals("hello while you were away", withTimeout(15_000) { bobReceived.await() })
                bobCollector.cancel()
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
