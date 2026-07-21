package messenger.server.routing

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.application.install
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import java.util.Base64
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import messenger.common.crypto.Aead
import messenger.common.crypto.Hkdf
import messenger.common.crypto.X25519
import messenger.common.transport.NoiseStaticKeyPair
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("messenger.server.routing.OnionNodeRoute")

/**
 * A forwarding-only onion hop (guard or middle). It peels exactly one layer of ChaCha20-Poly1305
 * encryption off every frame coming from the previous hop (the client, or an earlier onion node)
 * and forwards the plaintext to [nextHopUrl] unmodified, and does the reverse (encrypt-and-forward)
 * for frames coming back — so it only ever sees its own single layer, never the Noise/E2EE traffic
 * the client is actually exchanging with the real relay, and never more of the circuit than its
 * immediate neighbors.
 *
 * The per-circuit key comes from a fresh ECDH with the client's ephemeral key (sent once, as the
 * `ek` query parameter on the WebSocket upgrade) against this node's own long-term static key — no
 * separate handshake round-trip is needed since every subsequent frame is already usable ciphertext.
 * [forwardEphemeralKey] is true for a guard forwarding to a middle node, false for a middle node
 * forwarding to the real relay (which has no idea this circuit exists and expects its normal
 * `/v1/connect` traffic verbatim).
 */
fun Application.configureOnionNode(nodeIdentity: NoiseStaticKeyPair, nextHopUrl: String, forwardEphemeralKey: Boolean) {
    val outboundClient = HttpClient(CIO) { install(ClientWebSockets) }

    // SECURITY (audit finding, 2026-07-21): the main relay's /v1/connect has this same cap (see
    // Application.kt/Routes.kt's own doc) specifically because completing a handshake here is
    // cheap and requires no allowlist -- this onion hop had no equivalent at all, and every
    // accepted connection here also opens an *outbound* WebSocket to the next hop, so flooding one
    // node cascades resource exhaustion onto the whole circuit, including the real backend relay.
    val maxConcurrentConnections = System.getenv("VORON_ONION_MAX_CONNECTIONS")?.toIntOrNull() ?: 20_000
    val connectionSlots = Semaphore(maxConcurrentConnections)

    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
        // Same reasoning as the main relay's WebSockets config (see Application.kt) — Ktor's
        // default is unbounded, and a forwarding-only onion hop has even less reason to ever see
        // a frame bigger than what the real relay itself would accept.
        maxFrameSize = 1L * 1024 * 1024
    }

    routing {
        get("/v1/onion-info") {
            val publicKeyB64 = Base64.getEncoder().encodeToString(nodeIdentity.publicKey)
            call.respondText(
                """{"staticPublicKey":"$publicKeyB64"}""",
                ContentType.Application.Json,
            )
        }

        webSocket("/v1/onion-relay") {
            if (!connectionSlots.tryAcquire()) {
                logger.info("rejecting onion connection: at capacity ($maxConcurrentConnections)")
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "node at capacity"))
                return@webSocket
            }
            try {
            val ekParam = call.request.queryParameters["ek"]
            val clientEphemeralPublic = ekParam?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            if (clientEphemeralPublic == null || clientEphemeralPublic.size != X25519.KEY_LENGTH) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "missing or malformed ek"))
                return@webSocket
            }

            val shared = X25519.dh(nodeIdentity.privateKey, clientEphemeralPublic)
            // Independent keys per direction so a single shared secret never reuses a nonce space.
            val peelKey = Hkdf.derive(shared, "voron-onion-client-to-exit", Aead.KEY_LENGTH)
            val wrapKey = Hkdf.derive(shared, "voron-onion-exit-to-client", Aead.KEY_LENGTH)

            val nextUrl = if (forwardEphemeralKey) "$nextHopUrl?ek=$ekParam" else nextHopUrl
            val nextHop = try {
                outboundClient.webSocketSession(nextUrl)
            } catch (e: Exception) {
                logger.warn("failed to reach next hop $nextHopUrl", e)
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "next hop unreachable"))
                return@webSocket
            }

            var peelCounter = 0L
            var wrapCounter = 0L

            try {
                coroutineScope {
                    val fromClient = launch {
                        for (frame in incoming) {
                            if (frame !is Frame.Binary) continue
                            val plaintext = try {
                                Aead.decrypt(peelKey, Aead.counterNonce(peelCounter++), EMPTY_AAD, frame.readBytes())
                            } catch (e: Exception) {
                                logger.warn("dropping onion frame that failed to authenticate")
                                continue
                            }
                            nextHop.send(Frame.Binary(fin = true, data = plaintext))
                        }
                    }
                    val fromNextHop = launch {
                        for (frame in nextHop.incoming) {
                            if (frame !is Frame.Binary) continue
                            val ciphertext = Aead.encrypt(wrapKey, Aead.counterNonce(wrapCounter++), EMPTY_AAD, frame.readBytes())
                            send(Frame.Binary(fin = true, data = ciphertext))
                        }
                    }
                    fromClient.invokeOnCompletion { fromNextHop.cancel() }
                    fromNextHop.invokeOnCompletion { fromClient.cancel() }
                }
            } catch (e: ClosedReceiveChannelException) {
                // normal disconnect
            } catch (e: CancellationException) {
                throw e
            } finally {
                nextHop.close()
            }
            } finally {
                connectionSlots.release()
            }
        }
    }
}

private val EMPTY_AAD = ByteArray(0)
