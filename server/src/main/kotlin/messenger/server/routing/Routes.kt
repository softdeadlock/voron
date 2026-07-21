package messenger.server.routing

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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
import messenger.common.e2ee.PreKeyCodec
import messenger.common.e2ee.TurnCredentialsCodec
import messenger.common.protocol.RoutingEnvelope
import messenger.common.protocol.TransportFrame
import messenger.common.transport.NoiseIkResponderHandshake
import messenger.common.transport.NoiseStaticKeyPair
import messenger.common.util.hexToByteArray
import messenger.common.util.toHex
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("messenger.server.routing.Routes")

private const val DEFAULT_CANARY_STATEMENT =
    "As of this date, the operator of this relay has not received any national security letter, " +
        "gag order, or other secret legal demand requiring it to disclose user data, backdoor this " +
        "software, or covertly assist surveillance of any kind -- and has not been compelled to " +
        "hand over any private keys, message content, or metadata beyond what this relay ever holds " +
        "in the first place (see the project's own docs for what that is). This statement is renewed " +
        "periodically. If it stops being renewed, or its wording changes in a way that quietly drops " +
        "any of the above, treat that absence itself as the signal."

// Fallback "as of" if the operator hasn't set CANARY_AS_OF -- process start time, so an operator who
// forgets to configure it at least gets a visibly-recent (not stale-looking-but-wrong) timestamp,
// though setting the env var explicitly on each real renewal is what actually makes this meaningful.
private val canaryDeployedAtMillis = System.currentTimeMillis()

private fun jsonString(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    return "\"$escaped\""
}

