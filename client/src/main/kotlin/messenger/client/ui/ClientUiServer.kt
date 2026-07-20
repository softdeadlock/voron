package messenger.client.ui

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import messenger.common.client.MessengerClient
import messenger.common.e2ee.DeviceIdentity
import messenger.common.util.hexToByteArray
import messenger.common.util.toHex
import org.slf4j.LoggerFactory

/**
 * Serves a minimal local chat page for a single device, purely to let a
 * human drive [MessengerClient] from a browser tab for manual/demo testing.
 * Not part of the wire protocol: browser <-> this server speaks a trivial
 * pipe-delimited text protocol over a local WebSocket, and this server
 * translates it into real calls on [client]. All the actual E2EE happens
 * inside [client]; this is just a thin visual harness on top.
 */
class ClientUiServer(
    private val client: MessengerClient,
    private val identity: DeviceIdentity,
) {
    private val logger = LoggerFactory.getLogger("messenger.client.ui.ClientUiServer")

    fun start(port: Int) {
        embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(WebSockets)
            routing {
                get("/") {
                    call.respondText(pageHtml(identity.dhIdentityPublicKey.toHex()), ContentType.Text.Html)
                }

                webSocket("/ui") {
                    val browserSession = this
                    send("IDENTITY|${identity.dhIdentityPublicKey.toHex()}")

                    try {
                        coroutineScope {
                            val forwarder = launch {
                                client.incomingMessages.collect { message ->
                                    val text = String(message.plaintext, Charsets.UTF_8)
                                    send("MESSAGE|${message.senderDhIdentityKey.toHex()}|${message.senderDisplayName}|$text")
                                }
                            }
                            try {
                                for (frame in incoming) {
                                    if (frame !is Frame.Text) continue
                                    handleBrowserFrame(browserSession, frame.readText())
                                }
                            } finally {
                                forwarder.cancel()
                            }
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        // browser tab closed
                    } catch (e: CancellationException) {
                        throw e
                    }
                }
            }
        }.start(wait = true)
    }

    private suspend fun handleBrowserFrame(session: DefaultWebSocketServerSession, text: String) {
        val parts = text.split("|", limit = 3)
        if (parts.size != 3 || parts[0] != "SEND") {
            session.send("ERROR|malformed command")
            return
        }
        val (_, peerHex, message) = parts
        try {
            client.sendMessage(peerHex.hexToByteArray(), message.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            logger.warn("send failed", e)
            session.send("ERROR|${e.message}")
        }
    }

    private fun pageHtml(ownKeyHex: String) = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <title>messenger test UI — ${ownKeyHex.take(8)}</title>
            <style>
                body { font-family: monospace; max-width: 720px; margin: 24px auto; }
                #own { color: #666; word-break: break-all; margin-bottom: 16px; }
                #log { border: 1px solid #ccc; height: 320px; overflow-y: auto; padding: 8px; margin-bottom: 8px; white-space: pre-wrap; }
                .msg-in { color: #0a6; }
                .msg-out { color: #06a; }
                .msg-err { color: #c22; }
                input { font-family: monospace; }
                #peer { width: 100%; margin-bottom: 8px; }
                #text { width: 70%; }
            </style>
        </head>
        <body>
            <h3>Device: <span id="own">$ownKeyHex</span></h3>
            <div>Peer device key (hex):</div>
            <input id="peer" placeholder="paste the other tab's device key here">
            <div id="log"></div>
            <input id="text" placeholder="message">
            <button id="sendBtn">Send</button>
            <script>
                const log = document.getElementById('log');
                function append(cls, text) {
                    const div = document.createElement('div');
                    div.className = cls;
                    div.textContent = text;
                    log.appendChild(div);
                    log.scrollTop = log.scrollHeight;
                }
                const ws = new WebSocket("ws://" + location.host + "/ui");
                ws.onmessage = (event) => {
                    const parts = event.data.split("|");
                    if (parts[0] === "MESSAGE") {
                        append("msg-in", "from " + parts[2] + " (" + parts[1].slice(0, 8) + "...): " + parts.slice(3).join("|"));
                    } else if (parts[0] === "IDENTITY") {
                        // already shown server-side
                    } else if (parts[0] === "ERROR") {
                        append("msg-err", "error: " + parts.slice(1).join("|"));
                    }
                };
                document.getElementById('sendBtn').onclick = () => {
                    const peer = document.getElementById('peer').value.trim();
                    const text = document.getElementById('text').value;
                    if (!peer || !text) return;
                    ws.send("SEND|" + peer + "|" + text);
                    append("msg-out", "to " + peer.slice(0, 8) + "...: " + text);
                    document.getElementById('text').value = '';
                };
                document.getElementById('text').addEventListener('keydown', (e) => {
                    if (e.key === 'Enter') document.getElementById('sendBtn').click();
                });
            </script>
        </body>
        </html>
    """.trimIndent()
}
