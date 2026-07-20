package messenger.common.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.BadPaddingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import messenger.common.e2ee.CallSignal
import messenger.common.e2ee.EditSignal
import messenger.common.e2ee.FileSignal
import messenger.common.e2ee.DeviceIdentity
import messenger.common.e2ee.E2eeManager
import messenger.common.e2ee.E2eeMessage
import messenger.common.e2ee.PreKeyBundle
import messenger.common.e2ee.PreKeyCodec
import messenger.common.e2ee.PreKeyStore
import messenger.common.e2ee.ReactionSignal
import messenger.common.e2ee.TurnCredentials
import messenger.common.e2ee.TurnCredentialsCodec
import messenger.common.crypto.Ed25519Signatures
import messenger.common.group.GROUP_ID_LENGTH
import messenger.common.group.GroupCiphertextMessage
import messenger.common.group.GroupControlEvent
import messenger.common.group.GroupCryptoSession
import messenger.common.group.GroupInvite
import messenger.common.group.GroupSenderKeyMessage
import messenger.common.protocol.RoutingEnvelope
import messenger.common.protocol.TransportFrame
import messenger.common.transport.NoiseIkInitiatorHandshake
import messenger.common.transport.NoiseTransportSession
import messenger.common.util.toHex
import org.slf4j.LoggerFactory

data class IncomingMessage(
    val senderDhIdentityKey: ByteArray,
    val senderDisplayName: String,
    val senderAvatarIcon: Int,
    val plaintext: ByteArray,
    val messageId: ByteArray,
    val replyTo: ApplicationMessage.ReplyReference? = null,
    val linkPreview: ApplicationMessage.LinkPreviewRef? = null,
    val voiceAttachment: ApplicationMessage.VoiceAttachmentRef? = null,
)

/** [messageId] is the hex ID returned by the [MessengerClient.sendMessage] call that produced the now-delivered message. */
data class DeliveryAck(val peerDhIdentityKey: ByteArray, val messageId: String)

/** A call-signaling event, either an authenticated [Signal] from the peer or a relay-reported [Unavailable]. */
sealed class IncomingCallSignal {
    data class Signal(val peerDhIdentityKey: ByteArray, val callId: UUID, val kind: Byte, val payload: String) : IncomingCallSignal()
    data class Unavailable(val peerDhIdentityKey: ByteArray) : IncomingCallSignal()
}

/** A file-transfer event, either an authenticated [Signal] from the peer or a relay-reported [Unavailable] (recipient offline). */
sealed class IncomingFileSignal {
    data class Signal(val peerDhIdentityKey: ByteArray, val fileId: UUID, val kind: Byte, val payload: ByteArray) : IncomingFileSignal()
    data class Unavailable(val peerDhIdentityKey: ByteArray) : IncomingFileSignal()
}

/** [emoji] empty means the peer cleared their reaction on [messageId]. */
data class IncomingReaction(val peerDhIdentityKey: ByteArray, val messageId: String, val emoji: String)

data class IncomingMessageEdit(val peerDhIdentityKey: ByteArray, val messageId: String, val newText: String)

/**
 * A decrypted group message — see [MessengerClient.sendGroupMessage] for how [senderDhIdentityKey]'s
 * copy of this was produced. [epoch]/[counter] are the sender's chain position this message was
 * encrypted at (see [messenger.common.group.GroupCiphertextMessage]) — together with
 * [senderDhIdentityKey] they're a stable, deterministic identity for this exact message, unlike a
 * freshly-random id, so a caller can dedupe a mailbox redelivery of the same message instead of
 * showing it twice.
 */
data class IncomingGroupMessage(
    val groupId: ByteArray,
    val senderDhIdentityKey: ByteArray,
    val plaintext: ByteArray,
    val epoch: Int,
    val counter: Int,
)

/** A signed group membership/role event received from [senderDhIdentityKey] — the caller feeds it into that group's [messenger.common.group.GroupControlLog]. */
data class IncomingGroupControlEvent(val senderDhIdentityKey: ByteArray, val event: GroupControlEvent)

/** [senderDhIdentityKey] has a different control-log head ([headHash]) than us for [groupId] and wants the events we have past theirs — see [MessengerClient.sendGroupControlSyncRequest]. */
data class IncomingGroupSyncRequest(val senderDhIdentityKey: ByteArray, val groupId: ByteArray, val headHash: ByteArray)

/**
 * [senderDhIdentityKey] opened an invite link/QR and is asking us (the inviter named inside
 * [invite]) to add them. [invite] is the actual signed, expiring invite [messenger.common.group.GroupInvite]
 * the joiner is holding — carried here (not just a bare groupId) so the caller can re-verify its
 * signature/expiry/self-identity itself rather than trusting that the sender genuinely held a valid
 * invite; see `GroupManager.onJoinRequest`.
 */
data class IncomingGroupJoinRequest(val senderDhIdentityKey: ByteArray, val invite: GroupInvite.Invite)

/**
 * A device's connection to one relay: performs the Noise_IK transport
 * handshake, publishes/fetches prekeys, and runs the 1:1 E2EE layer
 * ([E2eeManager]) on top so callers only ever see plaintext in and out.
 *
 * Platform-agnostic on purpose — it only needs an [HttpClient] with the
 * WebSockets plugin installed, so the same class runs unmodified on the
 * desktop test harness and on Android (with an OkHttp/CIO engine chosen by
 * the caller). No server-side dependencies here.
 *
 * Not thread-safe beyond what coroutines give for free: [connect] must
 * complete before [sendMessage] / [publishPreKeys] are called.
 */