fun Application.configureRouting(
    relayIdentity: NoiseStaticKeyPair,
    registry: ConnectionRegistry,
    preKeys: PreKeyDirectoryStore,
    mailbox: MailboxStore,
    pushRegistry: PushEndpointStore,
    pushNotifier: PushNotifier,
    turnCredentialsIssuer: TurnCredentialsIssuer? = null,
) {
    val groupEventRateLimiter = GroupEventRateLimiter()
    val preKeyFetchRateLimiter = GroupEventRateLimiter(maxEvents = 30, windowMillis = 10_000L)
    // DoS: a Noise_IK handshake attempt is cheap for whoever's sending it (just a fresh ephemeral
    // keypair) but not free for this relay to process, and nothing capped how many could be in
    // flight or held open at once before this -- an attacker opening many sockets and either
    // completing real handshakes or just sitting on them exhausts file descriptors/memory/coroutines
    // with no authentication required at all. A single global permit count is coarse (no per-IP
    // fairness) but cheap and closes the unbounded-growth failure mode; per-IP accounting is a
    // reasonable follow-up once there's visibility into real deployment traffic patterns/proxy setup.
    val maxConcurrentConnections = System.getenv("VORON_MAX_CONNECTIONS")?.toIntOrNull() ?: 20_000
    val connectionSlots = Semaphore(maxConcurrentConnections)

    routing {
        get("/health") {
            call.respondText("ok")
        }

        get("/v1/server-info") {
            val publicKeyB64 = Base64.getEncoder().encodeToString(relayIdentity.publicKey)
            call.respondText(
                """{"staticPublicKey":"$publicKeyB64"}""",
                ContentType.Application.Json,
            )
        }

        // Warrant canary: a periodically-renewed statement the operator manually updates (see
        // CANARY_STATEMENT/CANARY_AS_OF env vars) affirming no secret legal order has been received.
        // The signal isn't cryptographic -- it's that this statement keeps being renewed at all, and
        // by a human who can be legally barred from saying otherwise but not (in most jurisdictions)
        // compelled to keep lying by omission indefinitely. If asOfMillis stops advancing, or this
        // statement disappears/changes wording unexpectedly, treat that itself as the signal.
        get("/v1/canary") {
            val statement = System.getenv("CANARY_STATEMENT") ?: DEFAULT_CANARY_STATEMENT
            val asOfMillis = System.getenv("CANARY_AS_OF")?.toLongOrNull() ?: canaryDeployedAtMillis
            call.respondText(
                """{"statement":${jsonString(statement)},"asOfMillis":$asOfMillis}""",
                ContentType.Application.Json,
            )
        }

        webSocket("/v1/connect") {
            if (!connectionSlots.tryAcquire()) {
                logger.info("rejecting connection: at capacity ($maxConcurrentConnections)")
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "relay at capacity"))
                return@webSocket
            }
            try {
            val firstFrame = incoming.receive()
            if (firstFrame !is Frame.Binary) {
                close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "expected binary handshake message"))
                return@webSocket
            }

            val responder = NoiseIkResponderHandshake(relayIdentity)
            try {
                responder.consumeMessage1(firstFrame.readBytes())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // SECURITY (2026-07-18 exploit hunt): this used to only catch BadPaddingException
                // (a failed AEAD tag on the handshake payload) — but an unauthenticated client's
                // very first byte on the wire can be anything, and noise-java's parsing of a
                // malformed-but-not-necessarily-decryption-failing message (garbage that isn't
                // even shaped like a Noise_IK message1) can throw other RuntimeExceptions
                // (confirmed live: a garbage first frame left the connection hung with no reply
                // for 30+s instead of being rejected). Any failure to parse an untrusted client's
                // handshake attempt should just close the connection, never propagate.
                logger.info("rejected Noise_IK handshake: ${e.message}")
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "handshake failed"))
                return@webSocket
            }

            val (message2, transportSession) = responder.createMessage2()
            send(Frame.Binary(fin = true, data = message2))

            val connection = Connection(
                staticPublicKeyHex = responder.remoteStaticPublicKey.toHex(),
                transportSession = transportSession,
            )
            registry.register(connection)
            logger.info("device connected: ${connection.staticPublicKeyHex}")

            try {
                coroutineScope {
                    // RELIABILITY: `writer.cancel()` below (whenever the client disconnects, which
                    // includes the common "brief push-wakeup reconnect" case) used to just abandon
                    // whatever was still sitting in `connection.outgoing`'s buffer — a message,
                    // ack, reaction, or edit already popped out of the mailbox and handed to this
                    // channel, but not yet actually written to the (possibly already-dead) socket,
                    // was silently lost forever: never delivered, and never re-queued, so the
                    // sender's delivery status just stayed stuck with no way to tell it happened.
                    // See drainOutgoingReliably for the fix.
                    val writer = launch {
                        drainOutgoingReliably(connection, mailbox) { frame ->
                            send(Frame.Binary(fin = true, data = connection.transportSession.encrypt(frame)))
                        }
                    }

                    // Flush queued offline messages with a suspending send: the
                    // writer above is already draining, so a backlog larger than
                    // the channel capacity just applies backpressure here
                    // instead of silently dropping the excess (trySend would).
                    //
                    // SECURITY (2026-07-18 exploit hunt): this used to be an uncancelled `launch`.
                    // If the client disconnected while a backlog bigger than the outgoing channel's
                    // capacity was still flushing, this job stayed suspended on send() forever once
                    // `writer` (below) stopped draining — and since coroutineScope awaits every
                    // child before returning, that permanently blocked this whole connection's
                    // cleanup: registry.unregister/connection.close/transportSession.destroy in the
                    // outer `finally` (below) never ran, leaking the connection and its transport
                    // session per affected disconnect. Now tracked and cancelled alongside `writer`,
                    // and whatever's left unsent gets requeued rather than silently dropped.
                    val queued = mailbox.drain(connection.staticPublicKeyHex)
                    val flushJob = queued.takeIf { it.isNotEmpty() }?.let { toFlush ->
                        logger.info("flushing ${toFlush.size} mailboxed frame(s) to ${connection.staticPublicKeyHex}")
                        launch {
                            var sent = 0
                            try {
                                while (sent < toFlush.size) {
                                    connection.outgoing.send(toFlush[sent])
                                    sent++
                                }
                            } finally {
                                for (i in sent until toFlush.size) mailbox.enqueue(connection.staticPublicKeyHex, toFlush[i])
                            }
                        }
                    }

                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Binary) continue
                            val plaintext = try {
                                connection.transportSession.decrypt(frame.readBytes())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // SECURITY (2026-07-18 exploit hunt): same reasoning as the
                                // handshake catch above — a too-short or otherwise malformed
                                // ciphertext can throw something other than BadPaddingException
                                // out of the underlying cipher, and this frame's bytes are
                                // attacker-influenced (an authenticated device is still an
                                // untrusted party w.r.t. frame *contents*). Drop and keep the
                                // connection alive rather than letting it escape uncaught.
                                logger.info("dropping frame that failed to authenticate from ${connection.staticPublicKeyHex}")
                                continue
                            }
                            handleFrame(connection, plaintext, registry, preKeys, mailbox, pushRegistry, pushNotifier, turnCredentialsIssuer, groupEventRateLimiter, preKeyFetchRateLimiter)
                        }
                    } finally {
                        writer.cancel()
                        flushJob?.cancel()
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                // normal disconnect
            } catch (e: CancellationException) {
                throw e
            } finally {
                registry.unregister(connection)
                connection.close()
                logger.info("device disconnected: ${connection.staticPublicKeyHex}")
            }
            } finally {
                connectionSlots.release()
            }
        }
    }
}

