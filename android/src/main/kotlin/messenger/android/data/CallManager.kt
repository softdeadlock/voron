package messenger.android.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import messenger.android.CallForegroundService
import messenger.common.client.IncomingCallSignal
import messenger.common.e2ee.CallSignal
import messenger.common.e2ee.TurnCredentials
import messenger.common.util.hexToByteArray
import messenger.common.util.toHex
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

private const val TAG = "VoronCall"

// Far more than a real call ever gathers before its answer/RING arrives — just a backstop so an
// already-session-authenticated peer can't grow this per-call buffer unboundedly by sending
// ICE_CANDIDATE signals before ever answering.
private const val MAX_PENDING_REMOTE_ICE = 50

private open class SimpleSdpObserver(private val label: String = "sdp") : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {
        VoronLog.w(TAG, "$label create failed: $error")
    }
    override fun onSetFailure(error: String?) {
        VoronLog.w(TAG, "$label set failed: $error")
    }
}

/**
 * 1:1 audio calling: WebRTC media (P2P, falling back to Open Relay Project's free TURN when
 * direct connectivity fails) plus signaling reused over the exact same relay + E2EE pipe chat
 * messages already use (see [messenger.common.client.MessengerClient.sendCallSignal] /
 * [IncomingCallSignal]). Process-scoped, like [ConnectionManager] — constructed once in
 * [messenger.android.VoronApplication].
 *
 * Only one call at a time, matching the 1:1-only scope: [currentCallId]/[remotePeerKeyHex] track
 * that single active (or ringing) call, never a set of them.
 */
