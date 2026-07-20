package messenger.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import messenger.android.data.AppState
import messenger.android.data.ConnectionManager
import messenger.android.ui.VoronActions
import messenger.android.ui.VoronApp
import messenger.android.ui.theme.VoronTheme
import messenger.common.client.MessengerClient
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Native Android client for Voron: drives the exact same [MessengerClient]
 * from [common][messenger.common.client] as the desktop test harness, just
 * with a real chat UI instead of a browser tab. No Android-specific crypto
 * code exists anywhere in this app — Noise_IK transport, X3DH and the
 * ratchet all live in :common and run unmodified here via Conscrypt.
 *
 * Connection lifecycle itself lives in [ConnectionManager] ([VoronApplication]-scoped, not
 * Activity-scoped) so it survives Activity recreation and keeps reconnecting in the background.
 */
class MainActivity : FragmentActivity() {
    private var pendingCallAction: (() -> Unit)? = null

    /** Calling always needs the mic, requested at call time (not at launch, unlike notifications) since most sessions never place a call. */
    private fun withAudioPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingCallAction = action
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                pendingCallAction?.invoke()
            }
            pendingCallAction = null
        }
    }

    private var pendingDeepLinkPeerKey by mutableStateOf<String?>(null)

    /**
     * UnifiedPush registration must run from an Activity: [UnifiedPush.tryUseCurrentOrDefaultDistributor]
     * needs one to launch its translucent distributor-picker activity when no distributor is
     * already saved/acked, so this can't live in [ConnectionManager] (Application-scoped).
     */
    private fun setPushEnabled(enabled: Boolean, appState: AppState, connectionManager: ConnectionManager) {
        if (!enabled) {
            UnifiedPush.unregister(this)
            appState.setPushEnabledPersisted(false)
            connectionManager.onPushEndpointChanged(null)
            return
        }
        UnifiedPush.tryUseCurrentOrDefaultDistributor(this) { success ->
            appState.setPushEnabledPersisted(success)
            if (success) {
                UnifiedPush.register(this, messageForDistributor = "Voron")
            }
            // If no distributor is installed (e.g. no ntfy/similar app), success is false and the
            // switch just visibly stays off — nothing to register, nothing to tear down.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Blocks screenshots, screen recording, and the plaintext preview Android would
        // otherwise show for this app in the recent-apps switcher.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATIONS)
        }

        // Requested at launch, not lazily at call time: Android 14+ requires RECORD_AUDIO to
        // already be granted before a "microphone"-type foreground service can start at all
        // (CallForegroundService.startRinging, called for an *incoming* ring). Without this, a
        // device that had never itself placed/answered a call yet would silently fail to show
        // the ringing UI for an incoming one — startForeground() throws, gets caught, and nothing
        // wakes the screen. Placing an outgoing call still separately checks/re-requests via
        // withAudioPermission, in case the user denied it here.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_AUDIO)
        }

        pendingDeepLinkPeerKey = intent?.getStringExtra(MessageNotifier.EXTRA_PEER_KEY)

        val app = application as VoronApplication
        val appState = app.appState
        val connectionManager = app.connectionManager
        val callManager = app.callManager
        val groupManager = app.groupManager
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val appVersionLabel = "Version ${packageInfo.versionName} (build ${packageInfo.longVersionCode})"

        val actions = VoronActions(
            connect = connectionManager::connect,
            sendMessage = connectionManager::sendMessage,
            fetchLinkPreview = connectionManager::fetchLinkPreview,
            sendFile = connectionManager::sendFile,
            sendVoiceMessage = connectionManager::sendVoiceMessage,
            retryMessage = connectionManager::retryMessage,
            editMessage = connectionManager::editMessage,
            toggleReaction = connectionManager::toggleReaction,
            copyToClipboard = ::copyToClipboard,
            disconnect = connectionManager::disconnect,
            installUpdate = connectionManager::installDownloadedUpdate,
            setOnionRouting = connectionManager::setOnionRoutingEnabled,
            setOnionWifiOnly = connectionManager::setOnionWifiOnly,
            rebuildCircuit = connectionManager::rebuildOnionCircuit,
            setNotifications = appState::setNotificationsEnabledPersisted,
            setPushEnabled = { enabled -> setPushEnabled(enabled, appState, connectionManager) },
            setHideNotificationSender = appState::setHideNotificationSenderPersisted,
            setHideNotificationContent = appState::setHideNotificationContentPersisted,
            clearHistory = appState::clearAllMessages,
            setAppLock = appState::setAppLockEnabledPersisted,
            requestUnlock = ::showUnlockPrompt,
            startCall = { peerKeyHex -> withAudioPermission { callManager.startCall(peerKeyHex) } },
            markChatSeen = connectionManager::markChatSeen,
            notifyTyping = connectionManager::notifyTyping,
            answerCall = { withAudioPermission(callManager::answerCall) },
            declineCall = callManager::declineCall,
            hangUpCall = callManager::hangUp,
            toggleMuteCall = callManager::toggleMute,
            createGroup = { name, memberKeys, onCreated -> groupManager.createGroup(name, memberKeys, onCreated) },
            sendGroupMessage = groupManager::sendGroupMessage,
            addGroupMember = groupManager::addMember,
            removeGroupMember = groupManager::removeMember,
            promoteGroupAdmin = groupManager::promoteAdmin,
            demoteGroupAdmin = groupManager::demoteAdmin,
            transferGroupOwnership = groupManager::transferOwnership,
            setGroupAnnouncementMode = groupManager::setAnnouncementMode,
            setGroupInviteLinksEnabled = groupManager::setInviteLinksEnabled,
            createGroupInviteLink = groupManager::createInviteLink,
            leaveGroup = groupManager::leaveGroup,
            joinGroupViaInvite = { link, onResult -> groupManager.joinViaInvite(link, onResult) },
        )

        setContent {
            VoronTheme(themeMode = appState.themeMode) {
                CompositionLocalProvider(
                    LocalDensity provides LocalDensity.current.let {
                        Density(it.density, appState.fontScale)
                    },
                ) {
                    Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background) {
                        // Only shown over the phone's own lock screen while a call is actually
                        // active (incoming ring, outgoing ring, or connected) — set dynamically
                        // here rather than as a static manifest attribute, which would make
                        // *every* launch of this Activity (e.g. from a message notification)
                        // show its current content over the lock screen, not just calls.
                        LaunchedEffect(appState.activeCall) {
                            val callActive = appState.activeCall != null
                            setShowWhenLocked(callActive)
                            setTurnScreenOn(callActive)
                        }
                        VoronApp(
                            appState = appState,
                            actions = actions,
                            appVersionLabel = appVersionLabel,
                            deepLinkPeerKey = pendingDeepLinkPeerKey,
                            onDeepLinkConsumed = { pendingDeepLinkPeerKey = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(MessageNotifier.EXTRA_PEER_KEY)?.let { pendingDeepLinkPeerKey = it }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Voron", text))
    }

    /** Biometric if enrolled, falling back to the device's own PIN/pattern/password otherwise — never Voron's own credential, there isn't one. */
    private fun showUnlockPrompt() {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Voron")
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    (application as VoronApplication).appState.isLocked = false
                }
            },
        )
        prompt.authenticate(promptInfo)
    }

    private companion object {
        // Fixed, well within the 16-bit range android.app.Activity#onRequestPermissionsResult
        // requires — deliberately not using registerForActivityResult(RequestPermission()) here,
        // whose auto-generated request codes intentionally start at 0x10000 (to avoid colliding
        // with manual ones) and can crash FragmentActivity's own 16-bit validation for permission
        // requests specifically (a known androidx.activity/androidx.fragment conflict).
        const val REQUEST_CODE_NOTIFICATIONS = 1001
        const val REQUEST_CODE_AUDIO = 1002
    }
}