/**
 * Drains [Connection.outgoing] via [sendFrame], one frame at a time. If this coroutine is
 * cancelled (the normal case: the client disconnected) or [sendFrame] throws, whatever frame was
 * mid-send plus anything still buffered goes back into [mailbox] rather than being silently lost —
 * `tryReceive` is non-suspending, so this is safe to run from a `finally` even under cancellation.
 */
internal suspend fun drainOutgoingReliably(connection: Connection, mailbox: MailboxStore, sendFrame: suspend (ByteArray) -> Unit) {
    var inFlight: ByteArray? = null
    try {
        for (frame in connection.outgoing) {
            inFlight = frame
            sendFrame(frame)
            inFlight = null
        }
    } finally {
        inFlight?.let { mailbox.enqueue(connection.staticPublicKeyHex, it) }
        var leftover = connection.outgoing.tryReceive().getOrNull()
        while (leftover != null) {
            mailbox.enqueue(connection.staticPublicKeyHex, leftover)
            leftover = connection.outgoing.tryReceive().getOrNull()
        }
    }
}

private suspend fun handleFrame(
    from: Connection,
    plaintext: ByteArray,
    registry: ConnectionRegistry,
    preKeys: PreKeyDirectoryStore,
    mailbox: MailboxStore,
    pushRegistry: PushEndpointStore,
    pushNotifier: PushNotifier,
    turnCredentialsIssuer: TurnCredentialsIssuer?,
    groupEventRateLimiter: GroupEventRateLimiter,
    preKeyFetchRateLimiter: GroupEventRateLimiter,
) {
    val decoded = try {
        TransportFrame.decode(plaintext)
    } catch (e: IllegalArgumentException) {
        logger.info("dropping malformed transport frame from ${from.staticPublicKeyHex}")
        return
    }

    // Administrative group churn (membership changes, join requests) gets a tighter leash than
    // ordinary chat traffic: every fan-out recipient has to replay/re-verify it, so it's the
    // cheaper spam vector — see GroupEventRateLimiter's doc comment.
    if (decoded.type == TransportFrame.GROUP_CONTROL_EVENT || decoded.type == TransportFrame.GROUP_JOIN_REQUEST) {
        if (!groupEventRateLimiter.allow(from.staticPublicKeyHex)) {
            logger.info("rate-limiting group control traffic from ${from.staticPublicKeyHex}")
            return
        }
    }

    when (decoded.type) {
        // All share store-and-forward routing; the frame type is preserved on the wire so
        // the recipient dispatches each the way the sender tagged it. REACTION/EDIT_MESSAGE and the
        // group frames are mailboxed like ROUTE (not live-only like TYPING_INDICATOR) — a reaction,
        // edit, sender-key distribution, or group message to an offline peer should still arrive
        // once they reconnect, same as an ordinary message would. The relay never learns groups
        // exist as a concept: GROUP_SENDER_KEY/GROUP_MESSAGE/GROUP_KEY_REQUEST are, to it, just
        // another pairwise-routed opaque E2EE blob, fanned out by the sender one recipient at a
        // time exactly like every other frame type here.
        TransportFrame.ROUTE, TransportFrame.SESSION_RESET_NOTICE, TransportFrame.DELIVERY_ACK,
        TransportFrame.READ_ACK, TransportFrame.REACTION, TransportFrame.EDIT_MESSAGE,
        TransportFrame.GROUP_SENDER_KEY, TransportFrame.GROUP_MESSAGE, TransportFrame.GROUP_KEY_REQUEST,
        TransportFrame.GROUP_CONTROL_EVENT, TransportFrame.GROUP_CONTROL_SYNC_REQUEST, TransportFrame.GROUP_JOIN_REQUEST ->
            routeMessage(from, decoded.body, registry, mailbox, pushRegistry, pushNotifier, decoded.type)
        TransportFrame.CALL_SIGNAL -> routeCallSignal(from, decoded.body, registry, mailbox, pushRegistry, pushNotifier)
        TransportFrame.FILE_TRANSFER -> routeFileTransfer(from, decoded.body, registry)
        TransportFrame.TYPING_INDICATOR -> routeEphemeral(from, decoded.body, registry, TransportFrame.TYPING_INDICATOR)
        TransportFrame.PUBLISH_PREKEYS -> publishPreKeys(from, decoded.body, preKeys)
        // DoS: PreKeyDirectory.fetch() pops one one-time prekey off the target's pool per call --
        // with no cap, any connected device could name any target and burn through their whole OTP
        // pool for free, forcing every one of that target's future sessions to fall back to the
        // weaker no-OTP 3-DH path. Reuses GroupEventRateLimiter's generic per-device sliding window
        // rather than a second copy of the same bucket logic.
        TransportFrame.FETCH_PREKEYS -> {
            if (preKeyFetchRateLimiter.allow(from.staticPublicKeyHex)) {
                fetchPreKeys(from, decoded.body, preKeys)
            } else {
                logger.info("rate-limiting prekey fetches from ${from.staticPublicKeyHex}")
            }
        }
        TransportFrame.PUSH_REGISTER -> registerPush(from, decoded.body, pushRegistry)
        TransportFrame.TURN_CREDENTIALS_REQUEST -> issueTurnCredentials(from, turnCredentialsIssuer)
        else -> logger.info("dropping unknown frame type ${decoded.type} from ${from.staticPublicKeyHex}")
    }
}