class MessengerClient(
    val identity: DeviceIdentity,
    private val httpClient: HttpClient,
    @Volatile var displayName: String = identity.dhIdentityPublicKey.toHex().take(8),
    @Volatile var avatarIcon: Int = 0,
    // Same shape as DeviceIdentity.loadOrCreate's byte-array variant: callers that want the signed
    // prekey/one-time prekeys to survive a restart (e.g. Android, backed by SecureStore) supply
    // these; callers that don't (the desktop test harness) get today's fully in-memory behavior.
    loadPersistedPreKeys: () -> ByteArray? = { null },
    persistPreKeys: (ByteArray) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger("messenger.common.client.MessengerClient.${identity.dhIdentityPublicKey.toHex().take(8)}")

    private val preKeyStore = PreKeyStore(identity, loadPersistedPreKeys, persistPreKeys)
    private val e2ee = E2eeManager(identity, preKeyStore)

    // sendMessage/sendCallSignal can now be called concurrently from independent coroutines for
    // the same peer (call signaling fires off one coroutine per outgoing ICE candidate, alongside
    // ring/answer/hangup) — e2ee.encrypt() mutates a per-peer SendingChain's counter/chain key
    // with no internal synchronization, so unguarded concurrent encrypts could hand out the same
    // (key, nonce) pair twice or desync the message counter the receiver expects. This mutex makes
    // "check session, encrypt" atomic; the (potentially slow) prekey-bundle fetch beforehand stays
    // outside it so one send doesn't block others waiting on the network.
    private val encryptMutex = Mutex()

    private lateinit var session: DefaultClientWebSocketSession
    private lateinit var transportSession: NoiseTransportSession

    private val outgoing = Channel<ByteArray>(capacity = 64)
    private val pendingFetches = ConcurrentHashMap<String, CompletableDeferred<PreKeyBundle?>>()

    // Only one call is ever active at a time (see CallManager), so a single in-flight slot is
    // enough -- a second concurrent fetchTurnCredentials() call just awaits the same request
    // rather than firing a duplicate one at the relay.
    private val turnCredentialsMutex = Mutex()
    private var pendingTurnCredentialsFetch: CompletableDeferred<TurnCredentials?>? = null

    private val mutableIncomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<IncomingMessage> = mutableIncomingMessages

    private val mutableDeliveryAcks = MutableSharedFlow<DeliveryAck>(extraBufferCapacity = 64)
    val deliveryAcks: SharedFlow<DeliveryAck> = mutableDeliveryAcks

    private val mutableReadAcks = MutableSharedFlow<DeliveryAck>(extraBufferCapacity = 64)
    val readAcks: SharedFlow<DeliveryAck> = mutableReadAcks

    /** Emits the sender's DH identity key each time a [TYPING_INDICATOR][messenger.common.protocol.TransportFrame.TYPING_INDICATOR] arrives. */
    private val mutableTypingIndicators = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val typingIndicators: SharedFlow<ByteArray> = mutableTypingIndicators

    private val mutableCallSignals = MutableSharedFlow<IncomingCallSignal>(extraBufferCapacity = 64)
    val callSignals: SharedFlow<IncomingCallSignal> = mutableCallSignals

    // Bigger buffer than the others: a file transfer streams many CHUNK/ACK signals in quick
    // succession, and dropping one to buffer overflow would stall or corrupt the transfer.
    private val mutableFileSignals = MutableSharedFlow<IncomingFileSignal>(extraBufferCapacity = 256)
    val fileSignals: SharedFlow<IncomingFileSignal> = mutableFileSignals

    private val mutableReactions = MutableSharedFlow<IncomingReaction>(extraBufferCapacity = 64)
    val reactions: SharedFlow<IncomingReaction> = mutableReactions

    private val mutableMessageEdits = MutableSharedFlow<IncomingMessageEdit>(extraBufferCapacity = 64)
    val messageEdits: SharedFlow<IncomingMessageEdit> = mutableMessageEdits

    /**
     * Emits a peer's DH identity key each time they tell us they lost their session with us (see
     * [handleSessionResetNotice]) — dropping our own side is not enough to recover on its own:
     * whatever message we most recently sent under the now-stale session already failed to
     * decrypt on their end and is gone for good, so callers should resend their latest
     * pending/unacknowledged message(s) to this peer once this fires, or that message is simply
     * lost until the user happens to send another one later.
     */
    private val mutableSessionResetNotices = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val sessionResetNotices: SharedFlow<ByteArray> = mutableSessionResetNotices

    // Pragmatic "sender keys" group E2EE, not the full RFC 9420 MLS/TreeKEM protocol the roadmap
    // originally named — see GroupCryptoSession's own doc for why. Never persisted, same as the
    // 1:1 ratchet's ephemeral ReceivingChain/SendingChain state; a device that loses this (app
    // restart) recovers via GROUP_KEY_REQUEST instead of a snapshot-to-disk scheme.
    private val groupSessions = ConcurrentHashMap<String, GroupCryptoSession>()

    private val mutableGroupMessages = MutableSharedFlow<IncomingGroupMessage>(extraBufferCapacity = 64)
    val groupMessages: SharedFlow<IncomingGroupMessage> = mutableGroupMessages

    private val mutableGroupControlEvents = MutableSharedFlow<IncomingGroupControlEvent>(extraBufferCapacity = 64)
    val groupControlEvents: SharedFlow<IncomingGroupControlEvent> = mutableGroupControlEvents

    private val mutableGroupSyncRequests = MutableSharedFlow<IncomingGroupSyncRequest>(extraBufferCapacity = 64)
    val groupSyncRequests: SharedFlow<IncomingGroupSyncRequest> = mutableGroupSyncRequests

    private val mutableGroupJoinRequests = MutableSharedFlow<IncomingGroupJoinRequest>(extraBufferCapacity = 64)
    val groupJoinRequests: SharedFlow<IncomingGroupJoinRequest> = mutableGroupJoinRequests

    private val random = SecureRandom()

    @Volatile private var closingIntentionally = false

    // Fires once if the socket dies on its own (ping timeout, network drop, relay restart) —
    // never fires on a deliberate close(), so callers can use it as a "please reconnect" signal.
    private val mutableConnectionLost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val connectionLost: SharedFlow<Unit> = mutableConnectionLost

    /**
     * Connects to [relayUrl] (e.g. "ws://127.0.0.1:8080/v1/connect"), pinning [relayStaticPublicKey].
     *
     * [encodeOutgoing]/[decodeIncoming] wrap/peel every raw frame at the very outside of this
     * pipe, entirely underneath the Noise handshake and transport encryption below — they're the
     * hook an [messenger.common.onion.OnionCircuit] uses to tunnel this same connection through
     * onion hops, without this class needing to know that onion routing exists. Left as identity
     * functions, [relayUrl] is connected to directly, exactly as before.
     */
    suspend fun connect(
        relayUrl: String,
        relayStaticPublicKey: ByteArray,
        scope: CoroutineScope,
        encodeOutgoing: (ByteArray) -> ByteArray = { it },
        decodeIncoming: (ByteArray) -> ByteArray = { it },
    ) {
        closingIntentionally = false
        session = httpClient.webSocketSession(relayUrl)

        val initiator = NoiseIkInitiatorHandshake(identity.dhIdentity, relayStaticPublicKey)
        session.send(Frame.Binary(fin = true, data = encodeOutgoing(initiator.createMessage1())))
        val firstReply = session.incoming.receive()
        require(firstReply is Frame.Binary) { "expected binary Noise_IK reply, got $firstReply" }
        transportSession = initiator.consumeMessage2(decodeIncoming(firstReply.readBytes()))

        val writerJob = scope.launch { writerLoop(encodeOutgoing) }
        val readerJob = scope.launch { readerLoop(decodeIncoming) }
        scope.launch {
            readerJob.join()
            writerJob.cancel()
            if (!closingIntentionally) {
                logger.warn("connection to relay lost")
                mutableConnectionLost.emit(Unit)
            }
        }
        logger.info("connected to relay, device key ${identity.dhIdentityPublicKey.toHex()}")
    }

    // A dropped/reset socket surfaces as an exception out of `session.incoming`/`session.send`,
    // not a clean channel close. Left uncaught, that exception is fatal to whatever dispatcher
    // ran the coroutine — on Android with no CoroutineExceptionHandler installed, that means the
    // whole process gets killed instead of the caller's `connectionLost` reconnect path running.
    // CancellationException must still propagate so normal coroutine cancellation isn't swallowed.
    private suspend fun writerLoop(encodeOutgoing: (ByteArray) -> ByteArray) {
        try {
            for (frame in outgoing) {
                session.send(Frame.Binary(fin = true, data = encodeOutgoing(transportSession.encrypt(frame))))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("writer loop ended: ${e.message}")
        }
    }

    private suspend fun readerLoop(decodeIncoming: (ByteArray) -> ByteArray) {
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Binary) continue
                val plaintext = try {
                    transportSession.decrypt(decodeIncoming(frame.readBytes()))
                } catch (e: BadPaddingException) {
                    logger.warn("dropping transport frame that failed to authenticate")
                    continue
                }
                val decoded = TransportFrame.decode(plaintext)
                when (decoded.type) {
                    TransportFrame.ROUTE -> handleIncomingRoute(decoded.body)
                    TransportFrame.PREKEYS_RESULT -> handlePreKeysResult(decoded.body)
                    TransportFrame.SESSION_RESET_NOTICE -> handleSessionResetNotice(decoded.body)
                    TransportFrame.DELIVERY_ACK -> handleDeliveryAck(decoded.body)
                    TransportFrame.READ_ACK -> handleReadAck(decoded.body)
                    TransportFrame.TYPING_INDICATOR -> handleTypingIndicator(decoded.body)
                    TransportFrame.CALL_SIGNAL -> handleIncomingCallSignal(decoded.body)
                    TransportFrame.CALL_UNAVAILABLE -> handleCallUnavailable(decoded.body)
                    TransportFrame.FILE_TRANSFER -> handleIncomingFileSignal(decoded.body)
                    TransportFrame.FILE_UNAVAILABLE -> handleFileUnavailable(decoded.body)
                    TransportFrame.REACTION -> handleReaction(decoded.body)
                    TransportFrame.EDIT_MESSAGE -> handleMessageEdit(decoded.body)
                    TransportFrame.GROUP_SENDER_KEY -> handleGroupSenderKey(decoded.body)
                    TransportFrame.GROUP_MESSAGE -> handleGroupMessage(decoded.body)
                    TransportFrame.GROUP_KEY_REQUEST -> handleGroupKeyRequest(decoded.body)
                    TransportFrame.GROUP_CONTROL_EVENT -> handleGroupControlEvent(decoded.body)
                    TransportFrame.GROUP_CONTROL_SYNC_REQUEST -> handleGroupSyncRequest(decoded.body)
                    TransportFrame.GROUP_JOIN_REQUEST -> handleGroupJoinRequest(decoded.body)
                    TransportFrame.TURN_CREDENTIALS_RESULT -> handleTurnCredentialsResult(decoded.body)
                    else -> logger.warn("ignoring unexpected frame type ${decoded.type}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("reader loop ended: ${e.message}")
        }
    }

    /** Decodes a [RoutingEnvelope], logging and returning null (frame dropped) on malformed input — the shared first step of every incoming handler. */
    private fun decodeEnvelopeOrNull(body: ByteArray, label: String): RoutingEnvelope.Decoded? = try {
        RoutingEnvelope.decode(body)
    } catch (e: Exception) {
        logger.warn("dropping $label with malformed envelope", e)
        null
    }

    /**
     * Shared "decode envelope → decode [E2eeMessage] → [E2eeManager.decrypt] → [decode] the
     * plaintext" skeleton behind every incoming E2EE signal handler except [handleIncomingRoute],
     * which needs its own [E2eeManager.NoSessionException]-vs-anything-else distinction to decide
     * whether a session-reset notice is warranted. Logs and returns null (frame dropped) on any
     * failure along the way.
     */
    private fun <T> decryptEnvelope(body: ByteArray, label: String, decode: (ByteArray) -> T): Pair<RoutingEnvelope.Decoded, T>? {
        val envelope = decodeEnvelopeOrNull(body, label) ?: return null
        return try {
            val message = E2eeMessage.decode(envelope.payload)
            val plaintext = e2ee.decrypt(envelope.peerStaticPublicKey, message)
            envelope to decode(plaintext)
        } catch (e: Exception) {
            logger.warn("dropping undecryptable $label from ${envelope.peerStaticPublicKey.toHex()}", e)
            null
        }
    }

    /** Shared "must be exactly a peer key, nothing else" shape behind [handleCallUnavailable]/[handleFileUnavailable] — both relay-generated, plaintext, unauthenticated-beyond-routing notices. */
    private fun <T> handleUnavailable(body: ByteArray, label: String, flow: MutableSharedFlow<T>, wrap: (ByteArray) -> T) {
        if (body.size != RoutingEnvelope.PEER_KEY_LENGTH) {
            logger.warn("dropping malformed $label")
            return
        }
        flow.tryEmit(wrap(body))
    }

    private suspend fun handleIncomingRoute(body: ByteArray) {
        val envelope = decodeEnvelopeOrNull(body, "route frame") ?: return
        val application = try {
            val message = E2eeMessage.decode(envelope.payload)
            val plaintext = e2ee.decrypt(envelope.peerStaticPublicKey, message)
            ApplicationMessage.decode(plaintext)
        } catch (e: E2eeManager.NoSessionException) {
            // We hold no session at all for this peer — legitimately happens if the sender's
            // session predates a restart on our side (e.g. app reinstall). Tell them so they drop
            // their now-stale session too and re-initiate X3DH on their next send.
            logger.warn("no session with ${envelope.peerStaticPublicKey.toHex()}, notifying sender to reset", e)
            sendSessionResetNotice(envelope.peerStaticPublicKey)
            return
        } catch (e: Exception) {
            // SECURITY: an AEAD tag failure against an *existing* session (as opposed to
            // NoSessionException above) is exactly what a relay bit-flipping one byte of ciphertext
            // — or the ratchet header, which the AEAD tag doesn't cover — produces on purpose. It
            // does NOT mean the session is desynced. Treating it as "reset the whole session" would
            // let any relay force unlimited forced re-handshakes on any conversation at will, for
            // the cost of corrupting a single byte. Just drop this one message.
            logger.warn("dropping undecryptable message from ${envelope.peerStaticPublicKey.toHex()}", e)
            return
        }
        mutableIncomingMessages.emit(
            IncomingMessage(
                envelope.peerStaticPublicKey,
                application.senderDisplayName,
                application.senderAvatarIcon,
                application.body,
                application.messageId,
                application.replyTo,
                application.linkPreview,
                application.voiceAttachment,
            ),
        )
        sendDeliveryAck(envelope.peerStaticPublicKey, application.messageId)
    }

    // SECURITY: acks used to ride the plain RoutingEnvelope — just [peer key][messageId], never
    // wrapped in an E2eeMessage/passed through the AEAD ratchet. That gave a malicious relay (already
    // in scope of this app's threat model) a way to forge a DELIVERY_ACK/READ_ACK for any messageId
    // at any time; the victim had no way to tell it apart from a real one. Encrypting the messageId
    // the same way sendTypingIndicator does — a session is guaranteed to already exist, since an ack
    // only ever follows a message we just genuinely decrypted from that peer — means a forged ack now
    // has to forge a valid ratchet ciphertext, which the relay can't do.
    private suspend fun sendDeliveryAck(peerDhIdentityKey: ByteArray, messageId: ByteArray) {
        val message = encryptMutex.withLock { e2ee.encrypt(peerDhIdentityKey, null, messageId) }
        val envelope = RoutingEnvelope.encode(peerDhIdentityKey, message.encode())
        outgoing.send(TransportFrame.encode(TransportFrame.DELIVERY_ACK, envelope))
    }

    private fun handleDeliveryAck(body: ByteArray) = handleAck(body, "delivery ack", mutableDeliveryAcks)

    /** Tells [peerDhIdentityKey] that [messageId] has actually been shown on screen — see [TransportFrame.READ_ACK]. */
    suspend fun sendReadAck(peerDhIdentityKey: ByteArray, messageId: ByteArray) {
        val message = encryptMutex.withLock { e2ee.encrypt(peerDhIdentityKey, null, messageId) }
        val envelope = RoutingEnvelope.encode(peerDhIdentityKey, message.encode())
        outgoing.send(TransportFrame.encode(TransportFrame.READ_ACK, envelope))
    }

    private fun handleReadAck(body: ByteArray) = handleAck(body, "read ack", mutableReadAcks)

    /** Delivery and read acks share one wire shape (peer key + message id) and differ only in which flow they land on. */
    private fun handleAck(body: ByteArray, label: String, flow: MutableSharedFlow<DeliveryAck>) {
        val (envelope, messageId) = decryptEnvelope(body, label) { it } ?: return
        if (messageId.size != ApplicationMessage.MESSAGE_ID_LENGTH) {
            logger.warn("dropping $label with wrong-size message id")
            return
        }
        flow.tryEmit(DeliveryAck(envelope.peerStaticPublicKey, messageId.toHex()))
    }

    /**
     * Best-effort "I'm typing" ping to [peerDhIdentityKey]. Silently does nothing without an
     * already-established E2EE session — never worth fetching a prekey bundle / starting X3DH
     * just for this, unlike [sendMessage]/[sendCallSignal].
     */
    suspend fun sendTypingIndicator(peerDhIdentityKey: ByteArray) {
        if (!e2ee.hasSession(peerDhIdentityKey)) return
        val message = encryptMutex.withLock { e2ee.encrypt(peerDhIdentityKey, null, ByteArray(0)) }
        val envelope = RoutingEnvelope.encode(peerDhIdentityKey, message.encode())
        outgoing.send(TransportFrame.encode(TransportFrame.TYPING_INDICATOR, envelope))
    }

    // No session-reset notice on failure here (unlike handleIncomingRoute) — losing one typing
    // ping to a session mismatch isn't worth the reset dance a real message loss is.
    private fun handleTypingIndicator(body: ByteArray) {
        val (envelope, _) = decryptEnvelope(body, "typing indicator") { } ?: return
        mutableTypingIndicators.tryEmit(envelope.peerStaticPublicKey)
    }

    // SECURITY (2026-07-21): this used to carry an empty payload — just [peer key] with nothing
    // proving who sent it. Unlike ROUTE/DELIVERY_ACK/etc., it can't be wrapped in e2ee.encrypt()
    // (the whole reason we're sending it is that we hold *no* session with the recipient to
    // encrypt under), so a malicious relay could forge this frame directly into any device's
    // outgoing queue/mailbox with an arbitrary claimed sender, and handleSessionResetNotice would
    // unconditionally drop that device's real session with the named peer — an at-will forced
    // re-handshake DoS against any pair, no cryptographic capability needed. Signing with our own
    // identity signing key instead — verified by the recipient against their already-pinned copy
    // of it (same TOFU pin X3DH/GroupControlLog already rely on) — means forging this now requires
    // that private key, which a relay never holds.
    private suspend fun sendSessionResetNotice(peerDhIdentityKey: ByteArray) {
        val signature = Ed25519Signatures.sign(identity.signingIdentity.privateKey, peerDhIdentityKey)
        val envelope = RoutingEnvelope.encode(peerDhIdentityKey, signature)
        outgoing.send(TransportFrame.encode(TransportFrame.SESSION_RESET_NOTICE, envelope))
    }

    private suspend fun handleSessionResetNotice(body: ByteArray) {
        val envelope = decodeEnvelopeOrNull(body, "session-reset notice") ?: return
        val signingKey = e2ee.pinnedSigningIdentityKey(envelope.peerStaticPublicKey)
        if (signingKey == null || !Ed25519Signatures.verify(signingKey, identity.dhIdentityPublicKey, envelope.payload)) {
            logger.warn("dropping session-reset notice from ${envelope.peerStaticPublicKey.toHex()}: missing/invalid signature")
            return
        }
        logger.info("peer ${envelope.peerStaticPublicKey.toHex()} lost its session with us; dropping ours too")
        e2ee.dropSession(envelope.peerStaticPublicKey)
        mutableSessionResetNotices.emit(envelope.peerStaticPublicKey)
    }

    // SECURITY: a malformed/truncated relay-controlled frame here used to throw straight out of
    // readerLoop's `when` dispatch (via `require`/an unchecked PreKeyCodec.decodeBundle read) —
    // readerLoop's own catch(Exception) wraps the *whole* `for` loop, not each frame, so one bad
    // PREKEYS_RESULT killed the entire reader coroutine (and the connection) instead of just being
    // dropped, unlike every sibling relay-controlled handler (handleCallUnavailable etc.).
    private fun handlePreKeysResult(body: ByteArray) {
        if (body.size < 33) {
            logger.warn("dropping truncated prekeys result")
            return
        }
        val found = body[0] == TransportFrame.RESULT_FOUND
        val targetKey = body.copyOfRange(1, 33)
        val deferred = pendingFetches.remove(targetKey.toHex()) ?: return
        if (!found) {
            deferred.complete(null)
            return
        }
        val bundle = try {
            PreKeyCodec.decodeBundle(body.copyOfRange(33, body.size))
        } catch (e: Exception) {
            logger.warn("dropping malformed prekeys result", e)
            deferred.complete(null)
            return
        }
        deferred.complete(bundle)
    }

    suspend fun publishPreKeys(oneTimeCount: Int = 20) {
        val published = preKeyStore.publishedPreKeys(oneTimeCount)
        outgoing.send(TransportFrame.encode(TransportFrame.PUBLISH_PREKEYS, PreKeyCodec.encodePublished(published)))
    }

    // SECURITY/RELIABILITY: only handlePreKeysResult's happy path removed this entry — a relay
    // that simply never answers a given FETCH_PREKEYS (or answers after the 10s timeout already
    // fired) left it in `pendingFetches` forever, a slow memory leak bounded only by how many
    // distinct peers this device ever tries to message while stonewalled. `finally` now always
    // cleans up regardless of how this call ends.
    private suspend fun fetchBundle(peerDhIdentityKey: ByteArray): PreKeyBundle? {
        val hex = peerDhIdentityKey.toHex()
        val deferred = CompletableDeferred<PreKeyBundle?>()
        pendingFetches[hex] = deferred
        try {
            outgoing.send(TransportFrame.encode(TransportFrame.FETCH_PREKEYS, peerDhIdentityKey))
            return withTimeout(10_000) { deferred.await() }
        } finally {
            pendingFetches.remove(hex, deferred)
        }
    }

    /**
     * The shared tail of every authenticated send: ensure an E2EE session exists (fetching the
     * peer's prekey bundle for a first contact — deliberately *outside* [encryptMutex], so one
     * send's slow network fetch doesn't block others), encrypt under the mutex, envelope, and
     * queue as a [frameType] transport frame.
     */
    private suspend fun encryptAndSend(peerDhIdentityKey: ByteArray, frameType: Byte, plaintext: ByteArray) {
        val bundle = if (!e2ee.hasSession(peerDhIdentityKey)) {
            fetchBundle(peerDhIdentityKey)
                ?: throw NoSuchElementException("no prekey bundle published for ${peerDhIdentityKey.toHex()}")
        } else {
            null
        }
        val message = encryptMutex.withLock { e2ee.encrypt(peerDhIdentityKey, bundle, plaintext) }
        val envelope = RoutingEnvelope.encode(peerDhIdentityKey, message.encode())
        outgoing.send(TransportFrame.encode(frameType, envelope))
    }

    /**
     * Encrypts and sends [plaintext] to [peerDhIdentityKey], fetching a prekey bundle first if
     * needed. Returns the hex message ID to correlate against a later [deliveryAcks] emission.
     */
    suspend fun sendMessage(
        peerDhIdentityKey: ByteArray,
        plaintext: ByteArray,
        replyTo: ApplicationMessage.ReplyReference? = null,
        linkPreview: ApplicationMessage.LinkPreviewRef? = null,
        voiceAttachment: ApplicationMessage.VoiceAttachmentRef? = null,
    ): String {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH).also { random.nextBytes(it) }
        encryptAndSend(
            peerDhIdentityKey,
            TransportFrame.ROUTE,
            ApplicationMessage.encode(displayName, avatarIcon, messageId, plaintext, replyTo, linkPreview, voiceAttachment),
        )
        return messageId.toHex()
    }

    /**
     * Encrypts and sends a call-signaling [kind]/[payload] (SDP offer/answer, ICE candidate, or
     * hangup reason) to [peerDhIdentityKey], fetching a prekey bundle first if needed. Unlike
     * [sendMessage], this skips the [ApplicationMessage] envelope (no display name/avatar/message
     * ID is meaningful here) and does not expect a delivery ack — the ring/answer/hangup state
     * machine is its own liveness signal.
     */
    suspend fun sendCallSignal(peerDhIdentityKey: ByteArray, callId: UUID, kind: Byte, payload: String) =
        encryptAndSend(peerDhIdentityKey, TransportFrame.CALL_SIGNAL, CallSignal.encode(callId, kind, payload))

    /**
     * Encrypts and sends one file-transfer signal (offer/accept/chunk/ack/complete/cancel) to
     * [peerDhIdentityKey]. Same E2EE pipe as [sendMessage]/[sendCallSignal], but wrapped in a
     * [FILE_TRANSFER][TransportFrame.FILE_TRANSFER] frame the relay routes live-only and never
     * stores — see [messenger.common.e2ee.FileSignal].
     */
    suspend fun sendFileSignal(peerDhIdentityKey: ByteArray, fileId: UUID, kind: Byte, payload: ByteArray = ByteArray(0)) =
        encryptAndSend(peerDhIdentityKey, TransportFrame.FILE_TRANSFER, FileSignal.encode(fileId, kind, payload))

    /**
     * Sets (or, sending [emoji] = `""`, clears) this device's reaction on the message identified by
     * [messageId] (as returned by an earlier [sendMessage]/[IncomingMessage.messageId]) in
     * [peerDhIdentityKey]'s conversation. Fetches a prekey bundle first if no session exists yet —
     * unlike an ack, a reaction can plausibly be the first thing sent after a session was dropped.
     */
    suspend fun sendReaction(peerDhIdentityKey: ByteArray, messageId: ByteArray, emoji: String) =
        encryptAndSend(peerDhIdentityKey, TransportFrame.REACTION, ReactionSignal.encode(messageId, emoji))

    /** Replaces the text of an earlier message (identified by [messageId]) sent to [peerDhIdentityKey] with [newText]. */
    suspend fun sendMessageEdit(peerDhIdentityKey: ByteArray, messageId: ByteArray, newText: String) =
        encryptAndSend(peerDhIdentityKey, TransportFrame.EDIT_MESSAGE, EditSignal.encode(messageId, newText))

    /**
     * Starts (or restarts, on a membership change) this device's own sender-key epoch for
     * [groupId] and returns the message to hand to [sendGroupSenderKey] for every current member —
     * including ones who already had a previous epoch, since everyone's copy of "my" chain must
     * move together. Purely local/synchronous; nothing is sent until the caller distributes the
     * result.
     */
    fun createOrRekeyGroup(groupId: ByteArray): GroupSenderKeyMessage =
        groupSessions.getOrPut(groupId.toHex()) { GroupCryptoSession(groupId, identity.dhIdentityPublicKey) }.rekeySelf()

    /** Sends this device's sender key (from [createOrRekeyGroup]) to one member over their existing pairwise E2EE session, fetching a prekey bundle first if needed — call once per current member. */
    suspend fun sendGroupSenderKey(memberDhIdentityKey: ByteArray, senderKey: GroupSenderKeyMessage) =
        encryptAndSend(memberDhIdentityKey, TransportFrame.GROUP_SENDER_KEY, senderKey.encode())

    /**
     * Encrypts [plaintext] once under this device's own group sender-key chain and fans it out
     * individually (still pairwise E2EE per recipient) to every key in [memberDhIdentityKeys] —
     * the group ciphertext itself is identical for all of them, so this only ever does one group
     * encryption regardless of group size.
     */
    suspend fun sendGroupMessage(groupId: ByteArray, memberDhIdentityKeys: List<ByteArray>, plaintext: ByteArray) {
        val session = groupSessions[groupId.toHex()]
            ?: throw IllegalStateException("no group session for ${groupId.toHex()} -- call createOrRekeyGroup first")
        val encoded = session.encrypt(plaintext).encode()
        for (memberKey in memberDhIdentityKeys) {
            encryptAndSend(memberKey, TransportFrame.GROUP_MESSAGE, encoded)
        }
    }

    private suspend fun handleGroupSenderKey(body: ByteArray) {
        val (envelope, decoded) = decryptEnvelope(body, "group sender-key frame") { GroupSenderKeyMessage.decode(it) } ?: return
        groupSessions.getOrPut(decoded.groupId.toHex()) { GroupCryptoSession(decoded.groupId, identity.dhIdentityPublicKey) }
            .receiveSenderKey(envelope.peerStaticPublicKey, decoded)
    }

    private suspend fun handleGroupMessage(body: ByteArray) {
        val (envelope, decoded) = decryptEnvelope(body, "group message frame") { GroupCiphertextMessage.decode(it) } ?: return
        val session = groupSessions.getOrPut(decoded.groupId.toHex()) { GroupCryptoSession(decoded.groupId, identity.dhIdentityPublicKey) }
        val plaintext = try {
            session.decrypt(envelope.peerStaticPublicKey, decoded)
        } catch (e: Exception) {
            // Most likely: we never received (or lost, e.g. an app restart with no persisted
            // group-session state, same as the 1:1 ratchet) this sender's current sender key, or
            // they've since rekeyed past what we have — ask them to resend their current one
            // instead of silently dropping every message from them until an unrelated rekey fixes
            // it by coincidence.
            logger.warn("dropping undecryptable group message from ${envelope.peerStaticPublicKey.toHex()}, requesting their current key", e)
            runCatching { sendGroupKeyRequest(envelope.peerStaticPublicKey, decoded.groupId) }
            return
        }
        mutableGroupMessages.emit(IncomingGroupMessage(decoded.groupId, envelope.peerStaticPublicKey, plaintext, decoded.epoch, decoded.counter))
    }

    private suspend fun sendGroupKeyRequest(peerDhIdentityKey: ByteArray, groupId: ByteArray) =
        encryptAndSend(peerDhIdentityKey, TransportFrame.GROUP_KEY_REQUEST, groupId)

    /** Resends this device's current group sender key to whoever asked, if we actually have a session (and thus a key) for the group they named — a stale/forged request naming an unknown group is silently ignored. */
    private suspend fun handleGroupKeyRequest(body: ByteArray) {
        val (envelope, groupId) = decryptEnvelope(body, "group key request") { it } ?: return
        val currentKey = groupSessions[groupId.toHex()]?.currentSenderKeyMessageOrNull() ?: return
        runCatching { sendGroupSenderKey(envelope.peerStaticPublicKey, currentKey) }
    }

    /** Sends one signed group control event (see [GroupControlEvent]) to a single member over their pairwise E2EE session — the caller fans a new event out by calling this once per current member. */
    suspend fun sendGroupControlEvent(memberDhIdentityKey: ByteArray, event: GroupControlEvent) =
        encryptAndSend(memberDhIdentityKey, TransportFrame.GROUP_CONTROL_EVENT, event.encode())

    /** Asks [memberDhIdentityKey] to send us every control event they have past [headHash] for [groupId] — used when an incoming event/message references a head we don't recognize (see the control-log sync design). */
    suspend fun sendGroupControlSyncRequest(memberDhIdentityKey: ByteArray, groupId: ByteArray, headHash: ByteArray) =
        encryptAndSend(memberDhIdentityKey, TransportFrame.GROUP_CONTROL_SYNC_REQUEST, groupId + headHash)

    /**
     * Asks [invite]'s inviter to add us to its group — establishes a pairwise session first if this
     * is a first contact, exactly like sending them any other message would. The whole signed
     * [invite] travels along (not just the groupId) so the inviter can re-verify it themselves
     * instead of trusting that we genuinely held a valid one; see [handleGroupJoinRequest].
     */
    suspend fun sendGroupJoinRequest(inviterDhIdentityKey: ByteArray, invite: GroupInvite.Invite) =
        encryptAndSend(inviterDhIdentityKey, TransportFrame.GROUP_JOIN_REQUEST, invite.encode())

    private suspend fun handleGroupControlEvent(body: ByteArray) {
        val (envelope, event) = decryptEnvelope(body, "group control event") { GroupControlEvent.decode(it) } ?: return
        mutableGroupControlEvents.emit(IncomingGroupControlEvent(envelope.peerStaticPublicKey, event))
    }

    private suspend fun handleGroupSyncRequest(body: ByteArray) {
        val (envelope, payload) = decryptEnvelope(body, "group sync request") { it } ?: return
        if (payload.size != GROUP_ID_LENGTH + 32) {
            logger.warn("dropping group sync request with wrong-size body")
            return
        }
        val groupId = payload.copyOfRange(0, GROUP_ID_LENGTH)
        val headHash = payload.copyOfRange(GROUP_ID_LENGTH, payload.size)
        mutableGroupSyncRequests.emit(IncomingGroupSyncRequest(envelope.peerStaticPublicKey, groupId, headHash))
    }

    /**
     * Asks the relay to mint a fresh, short-lived TURN username/password for a call about to
     * start — see [TransportFrame.TURN_CREDENTIALS_REQUEST]'s doc for why this device never holds
     * one itself. Returns null if the relay has no TURN provider configured, the mint call to the
     * provider failed, or the relay didn't answer within the timeout — callers should fall back to
     * STUN-only (direct P2P may still work) rather than block the call on this.
     */
    suspend fun fetchTurnCredentials(): TurnCredentials? {
        val deferred = turnCredentialsMutex.withLock {
            pendingTurnCredentialsFetch?.takeIf { it.isActive } ?: CompletableDeferred<TurnCredentials?>().also { pendingTurnCredentialsFetch = it }
        }
        outgoing.send(TransportFrame.encode(TransportFrame.TURN_CREDENTIALS_REQUEST))
        return try {
            // Shorter than fetchBundle's 10s: this sits on a call's setup critical path (ring
            // timeout is only 45s total), so a slow/unresponsive relay should fall back to
            // STUN-only quickly rather than delay the whole call for a TURN fallback that
            // usually isn't even needed (direct P2P works for most pairs).
            withTimeout(4_000) { deferred.await() }
        } catch (e: Exception) {
            null
        }
    }

    private fun handleTurnCredentialsResult(body: ByteArray) {
        pendingTurnCredentialsFetch?.complete(TurnCredentialsCodec.decode(body))
    }

    private suspend fun handleGroupJoinRequest(body: ByteArray) {
        val (envelope, inviteBytes) = decryptEnvelope(body, "group join request") { it } ?: return
        val invite = GroupInvite.decode(inviteBytes)
        if (invite == null) {
            logger.warn("dropping group join request with a malformed invite")
            return
        }
        mutableGroupJoinRequests.emit(IncomingGroupJoinRequest(envelope.peerStaticPublicKey, invite))
    }

    /**
     * Resolves [peerDhIdentityKey]'s signing identity key: the existing TOFU pin if we've ever
     * established one (as an 1:1 initiator, or from a previous call here), otherwise by fetching
     * their prekey bundle fresh and pinning it — exactly the same TOFU trust [encrypt] gives a first
     * 1:1 initiation. Used by the group layer to bind a new member's claimed identity to something
     * more than a self-declared, self-signed field inside a [GroupControlEvent] or [GroupInvite]
     * (see `GroupManager`'s `resolveMemberSigningKey`) — returns null if the bundle can't be fetched,
     * doesn't self-verify, or (a spoofing attempt) claims a signing key different from one already
     * pinned via 1:1.
     */
    suspend fun resolveSigningIdentityKey(peerDhIdentityKey: ByteArray): ByteArray? {
        e2ee.pinnedSigningIdentityKey(peerDhIdentityKey)?.let { return it }
        // CRASH: fetchBundle throws (TimeoutCancellationException after 10s, or any transport
        // error) rather than returning null on failure -- callers of this function treat null as
        // "couldn't resolve, skip" (see GroupManager.resolveMemberSigningKey), not something to
        // propagate, so an uncaught throw here would escape a bare scope.launch { } with no
        // CoroutineExceptionHandler and crash the app, exactly like the earlier "callback invoked
        // off the main thread" class of group-creation crash.
        val bundle = runCatching { fetchBundle(peerDhIdentityKey) }.getOrNull() ?: return null
        if (!Ed25519Signatures.verify(bundle.signingIdentityKey, bundle.signedPreKey, bundle.signedPreKeySignature)) return null
        return if (e2ee.pinSigningIdentityKey(peerDhIdentityKey, bundle.signingIdentityKey)) bundle.signingIdentityKey else null
    }

    /**
     * Tells the relay where to send a wakeup push (a UnifiedPush endpoint URL) so a killed app
     * can still be reached for a mailboxed message or call. Not E2E-encrypted: this is routing
     * metadata about this device, not message content, and only the relay ever reads it. Call
     * again after every fresh connect (relay state is in-memory only, lost on restart, same as
     * [publishPreKeys]).
     */
    suspend fun registerPush(endpointUrl: String) {
        outgoing.send(TransportFrame.encode(TransportFrame.PUSH_REGISTER, endpointUrl.toByteArray(Charsets.UTF_8)))
    }

    /** Tells the relay to stop sending wakeup pushes for this device (empty body = unregister). */
    suspend fun unregisterPush() {
        outgoing.send(TransportFrame.encode(TransportFrame.PUSH_REGISTER, ByteArray(0)))
    }

    private suspend fun handleIncomingCallSignal(body: ByteArray) {
        val (envelope, decoded) = decryptEnvelope(body, "call-signal frame") { CallSignal.decode(it) } ?: return
        mutableCallSignals.emit(
            IncomingCallSignal.Signal(envelope.peerStaticPublicKey, decoded.callId, decoded.kind, decoded.payload),
        )
    }

    private fun handleCallUnavailable(body: ByteArray) =
        handleUnavailable(body, "call-unavailable notice", mutableCallSignals, IncomingCallSignal::Unavailable)

    private suspend fun handleIncomingFileSignal(body: ByteArray) {
        val (envelope, decoded) = decryptEnvelope(body, "file-transfer frame") { FileSignal.decode(it) } ?: return
        mutableFileSignals.emit(
            IncomingFileSignal.Signal(envelope.peerStaticPublicKey, decoded.fileId, decoded.kind, decoded.payload),
        )
    }

    private suspend fun handleReaction(body: ByteArray) {
        val (envelope, decoded) = decryptEnvelope(body, "reaction frame") { ReactionSignal.decode(it) } ?: return
        mutableReactions.emit(IncomingReaction(envelope.peerStaticPublicKey, decoded.messageId.toHex(), decoded.emoji))
    }

    private suspend fun handleMessageEdit(body: ByteArray) {
        val (envelope, decoded) = decryptEnvelope(body, "edit frame") { EditSignal.decode(it) } ?: return
        mutableMessageEdits.emit(IncomingMessageEdit(envelope.peerStaticPublicKey, decoded.messageId.toHex(), decoded.newText))
    }

    private fun handleFileUnavailable(body: ByteArray) =
        handleUnavailable(body, "file-unavailable notice", mutableFileSignals, IncomingFileSignal::Unavailable)

    /** Closes the relay connection. The writer/reader loops launched by [connect] exit on their own once this returns. */
    suspend fun close() {
        closingIntentionally = true
        outgoing.close()
        session.close()
    }
}
