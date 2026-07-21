package messenger.android.data

import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import messenger.android.MessageNotifier
import messenger.android.VoronConnectionService
import messenger.common.client.ApplicationMessage
import messenger.common.client.MessengerClient
import messenger.common.e2ee.DeviceIdentity
import messenger.common.onion.OnionCircuit
import messenger.common.onion.OnionNodeInfo
import messenger.common.util.hexToByteArray
import messenger.common.util.toHex

private const val TAG = "VoronConn"
private const val TYPING_SEND_COOLDOWN_MILLIS = 3_000L
private const val TYPING_CLEAR_TIMEOUT_MILLIS = 6_000L

/**
 * Owns the [MessengerClient] connection lifecycle, independent of any Activity — lives as long
 * as the process does (see [messenger.android.VoronApplication]), so a rotated/recreated
 * Activity doesn't tear down a live connection, and so reconnect attempts keep running even
 * while no Activity is in the foreground (backed by [VoronConnectionService] to survive Android
 * killing background network for the app).
 *
 * Reconnects automatically with capped exponential backoff whenever the socket dies on its own
 * (see [MessengerClient.connectionLost]) — the relay's per-device mailbox means nothing is lost
 * while a reconnect is in flight, it just arrives once the retry succeeds.
 */
class ConnectionManager(
    private val appContext: Context,
    val appState: AppState,
    private val callManager: CallManager,
    private val fileTransferManager: FileTransferManager,
    private val groupManager: GroupManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val notifier = MessageNotifier(appContext)
    private var connectionJob: Job? = null
    @Volatile private var manuallyDisconnected = true

    // One HttpClient for the manager's entire lifetime, shared by every server-info fetch,
    // reconnect, and update check — each of those used to spin up its own throwaway HttpClient
    // (and never close it), leaking an OkHttp connection pool + thread pool per reconnect attempt.
    private val httpClient by lazy { HttpClient(OkHttp) { install(WebSockets) } }

    private val updateManager by lazy { UpdateManager(appContext, appState, httpClient) }
    private val linkPreviewFetcher by lazy { LinkPreviewFetcher(appContext) }

    // Created once and reused across every reconnect, not just once per process: MessengerClient
    // holds the live E2EE ratchet sessions in memory, so replacing it on each reconnect used to
    // wipe every established session (forcing a fresh X3DH handshake, which fails outright if the
    // relay's prekey directory was also wiped by a restart) and would silently drop any message
    // queued during the reconnect window (its `outgoing` channel's reader was gone with the old
    // instance). Reusing the same client/channel means a message sent while disconnected just
    // queues and flushes once `connect()` relaunches the writer loop.
    private var messengerClient: MessengerClient? = null

    // Per-peer send-side throttle (don't fire on every keystroke) and receive-side auto-clear
    // (a typing indicator with no follow-up within TYPING_TIMEOUT_MILLIS means they stopped/left).
    private val lastTypingSentAt = ConcurrentHashMap<String, Long>()
    private val typingClearJobs = ConcurrentHashMap<String, Job>()

    init {
        // Disappearing messages are a purely local timer, so this sweep runs regardless of
        // connection state — no network round-trip involved, just periodically dropping whatever
        // in the local history has aged out.
        scope.launch {
            while (true) {
                delay(30_000)
                withContext(Dispatchers.Main) { appState.purgeExpiredMessages() }
            }
        }
    }

    /**
     * No-op if a connection attempt is already live or in progress — needed because, unlike every
     * other caller (which only ever calls this once, on first launch), a push wakeup
     * ([messenger.android.PushServiceImpl]) can race an already-succeeded reconnect from this
     * manager's own backoff loop. Cancelling [connectionJob] and relaunching in that case wouldn't
     * touch the still-running writer/reader loops [MessengerClient.connect] already started on
     * [scope] (they're siblings, not children, of the old job) — it would instead open a *second*
     * concurrent socket on the same client instance.
     */
    fun connect() {
        if (!manuallyDisconnected && connectionJob?.isActive == true) return
        manuallyDisconnected = false
        connectionJob?.cancel()
        connectionJob = scope.launch { connectionLoop() }
    }

    /**
     * Called by [messenger.android.PushServiceImpl] whenever the distributor hands us a fresh
     * endpoint (or tells us it dropped this app). Persists it and, if we're already connected,
     * tells the relay immediately — otherwise the next successful [openConnection] does it, same
     * as [MessengerClient.publishPreKeys].
     */
    fun onPushEndpointChanged(endpointUrl: String?) {
        appState.setPushEndpointPersisted(endpointUrl)
        val client = appState.client ?: return
        // Best-effort: the next successful connect re-sends it anyway.
        scope.launch { runCatching { if (endpointUrl != null) client.registerPush(endpointUrl) else client.unregisterPush() } }
    }

    /**
     * Persists the preference and, if currently connected, forces a fresh connection so the new
     * transport path (direct vs. tunneled through [OnionConfig]'s guard+middle) actually takes
     * effect — [openConnection] only decides this once, at the start of each connection attempt.
     */
    fun setOnionRoutingEnabled(enabled: Boolean) {
        appState.setOnionRoutingEnabledPersisted(enabled)
        reconnectNow()
    }

    /** Same reconnect-to-apply story as [setOnionRoutingEnabled], for the Wi-Fi-only restriction. */
    fun setOnionWifiOnly(enabled: Boolean) {
        appState.setOnionWifiOnlyPersisted(enabled)
        reconnectNow()
    }

    /** Forces a fresh circuit (new ephemeral key, new hop keys) without touching the enabled/disabled preference. No-op if onion routing is off or we're not connected. */
    fun rebuildOnionCircuit() {
        if (!appState.onionRoutingEnabled) return
        reconnectNow()
    }

    /** Tears down the current connection (if any) and immediately starts a fresh one — used whenever a setting that only takes effect at connect time changes while already connected. */
    private fun reconnectNow() {
        if (manuallyDisconnected) return
        connectionJob?.cancel()
        val staleClient = appState.client
        messengerClient = null
        appState.client = null
        appState.onionCircuitActive = false
        appState.connection = ConnectionState.Connecting
        // Best-effort: the socket may already be dead.
        scope.launch { runCatching { staleClient?.close() } }
        connectionJob = scope.launch { connectionLoop() }
    }

    fun disconnect() {
        manuallyDisconnected = true
        connectionJob?.cancel()
        connectionJob = null
        val client = appState.client
        scope.launch {
            // Best-effort: the socket may already be dead if the relay dropped us.
            runCatching { client?.close() }
            // close() permanently closes the outgoing channel, so this instance can't be
            // reconnected — a later connect() must build a fresh one (and, with it, fresh
            // sessions; that's the correct trade-off for a deliberate disconnect).
            messengerClient = null
            appState.client = null
            appState.onionCircuitActive = false
            appState.connection = ConnectionState.Idle
            VoronConnectionService.stop(appContext)
        }
    }

    /**
     * Sends a read ack for every incoming message in [peerKeyHex]'s conversation that doesn't
     * have one yet — called when its chat screen is opened. Messages that arrive while the chat
     * is *already* open are ack'd immediately instead (see the `incomingMessages` collector
     * above), so this only ever has to catch up on ones received while it was closed.
     */
    fun markChatSeen(peerKeyHex: String) {
        if (peerKeyHex == DRAFTS_DEVICE_KEY) return
        val client = appState.client ?: return
        val unseen = appState.unseenIncomingMessages(peerKeyHex)
        if (unseen.isEmpty()) return
        scope.launch {
            for (message in unseen) {
                val messageId = message.messageId ?: continue
                try {
                    client.sendReadAck(peerKeyHex.hexToByteArray(), messageId.hexToByteArray())
                    withContext(Dispatchers.Main) { appState.markReadAckSent(peerKeyHex, messageId) }
                } catch (e: Exception) {
                    // Best-effort: the next chat open retries whichever ones didn't go out.
                }
            }
        }
    }

    /** Throttled to at most one wire ping per [TYPING_SEND_COOLDOWN_MILLIS] per peer — safe to call on every keystroke. */
    fun notifyTyping(peerKeyHex: String) {
        if (peerKeyHex == DRAFTS_DEVICE_KEY) return
        val client = appState.client ?: return
        val now = System.currentTimeMillis()
        val last = lastTypingSentAt[peerKeyHex] ?: 0L
        if (now - last < TYPING_SEND_COOLDOWN_MILLIS) return
        lastTypingSentAt[peerKeyHex] = now
        // Best-effort: worst case the next keystroke's ping gets through instead.
        scope.launch { runCatching { client.sendTypingIndicator(peerKeyHex.hexToByteArray()) } }
    }

    /** Sends a picked file to [peerKeyHex] over the live-only, never-server-stored file pipe. No-op for the local Drafts chat. */
    fun sendFile(peerKeyHex: String, uri: Uri) {
        if (peerKeyHex == DRAFTS_DEVICE_KEY) return
        fileTransferManager.sendFile(peerKeyHex, uri)
    }

    /** URL-detects [text] for the compose bar's auto-banner — see [LinkPreviewFetcher.firstUrl]. */
    fun firstUrlIn(text: String): String? = linkPreviewFetcher.firstUrl(text)

    /** Fetches (sender-side only, never the recipient — see [ApplicationMessage.LinkPreviewRef]) a link preview for [url], or null on any failure. */
    suspend fun fetchLinkPreview(url: String) = linkPreviewFetcher.fetch(url)

    fun sendMessage(peerKeyHex: String, text: String, replyTo: ChatMessage? = null, linkPreview: ApplicationMessage.LinkPreviewRef? = null) {
        // Saved once up front (not inside `outgoing`, which can run twice — once optimistically,
        // once on failure/success) so a single send never writes the thumbnail file twice.
        val linkPreviewImagePath = linkPreview?.imageBytes?.let { linkPreviewFetcher.saveThumbnail(it) }
        fun outgoing(status: DeliveryStatus, messageId: String? = null) = ChatMessage(
            fromMe = true,
            text = text,
            timestampMillis = System.currentTimeMillis(),
            messageId = messageId,
            deliveryStatus = status,
            replyToMessageId = replyTo?.messageId,
            replyToPreview = replyTo?.text,
            replyToFromMe = replyTo?.fromMe ?: false,
            linkPreviewUrl = linkPreview?.url,
            linkPreviewTitle = linkPreview?.title,
            linkPreviewPageText = linkPreview?.pageText,
            linkPreviewImagePath = linkPreviewImagePath,
        )

        if (peerKeyHex == DRAFTS_DEVICE_KEY) {
            appState.appendMessage(peerKeyHex, outgoing(DeliveryStatus.DELIVERED))
            return
        }
        val client = appState.client ?: return
        val replyReference = replyTo?.messageId?.let { id ->
            ApplicationMessage.ReplyReference(id.hexToByteArray(), replyTo.text.take(200), repliedToWasFromReplier = replyTo.fromMe)
        }
        scope.launch {
            val messageId = try {
                client.sendMessage(peerKeyHex.hexToByteArray(), text.toByteArray(), replyReference, linkPreview)
            } catch (e: Exception) {
                VoronLog.w(TAG, "sendMessage to ${peerKeyHex.take(16)} failed", e)
                appState.appendMessage(peerKeyHex, outgoing(DeliveryStatus.FAILED))
                return@launch
            }
            VoronLog.d(TAG, "sendMessage to ${peerKeyHex.take(16)} succeeded, id=${messageId.take(16)}")
            appState.appendMessage(peerKeyHex, outgoing(DeliveryStatus.SENT, messageId))
        }
    }

    /**
     * Sends a recorded voice message [file] embedded directly in the E2EE message itself (see
     * [ApplicationMessage.VoiceAttachmentRef]) rather than through [FileTransferManager]'s
     * live-only pipe — so, unlike a generic file attachment, it's mailboxed and delivered
     * whenever the recipient next reconnects, exactly like a text message, instead of failing
     * outright the moment they aren't online right this second.
     */
    fun sendVoiceMessage(peerKeyHex: String, file: File, durationMillis: Long) {
        val audioBytes = try {
            file.readBytes()
        } catch (e: Exception) {
            file.delete()
            return
        }
        if (audioBytes.size > ApplicationMessage.MAX_VOICE_BYTES) {
            file.delete()
            return
        }
        fun outgoing(status: DeliveryStatus, messageId: String? = null) = ChatMessage(
            fromMe = true,
            text = "",
            timestampMillis = System.currentTimeMillis(),
            messageId = messageId,
            deliveryStatus = status,
            attachmentPath = file.absolutePath,
            attachmentName = file.name,
            attachmentMime = "audio/mp4",
            attachmentSize = audioBytes.size.toLong(),
            transferStatus = FileTransferStatus.COMPLETE,
            transferProgress = 1f,
        )

        if (peerKeyHex == DRAFTS_DEVICE_KEY) {
            appState.appendMessage(peerKeyHex, outgoing(DeliveryStatus.DELIVERED))
            return
        }
        val client = appState.client
        if (client == null) {
            appState.appendMessage(peerKeyHex, outgoing(DeliveryStatus.FAILED))
            return
        }
        val voiceAttachment = ApplicationMessage.VoiceAttachmentRef(audioBytes, durationMillis)
        scope.launch {
            val messageId = try {
                client.sendMessage(peerKeyHex.hexToByteArray(), ByteArray(0), voiceAttachment = voiceAttachment)
            } catch (e: Exception) {
                appState.appendMessage(peerKeyHex, outgoing(DeliveryStatus.FAILED))
                return@launch
            }
            appState.appendMessage(peerKeyHex, outgoing(DeliveryStatus.SENT, messageId))
        }
    }

    /** Sends [sticker] to [peerKeyHex] — same store-and-forward path as text (see [ApplicationMessage.encode]'s `stickerId` param), just with an empty text body. */
    fun sendSticker(peerKeyHex: String, sticker: StickerId) {
        fun outgoing(status: DeliveryStatus, messageId: String? = null) = ChatMessage(
            fromMe = true,
            text = "",
            timestampMillis = System.currentTimeMillis(),
            messageId = messageId,
            deliveryStatus = status,
            stickerId = sticker.wireValue,
        )

        if (peerKeyHex == DRAFTS_DEVICE_KEY) {
            appState.appendMessage(peerKeyHex, outgoing(DeliveryStatus.DELIVERED))
            return
        }
        val client = appState.client ?: return
        scope.launch {
            val messageId = try {
                client.sendMessage(peerKeyHex.hexToByteArray(), ByteArray(0), stickerId = sticker.wireValue)
            } catch (e: Exception) {
                VoronLog.w(TAG, "sendSticker to ${peerKeyHex.take(16)} failed", e)
                appState.appendMessage(peerKeyHex, outgoing(DeliveryStatus.FAILED))
                return@launch
            }
            appState.appendMessage(peerKeyHex, outgoing(DeliveryStatus.SENT, messageId))
        }
    }

    /**
     * Resends a [DeliveryStatus.FAILED] message's text and updates that same bubble in place
     * (rather than appending a new one) once it resolves. The most common cause of a FAILED send
     * is the recipient's device having been offline long enough that their published prekeys
     * expired from the relay's in-memory directory (it doesn't survive a relay restart) — once
     * they reopen the app and reconnect, [messenger.common.client.MessengerClient.publishPreKeys]
     * republishes and a retry here succeeds.
     */
    fun retryMessage(peerKeyHex: String, index: Int) {
        val message = appState.conversationFor(peerKeyHex).getOrNull(index) ?: return
        if (message.deliveryStatus != DeliveryStatus.FAILED) return
        resend(peerKeyHex, index, message)
    }

    /**
     * Resends every one of *our own* messages to [peerKeyHex] still sitting as [DeliveryStatus.SENT]
     * (never progressed to DELIVERED/SEEN) after that peer told us they lost their session (see the
     * sessionResetNotices collector in createClient). Dropping our own stale session (already done
     * by the time this runs) only fixes the *next* send — whatever we sent most recently under it
     * already failed to decrypt on their end and needs resending from scratch, or it's simply lost.
     * Only handles plain text messages for now: a lost voice/file attachment is a rarer edge case
     * left as still just "sent", matching this fix's actual reported scope.
     */
    private fun resendPendingMessagesAfterSessionReset(peerKeyHex: String) {
        val conversation = appState.conversationFor(peerKeyHex)
        val pendingIndices = conversation.indices.filter { index ->
            val message = conversation[index]
            message.fromMe && message.deliveryStatus == DeliveryStatus.SENT && message.attachmentPath == null
        }
        for (index in pendingIndices) {
            val message = conversation.getOrNull(index) ?: continue
            VoronLog.d(TAG, "resending message to ${peerKeyHex.take(16)} after its session reset (was stuck as SENT)")
            resend(peerKeyHex, index, message)
        }
    }

    /**
     * Shared core of [retryMessage] and [resendPendingMessagesAfterSessionReset]: re-encrypts and
     * resends [message]'s text, updating the same conversation slot in place throughout so the
     * bubble reflects RETRYING -> SENT (or FAILED again) rather than appearing as a duplicate.
     */
    private fun resend(peerKeyHex: String, index: Int, message: ChatMessage) {
        val client = appState.client ?: return
        // Visible immediately: without this, a retry that fails again (still the common case,
        // since the peer usually just hasn't reconnected yet) looked completely inert — same
        // icon, same text, no sign the tap did anything at all.
        appState.updateMessageAt(peerKeyHex, index, message.copy(deliveryStatus = DeliveryStatus.RETRYING))
        val replyReference = message.replyToMessageId?.let { id ->
            ApplicationMessage.ReplyReference(id.hexToByteArray(), message.replyToPreview.orEmpty().take(200), repliedToWasFromReplier = message.replyToFromMe)
        }
        scope.launch {
            val messageId = try {
                client.sendMessage(peerKeyHex.hexToByteArray(), message.text.toByteArray(), replyReference)
            } catch (e: Exception) {
                appState.updateMessageAt(peerKeyHex, index, message.copy(deliveryStatus = DeliveryStatus.FAILED))
                return@launch
            }
            appState.updateMessageAt(peerKeyHex, index, message.copy(messageId = messageId, deliveryStatus = DeliveryStatus.SENT))
        }
    }

    /**
     * Replaces the text of my own already-sent [messageId] in [peerKeyHex]'s conversation and tells
     * the peer. Optimistic like [sendMessage]: the local bubble updates immediately; a failure to
     * actually reach the peer is just logged — an edit isn't worth a retry-UI the way a first send is.
     */
    fun editMessage(peerKeyHex: String, messageId: String, newText: String) {
        appState.applyEdit(peerKeyHex, messageId, newText, expectedFromMe = true)
        if (peerKeyHex == DRAFTS_DEVICE_KEY) return
        val client = appState.client ?: return
        // Best-effort: the peer just won't see the edit until they happen to reconnect and this
        // gets retried some other way (there's no retry-UI for edits, unlike messages).
        scope.launch { runCatching { client.sendMessageEdit(peerKeyHex.hexToByteArray(), messageId.hexToByteArray(), newText) } }
    }

    /**
     * Sets (or, [emoji] null, clears) this device's own reaction on [messageId] in [peerKeyHex]'s
     * conversation and tells the peer. No-op for the local Drafts chat (nothing to notify).
     */
    fun toggleReaction(peerKeyHex: String, messageId: String, emoji: String?) {
        appState.setReaction(peerKeyHex, messageId, emoji, fromMe = true)
        if (peerKeyHex == DRAFTS_DEVICE_KEY) return
        val client = appState.client ?: return
        // Best-effort, same reasoning as editMessage above.
        scope.launch { runCatching { client.sendReaction(peerKeyHex.hexToByteArray(), messageId.hexToByteArray(), emoji.orEmpty()) } }
    }

    private suspend fun connectionLoop() {
        var attempt = 0
        while (!manuallyDisconnected) {
            appState.connection = ConnectionState.Connecting
            try {
                val client = openConnection()
                VoronLog.d(TAG, "relay connected")
                attempt = 0
                VoronConnectionService.start(appContext)
                client.connectionLost.first()
                VoronLog.w(TAG, "relay connectionLost fired, will reconnect after backoff")
                appState.onionCircuitActive = false
                if (manuallyDisconnected) return
                // Socket died on its own — fall through to the backoff below and retry.
            } catch (e: Exception) {
                if (manuallyDisconnected) return
                VoronLog.w(TAG, "relay connect attempt failed", e)
                appState.onionCircuitActive = false
                appState.connection = ConnectionState.Failed(e.message ?: e.toString())
            }
            attempt++
            delay(backoffMillis(attempt))
        }
    }

    private fun backoffMillis(attempt: Int): Long = minOf(30_000L, 2_000L shl minOf(attempt - 1, 4))

    private suspend fun openConnection(): MessengerClient {
        val relayKey = fetchStaticPublicKey("${RelayConfig.HTTP_SCHEME}://${RelayConfig.HOST}/v1/server-info")

        val client = messengerClient ?: createClient().also { messengerClient = it }

        val useOnion = appState.onionRoutingEnabled && (!appState.onionWifiOnly || isOnWifi())
        if (useOnion) {
            val circuit = buildOnionCircuit()
            client.connect(circuit.entryWsUrl, relayKey, scope, circuit::encodeOutgoing, circuit::decodeIncoming)
        } else {
            client.connect("${RelayConfig.WS_SCHEME}://${RelayConfig.HOST}/v1/connect", relayKey, scope)
        }
        VoronLog.d(TAG, "publishing prekeys after connect, own key=${client.identity.dhIdentityPublicKey.toHex().take(16)}")
        client.publishPreKeys()
        VoronLog.d(TAG, "prekeys published")
        appState.pushEndpoint?.let { client.registerPush(it) }

        appState.client = client
        appState.onionCircuitActive = useOnion
        appState.connection = ConnectionState.Connected(client.identity.dhIdentityPublicKey.toHex())
        scope.launch { updateManager.checkAndDownload() }
        return client
    }

    /** Fetches an info endpoint and extracts its announced Noise static public key — the same not-yet-pinned TOFU story for the relay and both onion hops. */
    private suspend fun fetchStaticPublicKey(url: String): ByteArray {
        val body = httpClient.get(url).bodyAsText()
        return Base64.getDecoder().decode(Regex(""""staticPublicKey":"([^"]+)"""").find(body)!!.groupValues[1])
    }

    private fun isOnWifi(): Boolean {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Fetches all three hops' current static keys fresh (same not-yet-pinned TOFU story as the relay's own key) and builds a one-off circuit for this connection attempt. */
    private suspend fun buildOnionCircuit(): OnionCircuit {
        val entry = fetchOnionNodeInfo(OnionConfig.ENTRY_HOST)
        val guard = fetchOnionNodeInfo(OnionConfig.GUARD_HOST)
        val middle = fetchOnionNodeInfo(OnionConfig.MIDDLE_HOST)
        return OnionCircuit.build(listOf(entry, guard, middle))
    }

    private suspend fun fetchOnionNodeInfo(host: String): OnionNodeInfo {
        val key = fetchStaticPublicKey("${OnionConfig.HTTP_SCHEME}://$host/v1/onion-info")
        return OnionNodeInfo(wsUrl = "${OnionConfig.WS_SCHEME}://$host/v1/onion-relay", staticPublicKey = key)
    }

    private fun createClient(): MessengerClient {
        val identityStore = SecureStore(appContext, "device_identity.key")
        val identity = DeviceIdentity.loadOrCreate(identityStore.readBytes(), identityStore::writeBytes)
        val preKeyStore = SecureStore(appContext, "signed_prekeys.key")
        val client = MessengerClient(
            identity,
            httpClient,
            appState.displayName.ifBlank { identity.dhIdentityPublicKey.toHex().take(8) },
            appState.avatarIconId.wireValue,
            loadPersistedPreKeys = preKeyStore::readBytes,
            persistPreKeys = preKeyStore::writeBytes,
        )

        // Subscribed exactly once, for the client's entire lifetime (not per reconnect): these are
        // ordinary SharedFlow subscriptions, so re-subscribing on every reconnect would process
        // every incoming message/ack once per past connection attempt.
        scope.launch {
            client.incomingMessages.collect { message ->
              try {
                val senderHex = message.senderDhIdentityKey.toHex()
                val text = String(message.plaintext)
                VoronLog.d(TAG, "incoming message decrypted OK from ${senderHex.take(16)}: \"$text\" (id=${message.messageId.toHex().take(16)})")
                // The sender already fetched/archived this — we only ever persist what arrived
                // E2E-encrypted, never make a network request of our own for it (see
                // ApplicationMessage.LinkPreviewRef). Writing the thumbnail file happens here, still
                // off Dispatchers.Main (this collector runs on the manager's Default scope), so it
                // can't block the UI thread the way an in-place write inside the withContext(Main)
                // block below would.
                val linkPreview = message.linkPreview
                val linkPreviewImagePath = linkPreview?.imageBytes?.let { linkPreviewFetcher.saveThumbnail(it) }
                // Same off-Main reasoning as the link-preview thumbnail above — a voice message's
                // audio bytes arrived embedded in this very E2EE message (see
                // ApplicationMessage.VoiceAttachmentRef), so writing them out is a one-time local
                // file save, not a network fetch of any kind.
                val voiceAttachment = message.voiceAttachment
                val voiceFilePath = voiceAttachment?.let { voice ->
                    val dir = File(appContext.filesDir, "media").apply { mkdirs() }
                    val file = File(dir, "voice_in_${System.currentTimeMillis()}.m4a")
                    file.writeBytes(voice.audioBytes)
                    file.absolutePath
                }
                withContext(Dispatchers.Main) {
                    // Silently dropped, not just hidden — a blocked contact gets no ack-of-block
                    // (the E2EE decrypt ack :common already sent is a transport-layer detail they
                    // can't tell apart from a normal delivery), no notification, no stored history.
                    if (appState.isBlocked(senderHex)) return@withContext
                    appState.applyIncomingProfile(senderHex, message.senderDisplayName, AvatarIconId.fromWireValue(message.senderAvatarIcon))
                    val messageIdHex = message.messageId.toHex()
                    // Compose never disposes the chat screen just because the app was backgrounded
                    // (only real back-navigation clears openPeerKeyHex), so checking it alone would
                    // still count as "already looking at this chat" while the app sits minimized —
                    // silently auto-marking every arriving message as read/seen. Foreground state is
                    // the other half of "actually looking at it right now".
                    val appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    val chatAlreadyOpen = appState.openPeerKeyHex == senderHex && appInForeground
                    appState.appendMessage(
                        senderHex,
                        ChatMessage(
                            fromMe = false,
                            text = text,
                            timestampMillis = System.currentTimeMillis(),
                            messageId = messageIdHex,
                            readAckSent = chatAlreadyOpen,
                            replyToMessageId = message.replyTo?.repliedToMessageId?.toHex(),
                            replyToPreview = message.replyTo?.previewText,
                            // Flip the sender's own point of view: if *they* authored the quoted
                            // message, it wasn't us (only two people in a 1:1 chat), and vice versa.
                            replyToFromMe = message.replyTo?.let { !it.repliedToWasFromReplier } ?: false,
                            linkPreviewUrl = linkPreview?.url,
                            linkPreviewTitle = linkPreview?.title,
                            linkPreviewPageText = linkPreview?.pageText,
                            linkPreviewImagePath = linkPreviewImagePath,
                            attachmentPath = voiceFilePath,
                            attachmentName = voiceFilePath?.let { "Voice message" },
                            attachmentMime = if (voiceAttachment != null) "audio/mp4" else null,
                            attachmentSize = voiceAttachment?.audioBytes?.size?.toLong(),
                            transferStatus = if (voiceAttachment != null) FileTransferStatus.COMPLETE else null,
                            transferProgress = if (voiceAttachment != null) 1f else 0f,
                            stickerId = message.stickerId,
                        ),
                    )
                    appState.markUnreadIfClosed(senderHex)
                    typingClearJobs.remove(senderHex)?.cancel()
                    appState.typingPeerKeys.remove(senderHex)
                    // Already looking at this chat right now — tell the sender immediately rather
                    // than waiting for them to next open/reopen it (see markChatSeen for that path).
                    if (chatAlreadyOpen) {
                        scope.launch {
                            try {
                                client.sendReadAck(message.senderDhIdentityKey, message.messageId)
                            } catch (e: Exception) {
                                // Best-effort: markChatSeen catches this up on the next open anyway.
                            }
                        }
                    }
                    // Only interrupt with a notification if no screen of ours is currently
                    // visible — if the app is foregrounded the chat list/detail already
                    // updates live, a banner on top of that would just be noise.
                    if (!appInForeground && appState.notificationsEnabled) {
                        notifier.notifyIncoming(
                            senderHex,
                            appState.nicknameFor(senderHex),
                            if (message.stickerId != null) "🐦 Sticker" else text,
                            hideSender = appState.hideNotificationSender,
                            hideContent = appState.hideNotificationContent,
                        )
                    }
                }
              } catch (e: Exception) {
                // A single malformed/oversized incoming message (e.g. a voice attachment that
                // fails to write to disk) must not silently kill this collector forever — that
                // would stop every future incoming message on this device until app restart,
                // while the sender sees nothing wrong (their delivery ack already fired inside
                // MessengerClient, independent of this UI-side processing).
                VoronLog.w(TAG, "failed to process incoming message, dropping it", e)
              }
            }
        }

        scope.launch {
            client.deliveryAcks.collect { ack ->
                withContext(Dispatchers.Main) {
                    appState.markDelivered(ack.peerDhIdentityKey.toHex(), ack.messageId)
                }
            }
        }

        scope.launch {
            client.readAcks.collect { ack ->
                withContext(Dispatchers.Main) {
                    appState.markSeen(ack.peerDhIdentityKey.toHex(), ack.messageId)
                }
            }
        }

        scope.launch {
            client.reactions.collect { reaction ->
                withContext(Dispatchers.Main) {
                    appState.setReaction(reaction.peerDhIdentityKey.toHex(), reaction.messageId, reaction.emoji.ifEmpty { null }, fromMe = false)
                }
            }
        }

        scope.launch {
            client.messageEdits.collect { edit ->
                withContext(Dispatchers.Main) {
                    appState.applyEdit(edit.peerDhIdentityKey.toHex(), edit.messageId, edit.newText, expectedFromMe = false)
                }
            }
        }

        // A peer telling us they lost their session (see MessengerClient.handleSessionResetNotice)
        // means whatever we most recently sent them under the now-dropped session already failed
        // to decrypt on their end and is gone for good -- dropping our own session only fixes the
        // *next* send. Without this, that message would sit as "sent" forever with no automatic
        // recovery (see "messages sent while the recipient was restarting never arrive").
        scope.launch {
            client.sessionResetNotices.collect { peerKey ->
                withContext(Dispatchers.Main) {
                    resendPendingMessagesAfterSessionReset(peerKey.toHex())
                }
            }
        }

        scope.launch {
            client.typingIndicators.collect { peerKey ->
                val peerHex = peerKey.toHex()
                withContext(Dispatchers.Main) { appState.typingPeerKeys.add(peerHex) }
                typingClearJobs[peerHex]?.cancel()
                typingClearJobs[peerHex] = scope.launch {
                    delay(TYPING_CLEAR_TIMEOUT_MILLIS)
                    withContext(Dispatchers.Main) { appState.typingPeerKeys.remove(peerHex) }
                }
            }
        }

        scope.launch {
            client.callSignals.collect { signal ->
                withContext(Dispatchers.Main) { callManager.onSignal(signal) }
            }
        }

        scope.launch {
            // FileTransferManager.onSignal is thread-safe and dispatches its own IO/Main work, so no
            // withContext(Main) wrapper here (unlike callManager) — and file streaming shouldn't be
            // funnelled through the main thread.
            client.fileSignals.collect { signal -> fileTransferManager.onSignal(signal) }
        }

        // GroupManager subscribes the client's group flows itself (control events, sync/join
        // requests, group messages) — once per client lifetime, same as the collectors above.
        groupManager.attach(client)

        return client
    }

    /** Fires the system installer for a build [UpdateManager] already fetched — kept as a pass-through so MainActivity only wires against this manager. */
    fun installDownloadedUpdate() = updateManager.installDownloadedUpdate()
}