private fun registerPush(from: Connection, body: ByteArray, pushRegistry: PushEndpointStore) {
    val endpointUrl = String(body, Charsets.UTF_8)
    pushRegistry.register(from.staticPublicKeyHex, endpointUrl)
    logger.info(
        if (endpointUrl.isEmpty()) "unregistered push endpoint for ${from.staticPublicKeyHex}"
        else "registered push endpoint for ${from.staticPublicKeyHex}",
    )
}

/** The shared first step of every route*: the envelope decoded, its header swapped for [Connection.staticPublicKeyHex] (the authenticated sender), and re-encoded as an outbound frame. */
private class RoutedFrame(val recipientHex: String, val recipientKey: ByteArray, val frame: ByteArray)

/** Decodes [body]'s [RoutingEnvelope] and rewraps it for delivery as a [frameType] frame — null (frame dropped, logged) on malformed input. */
private fun rewrapForRecipient(from: Connection, body: ByteArray, frameType: Byte, label: String): RoutedFrame? {
    val envelope = try {
        RoutingEnvelope.decode(body)
    } catch (e: IllegalArgumentException) {
        logger.info("dropping malformed $label envelope from ${from.staticPublicKeyHex}")
        return null
    }
    val outbound = RoutingEnvelope.encode(from.staticPublicKeyHex.hexToByteArray(), envelope.payload)
    return RoutedFrame(
        recipientHex = envelope.peerStaticPublicKey.toHex(),
        recipientKey = envelope.peerStaticPublicKey,
        frame = TransportFrame.encode(frameType, outbound),
    )
}