class CallManager(private val appContext: Context, private val appState: AppState) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var peerConnectionFactory: PeerConnectionFactory? = null
    @Volatile private var peerConnection: PeerConnection? = null
    @Volatile private var localAudioTrack: AudioTrack? = null
    @Volatile private var localAudioSource: org.webrtc.AudioSource? = null

    @Volatile private var currentCallId: UUID? = null
    @Volatile private var remotePeerKeyHex: String? = null
    @Volatile private var pendingOfferSdp: String? = null

    // Guards [hasRemoteDescription] + [pendingRemoteIce] together: remote candidates arrive on the
    // main thread (onSignal) while the "remote description is set, flush the buffer" transition runs
    // on WebRTC's own signaling thread (setRemote/setLocal SdpObserver callbacks). Without one lock
    // covering both the flag flip and the buffer drain, a candidate could be buffered *after* the
    // drain already cleared it (stranded forever → media path silently never forms), or the plain
    // ArrayList could be mutated concurrently.
    private val iceLock = Any()
    private var hasRemoteDescription: Boolean = false
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var ringTimeoutJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private fun factory(): PeerConnectionFactory {
        peerConnectionFactory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext.applicationContext).createInitializationOptions(),
        )
        val audioDeviceModule = JavaAudioDeviceModule.builder(appContext.applicationContext).createAudioDeviceModule()
        val created = PeerConnectionFactory.builder().setAudioDeviceModule(audioDeviceModule).createPeerConnectionFactory()
        peerConnectionFactory = created
        return created
    }

    // Open Relay Project's free demo TURN turned out to be unreachable (confirmed: with
    // iceTransportsType=RELAY forced, zero candidates were ever gathered on either device across
    // a full call). Uses the relay's Metered.ca TURN service instead — [turnCredentials] is a
    // fresh, short-lived username/password minted server-side per call (see
    // MessengerClient.fetchTurnCredentials), never a static secret shipped in this app: hardcoding
    // one here would ship a real, standing, un-rotatable credential in every APK, trivially
    // extractable by decompiling it. Null (relay unreachable, no TURN provider configured, or the
    // mint call failed) falls back to STUN-only — direct P2P connectivity still works for most
    // pairs; only a TURN-requiring pair (both behind symmetric NAT, etc.) loses the fallback.
    private fun iceServers(turnCredentials: TurnCredentials?): List<PeerConnection.IceServer> {
        val servers = mutableListOf(PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer())
        if (turnCredentials != null) {
            val username = turnCredentials.username
            val credential = turnCredentials.password
            servers += listOf(
                PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80")
                    .setUsername(username).setPassword(credential).createIceServer(),
                PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80?transport=tcp")
                    .setUsername(username).setPassword(credential).createIceServer(),
                PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443")
                    .setUsername(username).setPassword(credential).createIceServer(),
                PeerConnection.IceServer.builder("turns:global.relay.metered.ca:443?transport=tcp")
                    .setUsername(username).setPassword(credential).createIceServer(),
            )
        } else {
            VoronLog.w(TAG, "no TURN credentials available for this call, falling back to STUN-only")
        }
        return servers
    }

    private fun openPeerConnection(turnCredentials: TurnCredentials?): PeerConnection {
        val audioSource = factory().createAudioSource(MediaConstraints())
        val track = factory().createAudioTrack("voron-audio", audioSource)
        localAudioSource = audioSource
        localAudioTrack = track

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers(turnCredentials)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory().createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val candidateType = Regex("""typ (\w+)""").find(candidate.sdp)?.groupValues?.get(1) ?: "unknown"
                VoronLog.d(TAG, "local ICE candidate gathered [type=$candidateType]: ${candidate.sdp}")
                val peerKeyHex = remotePeerKeyHex ?: return
                val callId = currentCallId ?: return
                val client = appState.client ?: return
                scope.launch {
                    try {
                        client.sendCallSignal(
                            peerKeyHex.hexToByteArray(),
                            callId,
                            CallSignal.ICE_CANDIDATE,
                            "${candidate.sdpMid}\n${candidate.sdpMLineIndex}\n${candidate.sdp}",
                        )
                        VoronLog.d(TAG, "sent local ICE candidate to peer")
                    } catch (e: Exception) {
                        VoronLog.w(TAG, "failed to send local ICE candidate", e)
                    }
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) {
                VoronLog.d(TAG, "selected candidate pair: local=${event.local.sdp} remote=${event.remote.sdp} reason=${event.reason}")
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                VoronLog.d(TAG, "signaling state: $state")
            }

            // ICE can go FAILED/DISCONNECTED well after the call UI already shows "Connected"
            // (an offer/answer exchanging successfully only proves signaling works, not that a
            // usable media path was ever actually found) — without reacting to this, a call that
            // silently can't pass audio just sits there looking "connected" forever instead of
            // failing visibly.
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                VoronLog.d(TAG, "ICE connection state: $state")
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.DISCONNECTED
                ) {
                    val peerKeyHex = remotePeerKeyHex ?: return
                    val callId = currentCallId ?: return
                    scope.launch {
                        withContext(Dispatchers.Main) {
                            if (currentCallId != callId) return@withContext
                            showUnavailableThenClear(peerKeyHex, "ICE connection $state")
                        }
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                VoronLog.d(TAG, "ICE gathering state: $state")
            }
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            // The Unified-Plan-correct callback for a newly negotiated remote track (onAddStream
            // is legacy Plan B and isn't reliably fired under Unified Plan, which this uses).
            // WebRTC's own audio device module plays a received audio track automatically once
            // it's part of the connection, but explicitly enabling it removes any doubt.
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                VoronLog.d(TAG, "onTrack: remote track received, mid=${transceiver?.mid}")
                (transceiver?.receiver?.track() as? org.webrtc.AudioTrack)?.setEnabled(true)
            }
        }) ?: error("factory refused to create a PeerConnection")
        VoronLog.d(TAG, "PeerConnection created")

        pc.addTrack(track, listOf("voron-stream"))
        peerConnection = pc

        val audioManager = appContext.getSystemService(AudioManager::class.java)
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        // Speaker on by default: this is a hands-free VoIP call between two independent devices,
        // not a phone held to the ear, and the earpiece path is quiet enough that "no audio
        // reaching the other side" was as likely to be this as an actual connectivity failure.
        audioManager?.isSpeakerphoneOn = true
        requestAudioFocus(audioManager)
        return pc
    }

    private fun requestAudioFocus(audioManager: AudioManager?) {
        audioManager ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    /** Places an outgoing call to [peerKeyHex]. No-op if a call is already active. */
    fun startCall(peerKeyHex: String) {
        if (appState.activeCall != null) return
        val client = appState.client ?: return
        val callId = UUID.randomUUID()
        currentCallId = callId
        remotePeerKeyHex = peerKeyHex
        pendingOfferSdp = null
        synchronized(iceLock) {
            hasRemoteDescription = false
            pendingRemoteIce.clear()
        }
        // Shown immediately (optimistic): fetchTurnCredentials below is a network round-trip, and
        // the ring UI shouldn't wait on it.
        appState.activeCall = CallUiState.OutgoingRinging(peerKeyHex, callId)

        scope.launch {
            val turnCredentials = client.fetchTurnCredentials()
            if (currentCallId != callId) return@launch // superseded (e.g. hung up) while fetching
            withContext(Dispatchers.Main) {
                val pc = openPeerConnection(turnCredentials)
                pc.createOffer(
                    object : SimpleSdpObserver("createOffer") {
                        override fun onCreateSuccess(description: SessionDescription?) {
                            val offer = description ?: return
                            pc.setLocalDescription(SimpleSdpObserver("setLocalDescription(offer)"), offer)
                            scope.launch {
                                try {
                                    client.sendCallSignal(peerKeyHex.hexToByteArray(), callId, CallSignal.RING, offer.description)
                                } catch (e: Exception) {
                                    // Same failure mode as a truly unreachable peer (most commonly: no
                                    // E2EE session yet and the relay had no fresh prekey bundle for them)
                                    // — show it the same way instead of the call screen just vanishing
                                    // with no explanation at all.
                                    VoronLog.w(TAG, "failed to send RING", e)
                                    withContext(Dispatchers.Main) { showUnavailableThenClear(peerKeyHex, "failed to send RING") }
                                    return@launch
                                }
                                startRingTimeout(peerKeyHex, callId)
                            }
                        }
                    },
                    MediaConstraints(),
                )
            }
        }
    }

    /** Accepts the currently-ringing incoming call. No-op unless [AppState.activeCall] is [CallUiState.IncomingRinging]. */
    fun answerCall() {
        val incoming = appState.activeCall as? CallUiState.IncomingRinging ?: return
        val client = appState.client ?: return
        val offerSdp = pendingOfferSdp ?: return
        val callId = incoming.callId

        scope.launch {
            val turnCredentials = client.fetchTurnCredentials()
            if (currentCallId != callId) return@launch // superseded (e.g. declined) while fetching
            withContext(Dispatchers.Main) {
                val pc = openPeerConnection(turnCredentials)
                pc.setRemoteDescription(
                    object : SimpleSdpObserver("setRemoteDescription(offer)") {
                        override fun onSetSuccess() {
                            flushPendingIce(pc)
                            pc.createAnswer(
                                object : SimpleSdpObserver("createAnswer") {
                                    override fun onCreateSuccess(description: SessionDescription?) {
                                        val answer = description ?: return
                                        pc.setLocalDescription(SimpleSdpObserver("setLocalDescription(answer)"), answer)
                                        scope.launch {
                                            try {
                                                client.sendCallSignal(
                                                    incoming.peerKeyHex.hexToByteArray(),
                                                    incoming.callId,
                                                    CallSignal.ANSWER,
                                                    answer.description,
                                                )
                                            } catch (e: Exception) {
                                                VoronLog.w(TAG, "failed to send ANSWER", e)
                                                withContext(Dispatchers.Main) { showUnavailableThenClear(incoming.peerKeyHex, "failed to send ANSWER") }
                                                return@launch
                                            }
                                            withContext(Dispatchers.Main) {
                                                appState.activeCall = CallUiState.Connected(
                                                    incoming.peerKeyHex,
                                                    incoming.callId,
                                                    System.currentTimeMillis(),
                                                    muted = false,
                                                )
                                                CallForegroundService.startInCall(appContext, appState.nicknameFor(incoming.peerKeyHex))
                                            }
                                        }
                                    }
                                },
                                MediaConstraints(),
                            )
                        }
                    },
                    SessionDescription(SessionDescription.Type.OFFER, offerSdp),
                )
            }
        }
    }

    /** Declines the currently-ringing incoming call. No-op unless [AppState.activeCall] is [CallUiState.IncomingRinging]. */
    fun declineCall() {
        val incoming = appState.activeCall as? CallUiState.IncomingRinging ?: return
        sendHangup(incoming.peerKeyHex, incoming.callId, CallSignal.HANGUP_DECLINED)
        endCall("declined locally")
        appState.activeCall = null
    }

    /** Ends the current outgoing-ringing or connected call. No-op if there is none. */
    fun hangUp() {
        val peerKeyHex = remotePeerKeyHex ?: return
        val callId = currentCallId ?: return
        sendHangup(peerKeyHex, callId, CallSignal.HANGUP_ENDED)
        endCall("hung up locally")
        appState.activeCall = null
    }

    fun toggleMute() {
        val connected = appState.activeCall as? CallUiState.Connected ?: return
        val muted = !connected.muted
        localAudioTrack?.setEnabled(!muted)
        appState.activeCall = connected.copy(muted = muted)
    }

    /** Dispatches a call-signaling event from [messenger.common.client.MessengerClient.callSignals] — must be called on the main thread. */
    fun onSignal(signal: IncomingCallSignal) {
        when (signal) {
            is IncomingCallSignal.Unavailable -> handleUnavailable(signal.peerDhIdentityKey.toHex())
            is IncomingCallSignal.Signal -> when (signal.kind) {
                CallSignal.RING -> handleRing(signal)
                CallSignal.ANSWER -> handleAnswer(signal)
                CallSignal.ICE_CANDIDATE -> handleIceCandidate(signal)
                CallSignal.HANGUP -> handleHangup(signal)
            }
        }
    }

    private fun handleRing(signal: IncomingCallSignal.Signal) {
        VoronLog.d(TAG, "RING received")
        val peerKeyHex = signal.peerDhIdentityKey.toHex()
        if (appState.activeCall != null) {
            // Already in (or ringing for) a call: reject silently rather than disturbing it.
            sendHangup(peerKeyHex, signal.callId, CallSignal.HANGUP_BUSY)
            return
        }
        currentCallId = signal.callId
        remotePeerKeyHex = peerKeyHex
        pendingOfferSdp = signal.payload
        synchronized(iceLock) {
            hasRemoteDescription = false
            pendingRemoteIce.clear()
        }
        appState.activeCall = CallUiState.IncomingRinging(peerKeyHex, signal.callId)
        CallForegroundService.startRinging(appContext, appState.nicknameFor(peerKeyHex))
    }

    private fun handleAnswer(signal: IncomingCallSignal.Signal) {
        VoronLog.d(TAG, "ANSWER received")
        if (signal.callId != currentCallId) return
        val pc = peerConnection ?: return
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        pc.setRemoteDescription(
            object : SimpleSdpObserver("setRemoteDescription(answer)") {
                override fun onSetSuccess() {
                    flushPendingIce(pc)
                    val peerKeyHex = remotePeerKeyHex ?: return
                    scope.launch {
                        withContext(Dispatchers.Main) {
                            appState.activeCall = CallUiState.Connected(peerKeyHex, signal.callId, System.currentTimeMillis(), muted = false)
                            CallForegroundService.startInCall(appContext, appState.nicknameFor(peerKeyHex))
                        }
                    }
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, signal.payload),
        )
    }

    private fun handleIceCandidate(signal: IncomingCallSignal.Signal) {
        if (signal.callId != currentCallId) {
            VoronLog.d(TAG, "dropping ICE candidate for a stale/mismatched callId")
            return
        }
        val parts = signal.payload.split("\n", limit = 3)
        if (parts.size != 3) {
            VoronLog.w(TAG, "dropping malformed remote ICE candidate payload")
            return
        }
        val sdpMLineIndex = parts[1].toIntOrNull() ?: return
        val candidate = IceCandidate(parts[0].ifEmpty { null }, sdpMLineIndex, parts[2])
        val pc = peerConnection
        synchronized(iceLock) {
            if (pc != null && hasRemoteDescription) {
                VoronLog.d(TAG, "adding remote ICE candidate immediately: ${candidate.sdp}")
                pc.addIceCandidate(candidate)
            } else if (pendingRemoteIce.size < MAX_PENDING_REMOTE_ICE) {
                VoronLog.d(TAG, "buffering remote ICE candidate (no remote description yet)")
                pendingRemoteIce.add(candidate)
            } else {
                VoronLog.w(TAG, "dropping remote ICE candidate, buffer full (no answer/RING received yet)")
            }
        }
    }

    /** Marks the remote description as set and drains every buffered remote ICE candidate into [pc], atomically w.r.t. [handleIceCandidate]. Runs on WebRTC's signaling thread. */
    private fun flushPendingIce(pc: PeerConnection) {
        synchronized(iceLock) {
            hasRemoteDescription = true
            pendingRemoteIce.forEach { pc.addIceCandidate(it) }
            pendingRemoteIce.clear()
        }
    }

    private fun handleHangup(signal: IncomingCallSignal.Signal) {
        VoronLog.d(TAG, "HANGUP received, reason=${signal.payload}")
        if (signal.callId != currentCallId) return
        endCall("remote hangup, reason=${signal.payload}")
        appState.activeCall = null
    }

    private fun handleUnavailable(peerKeyHex: String) {
        VoronLog.d(TAG, "CALL_UNAVAILABLE received")
        val current = appState.activeCall
        if (current !is CallUiState.OutgoingRinging || current.peerKeyHex != peerKeyHex || current.callId != currentCallId) return
        showUnavailableThenClear(peerKeyHex, "callee unreachable")
    }

    private fun startRingTimeout(peerKeyHex: String, callId: UUID) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(45_000)
            withContext(Dispatchers.Main) {
                if (currentCallId != callId || appState.activeCall !is CallUiState.OutgoingRinging) return@withContext
                VoronLog.d(TAG, "ring timed out with no answer")
                sendHangup(peerKeyHex, callId, CallSignal.HANGUP_TIMEOUT)
                showUnavailableThenClear(peerKeyHex, "no answer within 45s")
            }
        }
    }

    private fun sendHangup(peerKeyHex: String, callId: UUID, reason: Byte) {
        val client = appState.client ?: return
        // Best-effort: the peer will time out its own side if this doesn't arrive.
        scope.launch { runCatching { client.sendCallSignal(peerKeyHex.hexToByteArray(), callId, CallSignal.HANGUP, reason.toInt().toString()) } }
    }

    private fun showUnavailableThenClear(peerKeyHex: String, reason: String) {
        endCall(reason)
        val unavailable = CallUiState.Unavailable(peerKeyHex)
        appState.activeCall = unavailable
        scope.launch {
            delay(2_000)
            withContext(Dispatchers.Main) {
                if (appState.activeCall == unavailable) appState.activeCall = null
            }
        }
    }

    private fun endCall(reason: String = "unspecified") {
        VoronLog.d(TAG, "endCall: $reason")
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        // close() stops the connection; dispose() frees the native objects. Without disposing the
        // PeerConnection + per-call audio source/track, each finished call leaked native WebRTC
        // memory that accumulated over a long-lived process.
        peerConnection?.let {
            it.close()
            it.dispose()
        }
        peerConnection = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null
        currentCallId = null
        remotePeerKeyHex = null
        pendingOfferSdp = null
        synchronized(iceLock) {
            hasRemoteDescription = false
            pendingRemoteIce.clear()
        }
        val audioManager = appContext.getSystemService(AudioManager::class.java)
        audioManager?.mode = AudioManager.MODE_NORMAL
        audioManager?.isSpeakerphoneOn = false
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        CallForegroundService.stop(appContext)
    }
}