/** Forwards a [RoutingEnvelope]-shaped frame to its target, replacing the header with [from]'s key; [frameType] is preserved on the wire so the recipient dispatches it the same way the sender tagged it (e.g. ROUTE vs SESSION_RESET_NOTICE). */
internal fun routeMessage(
    from: Connection,
    body: ByteArray,
    registry: ConnectionRegistry,
    mailbox: MailboxStore,
    pushRegistry: PushEndpointStore,
    pushNotifier: PushNotifier,
    frameType: Byte = TransportFrame.ROUTE,
) {
    val routed = rewrapForRecipient(from, body, frameType, "routed") ?: return
    val recipientHex = routed.recipientHex
    val frame = routed.frame

    val recipient = registry.find(recipientHex)
    if (recipient == null) {
        logger.info("recipient $recipientHex offline, mailboxing frame from ${from.staticPublicKeyHex}")
        mailbox.enqueue(recipientHex, frame)
        pushRegistry.lookup(recipientHex)?.let(pushNotifier::notifyAsync)
        return
    }

    // A suspending send here would let one slow recipient stall the sender's
    // read loop (cross-connection head-of-line blocking), so on a full or
    // closing channel we fall back to the mailbox instead of losing the frame.
    val result = recipient.outgoing.trySend(frame)
    if (result.isFailure) {
        logger.warn(
            "outgoing channel for $recipientHex ${if (result.isClosed) "closed" else "full"}, " +
                "mailboxing frame from ${from.staticPublicKeyHex}",
        )
        mailbox.enqueue(recipientHex, frame)
        pushRegistry.lookup(recipientHex)?.let(pushNotifier::notifyAsync)
    }
}

/**
 * Routes a [CALL_SIGNAL][TransportFrame.CALL_SIGNAL] frame like [routeMessage], with one
 * difference on an unreachable recipient: if they have no push endpoint registered, there is no
 * way to reach them beyond hoping they happen to reconnect, so the caller gets an immediate
 * [CALL_UNAVAILABLE][TransportFrame.CALL_UNAVAILABLE] rather than waiting out the full ring
 * timeout for nothing. But if a push endpoint *is* registered, the signal is mailboxed and a
 * wakeup POST fired instead — same as any other frame — so a killed app has a real chance to
 * reconnect and receive the RING before the caller's own ~45s no-answer timeout gives up. This is
 * the fix for calls not arriving at all when the recipient's app isn't running.
 */
internal fun routeCallSignal(
    from: Connection,
    body: ByteArray,
    registry: ConnectionRegistry,
    mailbox: MailboxStore,
    pushRegistry: PushEndpointStore,
    pushNotifier: PushNotifier,
) {
    val routed = rewrapForRecipient(from, body, TransportFrame.CALL_SIGNAL, "call-signal") ?: return

    val recipient = registry.find(routed.recipientHex)
    val delivered = recipient != null && recipient.outgoing.trySend(routed.frame).isSuccess
    if (delivered) return

    val pushEndpoint = pushRegistry.lookup(routed.recipientHex)
    if (pushEndpoint != null) {
        logger.info("call-signal recipient ${routed.recipientHex} offline, mailboxing + waking via push")
        mailbox.enqueue(routed.recipientHex, routed.frame)
        pushNotifier.notifyAsync(pushEndpoint)
        return
    }

    logger.info("call-signal recipient ${routed.recipientHex} unreachable (no push endpoint), notifying ${from.staticPublicKeyHex}")
    from.outgoing.trySend(TransportFrame.encode(TransportFrame.CALL_UNAVAILABLE, routed.recipientKey))
}

/**
 * Routes a [FILE_TRANSFER][TransportFrame.FILE_TRANSFER] frame live-only, and — unlike
 * [routeEphemeral] — tells the sender when the recipient is unreachable so it can abort the
 * transfer immediately. The relay deliberately never mailboxes or persists these: a file's bytes
 * only ever exist on the two endpoints, transiting here in RAM and never touching disk/Postgres.
 * This is the "файлы хранятся только у сторон, минуя сервер" guarantee.
 */
internal fun routeFileTransfer(from: Connection, body: ByteArray, registry: ConnectionRegistry) {
    val routed = rewrapForRecipient(from, body, TransportFrame.FILE_TRANSFER, "file-transfer") ?: return

    val recipient = registry.find(routed.recipientHex)
    val delivered = recipient != null && recipient.outgoing.trySend(routed.frame).isSuccess
    if (!delivered) {
        // Offline, or its send buffer is full/closed (for a live streamed transfer that's equally
        // "can't keep up right now") — abort rather than park bytes we've promised never to store.
        from.outgoing.trySend(TransportFrame.encode(TransportFrame.FILE_UNAVAILABLE, routed.recipientKey))
    }
}

/**
 * Routes a frame live-only, like [routeMessage] but with no mailbox/push fallback at all — for
 * signals that are worthless once stale (currently just [TransportFrame.TYPING_INDICATOR]).
 * Silently dropped if the recipient isn't connected right now.
 */
internal fun routeEphemeral(from: Connection, body: ByteArray, registry: ConnectionRegistry, frameType: Byte) {
    val routed = rewrapForRecipient(from, body, frameType, "ephemeral") ?: return
    registry.find(routed.recipientHex)?.outgoing?.trySend(routed.frame)
}

private fun publishPreKeys(from: Connection, body: ByteArray, preKeys: PreKeyDirectoryStore) {
    val published = try {
        PreKeyCodec.decodePublished(body)
    } catch (e: Exception) {
        logger.info("dropping malformed prekey publish from ${from.staticPublicKeyHex}")
        return
    }
    // A device may only publish prekeys under its own authenticated identity:
    // its DH identity key IS its Noise transport static key.
    if (published.dhIdentityKey.toHex() != from.staticPublicKeyHex) {
        logger.warn("rejecting prekey publish: identity mismatch from ${from.staticPublicKeyHex}")
        return
    }
    preKeys.publish(from.staticPublicKeyHex, published)
    logger.info("stored prekeys for ${from.staticPublicKeyHex} (${published.oneTimePreKeys.size} one-time)")
}

/** Mints (or fails to) a fresh TURN credential for [from] and answers on its own channel — see [TurnCredentialsIssuer]. */
private suspend fun issueTurnCredentials(from: Connection, turnCredentialsIssuer: TurnCredentialsIssuer?) {
    val credentials = turnCredentialsIssuer?.mint(from.staticPublicKeyHex)
    val body = if (credentials != null) TurnCredentialsCodec.encodeFound(credentials) else TurnCredentialsCodec.encodeNotFound()
    // Suspending send: this is the requester's own channel, same backpressure reasoning as fetchPreKeys.
    from.outgoing.send(TransportFrame.encode(TransportFrame.TURN_CREDENTIALS_RESULT, body))
}

private suspend fun fetchPreKeys(from: Connection, body: ByteArray, preKeys: PreKeyDirectoryStore) {
    if (body.size != 32) {
        logger.info("dropping malformed prekey fetch from ${from.staticPublicKeyHex}")
        return
    }
    val targetHex = body.toHex()
    val bundle = preKeys.fetch(targetHex)
    val resultFrame = if (bundle == null) {
        TransportFrame.encode(TransportFrame.PREKEYS_RESULT, byteArrayOf(TransportFrame.RESULT_NOT_FOUND) + body)
    } else {
        TransportFrame.encode(
            TransportFrame.PREKEYS_RESULT,
            byteArrayOf(TransportFrame.RESULT_FOUND) + body + PreKeyCodec.encodeBundle(bundle),
        )
    }
    // Suspending send: this is the requester's own channel, so blocking their
    // read loop until their writer drains is exactly the backpressure we want
    // (a client flooding FETCH_PREKEYS throttles only itself).
    from.outgoing.send(resultFrame)
}
