package messenger.android.ui

import android.net.Uri
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import messenger.android.data.AppState
import messenger.android.data.CallUiState
import messenger.android.data.ChatMessage
import messenger.android.data.ConnectionState
import messenger.android.data.Contact
import messenger.android.data.DRAFTS_DEVICE_KEY
import messenger.android.data.GroupAvatarIconId
import messenger.android.data.StickerId
import messenger.android.data.wireValue
import messenger.android.ui.screens.AddContactSheet
import messenger.android.ui.screens.AddGroupMemberSheet
import messenger.android.ui.screens.CallScreen
import messenger.android.ui.screens.ChatDetailScreen
import messenger.android.ui.screens.ChatListScreen
import messenger.android.ui.screens.ConnectScreen
import messenger.android.ui.screens.CreateGroupSheet
import messenger.android.ui.screens.EditNicknameSheet
import messenger.android.ui.screens.GroupChatScreen
import messenger.android.ui.screens.GroupInfoScreen
import messenger.android.ui.screens.JoinGroupSheet
import messenger.android.ui.screens.ProfileSheet
import messenger.android.ui.screens.SearchScreen
import messenger.android.ui.screens.SettingsScreen
import messenger.android.ui.screens.UpdateAvailableSheet
import messenger.android.ui.theme.voronEncryptedColor
import messenger.common.client.ApplicationMessage
import messenger.common.util.toHex

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }

/**
 * Every app-level callback the UI can fire, bundled so screens receive one
 * object instead of two dozen individually-drilled function parameters.
 * Constructed once in MainActivity, where each field is wired to
 * ConnectionManager / CallManager / AppState / Activity-only APIs.
 */
class VoronActions(
    val connect: () -> Unit,
    val sendMessage: (peerKeyHex: String, text: String, replyTo: ChatMessage?, linkPreview: ApplicationMessage.LinkPreviewRef?) -> Unit,
    val fetchLinkPreview: suspend (String) -> ApplicationMessage.LinkPreviewRef?,
    val sendFile: (peerKeyHex: String, uri: Uri) -> Unit,
    val sendVoiceMessage: (peerKeyHex: String, file: File, durationMillis: Long) -> Unit,
    val sendSticker: (peerKeyHex: String, sticker: StickerId) -> Unit,
    val retryMessage: (peerKeyHex: String, index: Int) -> Unit,
    val editMessage: (peerKeyHex: String, messageId: String, newText: String) -> Unit,
    val toggleReaction: (peerKeyHex: String, messageId: String, emoji: String?) -> Unit,
    val copyToClipboard: (String) -> Unit,
    val disconnect: () -> Unit,
    val installUpdate: () -> Unit,
    val setOnionRouting: (Boolean) -> Unit,
    val setOnionWifiOnly: (Boolean) -> Unit,
    val rebuildCircuit: () -> Unit,
    val setNotifications: (Boolean) -> Unit,
    val setPushEnabled: (Boolean) -> Unit,
    val setHideNotificationSender: (Boolean) -> Unit,
    val setHideNotificationContent: (Boolean) -> Unit,
    val clearHistory: () -> Unit,
    val setAppLock: (Boolean) -> Unit,
    val requestUnlock: () -> Unit,
    val startCall: (peerKeyHex: String) -> Unit,
    val markChatSeen: (peerKeyHex: String) -> Unit,
    val notifyTyping: (peerKeyHex: String) -> Unit,
    val answerCall: () -> Unit,
    val declineCall: () -> Unit,
    val hangUpCall: () -> Unit,
    val toggleMuteCall: () -> Unit,
    val createGroup: (name: String, memberKeys: List<String>, onCreated: (ByteArray) -> Unit) -> Unit,
    val sendGroupMessage: (groupId: ByteArray, text: String) -> Unit,
    val addGroupMember: (groupId: ByteArray, memberKeyHex: String) -> Unit,
    val removeGroupMember: (groupId: ByteArray, memberKeyHex: String) -> Unit,
    val promoteGroupAdmin: (groupId: ByteArray, memberKeyHex: String, permissions: Int) -> Unit,
    val demoteGroupAdmin: (groupId: ByteArray, memberKeyHex: String) -> Unit,
    val transferGroupOwnership: (groupId: ByteArray, newOwnerKeyHex: String) -> Unit,
    val setGroupAnnouncementMode: (groupId: ByteArray, enabled: Boolean) -> Unit,
    val setGroupAvatar: (groupId: ByteArray, avatarIconId: Int) -> Unit,
    val setGroupInviteLinksEnabled: (groupId: ByteArray, enabled: Boolean) -> Unit,
    val createGroupInviteLink: (groupId: ByteArray) -> String?,
    val leaveGroup: (groupId: ByteArray) -> Unit,
    val joinGroupViaInvite: (link: String, onResult: (String?) -> Unit) -> Unit,
)

/**
 * A rapid double-tap can fire a click handler twice before the first
 * navigate()/popBackStack() call finishes recomposing the backstack — the
 * second call then races against a mid-transition state and can leave the
 * NavHost with no resumed destination (blank screen). Guarding on the
 * current entry's RESUMED state is the standard fix for that race.
 */
private fun NavBackStackEntry.isResumed() = lifecycle.currentState == Lifecycle.State.RESUMED

@Composable
fun VoronApp(
    appState: AppState,
    actions: VoronActions,
    appVersionLabel: String,
    deepLinkPeerKey: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    // VoronMainContent is composed unconditionally, always — it used to live behind a Crossfade
    // branch alongside the lock/call screens, which tore it down and rebuilt it from scratch
    // every time a call started or ended. That silently reset more than just the NavController:
    // its LaunchedEffect(connection) re-ran its *first* invocation on every rebuild, and since
    // appState.connection is almost always already Connected by the time a call ends, that
    // effect force-navigated to "chats" — discarding whatever chat you were actually in ("зашёл
    // из чата начал звонить а вылетело хрен пойми куда"). Lock/call screens are now plain
    // overlays drawn on top via Box + AnimatedVisibility instead of an exclusive-branch
    // Crossfade, so the main content underneath is never torn down for either of them.
    // VoronMainContent (and its message input) is never torn down under the call overlay below,
    // so if you were mid-type when a call starts, the OutlinedTextField keeps focus and the IME
    // stays up, floating uselessly under the call screen ("клавиатура не сворачивается при
    // звонке"). Explicitly drop focus + hide the IME the moment a call becomes active.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(appState.activeCall != null) {
        if (appState.activeCall != null) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VoronMainContent(
            appState = appState,
            actions = actions,
            appVersionLabel = appVersionLabel,
            deepLinkPeerKey = deepLinkPeerKey,
            onDeepLinkConsumed = onDeepLinkConsumed,
        )

        AnimatedVisibility(
            visible = appState.isLocked,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200)),
        ) {
            LockScreen(onUnlockClick = actions.requestUnlock)
        }

        // Drawn last (on top): an incoming call rings/shows even while locked, same as a real
        // phone call — only entering the resulting chat afterward is still gated by the lock
        // screen underneath.
        AnimatedVisibility(
            visible = appState.activeCall != null,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200)),
        ) {
            val call = appState.activeCall
            if (call != null) {
                val peerKeyHex = when (call) {
                    is CallUiState.IncomingRinging -> call.peerKeyHex
                    is CallUiState.OutgoingRinging -> call.peerKeyHex
                    is CallUiState.Connected -> call.peerKeyHex
                    is CallUiState.Unavailable -> call.peerKeyHex
                }
                CallScreen(
                    call = call,
                    peerNickname = appState.nicknameFor(peerKeyHex),
                    peerAvatarIconId = appState.avatarIconFor(peerKeyHex),
                    onAnswer = actions.answerCall,
                    onDecline = actions.declineCall,
                    onHangUp = actions.hangUpCall,
                    onToggleMute = actions.toggleMuteCall,
                )
            }
        }
    }
}

@Composable
private fun VoronMainContent(
    appState: AppState,
    actions: VoronActions,
    appVersionLabel: String,
    deepLinkPeerKey: String?,
    onDeepLinkConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    var showAddContact by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showEditNickname by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showJoinGroup by remember { mutableStateOf(false) }
    var addMemberToGroup by remember { mutableStateOf<ByteArray?>(null) }

    val connection = appState.connection
    LaunchedEffect(connection) {
        when (connection) {
            is ConnectionState.Connected -> {
                // Give the connect screen's success animation (loading ring -> shield) time to
                // play before yanking it off-screen, but only on the fresh-launch path -- a
                // reconnect while the user is already browsing chats shouldn't pause anything.
                if (navController.currentDestination?.route == "connect") delay(550)
                navController.navigate("chats") {
                    popUpTo("connect") { inclusive = true }
                }
            }
            is ConnectionState.Idle -> if (navController.currentDestination?.route != "connect") {
                navController.navigate("connect") {
                    popUpTo(0) { inclusive = true }
                }
            }
            else -> Unit
        }
    }

    // Tapping a message notification should land directly in that chat — only once
    // we're actually connected and on the chat list, otherwise it'd race the
    // connect-triggered navigation above.
    LaunchedEffect(deepLinkPeerKey, connection) {
        if (deepLinkPeerKey != null && connection is ConnectionState.Connected) {
            navController.navigate("chat/$deepLinkPeerKey")
            onDeepLinkConsumed()
        }
    }

    NavHost(navController = navController, startDestination = "connect") {
        composable("connect") {
            ConnectScreen(
                connection = appState.connection,
                onConnect = actions.connect,
            )
        }
        composable("chats") {
            ChatListScreen(
                contacts = appState.contacts,
                groups = appState.groups,
                lastMessageFor = { appState.lastMessageFor(it) },
                onOpenChat = { contact ->
                    if (navController.currentBackStackEntry?.isResumed() != false) {
                        navController.navigate("chat/${contact.deviceKeyHex}")
                    }
                },
                onOpenGroup = { group ->
                    if (navController.currentBackStackEntry?.isResumed() != false) {
                        navController.navigate("group/${group.groupId.toHex()}")
                    }
                },
                onAddContact = { showAddContact = true },
                onCreateGroup = { showCreateGroup = true },
                onJoinGroup = { showJoinGroup = true },
                onOpenProfile = { showProfile = true },
                onOpenSearch = {
                    if (navController.currentBackStackEntry?.isResumed() != false) {
                        navController.navigate("search")
                    }
                },
                onOpenSettings = {
                    if (navController.currentBackStackEntry?.isResumed() != false) {
                        navController.navigate("settings")
                    }
                },
                onRemoveContact = { contact -> appState.removeContact(contact) },
                onTogglePinnedChat = { contact -> appState.toggleChatPinned(contact) },
                onToggleBlockedContact = { contact -> appState.setBlocked(contact.deviceKeyHex, !contact.blocked) },
                onSetDisappearAfter = { peerKeyHex, duration -> appState.setDisappearAfter(peerKeyHex, duration) },
                isReconnecting = connection !is ConnectionState.Connected,
            )
        }
        composable("search") {
            SearchScreen(
                nicknameFor = { appState.nicknameFor(it) },
                search = { query -> appState.searchMessages(query) },
                onResultClick = { hit ->
                    appState.pendingSearchJumpMessageId = hit.messageId
                    if (navController.currentBackStackEntry?.isResumed() != false) {
                        navController.navigate("chat/${hit.peerKeyHex}") { popUpTo("chats") }
                    }
                },
                onBack = {
                    if (navController.currentBackStackEntry?.isResumed() != false) {
                        navController.popBackStack()
                    }
                },
            )
        }
        composable("settings") {
            SettingsScreen(
                themeMode = appState.themeMode,
                onThemeModeChange = appState::updateThemeMode,
                themeVariant = appState.themeVariant,
                onThemeVariantChange = appState::updateThemeVariant,
                isFounder = appState.isFounder,
                fontScale = appState.fontScale,
                onFontScaleChange = appState::updateFontScale,
                onionRoutingEnabled = appState.onionRoutingEnabled,
                onOnionRoutingChange = actions.setOnionRouting,
                onionWifiOnly = appState.onionWifiOnly,
                onOnionWifiOnlyChange = actions.setOnionWifiOnly,
                onionCircuitActive = appState.onionCircuitActive,
                onRebuildCircuit = actions.rebuildCircuit,
                notificationsEnabled = appState.notificationsEnabled,
                onNotificationsChange = actions.setNotifications,
                pushEnabled = appState.pushEnabled,
                onPushEnabledChange = actions.setPushEnabled,
                hideNotificationSender = appState.hideNotificationSender,
                onHideNotificationSenderChange = actions.setHideNotificationSender,
                hideNotificationContent = appState.hideNotificationContent,
                onHideNotificationContentChange = actions.setHideNotificationContent,
                onClearHistory = actions.clearHistory,
                appLockEnabled = appState.appLockEnabled,
                onAppLockChange = actions.setAppLock,
                appVersionLabel = appVersionLabel,
                onBack = {
                    if (navController.currentBackStackEntry?.isResumed() != false) {
                        navController.popBackStack()
                    }
                },
            )
        }
        composable("chat/{peerKey}") { backStackEntry ->
            val peerKeyHex = backStackEntry.arguments?.getString("peerKey") ?: return@composable
            var messageInput by remember(peerKeyHex) { mutableStateOf("") }
            ChatDetailScreen(
                ownKeyHex = (appState.connection as? ConnectionState.Connected)?.ownKeyHex.orEmpty(),
                peerNickname = appState.nicknameFor(peerKeyHex),
                peerKeyHex = peerKeyHex,
                peerAvatarIconId = appState.avatarIconFor(peerKeyHex),
                isVerified = appState.isVerified(peerKeyHex),
                messages = appState.conversationFor(peerKeyHex),
                messageInput = messageInput,
                onMessageInputChange = {
                    messageInput = it
                    if (it.isNotBlank()) actions.notifyTyping(peerKeyHex)
                },
                onSend = { replyTo, linkPreview ->
                    if (messageInput.isNotBlank()) {
                        actions.sendMessage(peerKeyHex, messageInput, replyTo, linkPreview)
                        messageInput = ""
                    }
                },
                onFetchLinkPreview = actions.fetchLinkPreview,
                onSendFile = { uri -> actions.sendFile(peerKeyHex, uri) },
                onSendVoice = { file, durationMillis -> actions.sendVoiceMessage(peerKeyHex, file, durationMillis) },
                onSendSticker = { sticker -> actions.sendSticker(peerKeyHex, sticker) },
                isFounder = appState.isFounder,
                onTogglePin = { index -> appState.togglePin(peerKeyHex, index) },
                onDeleteMessage = { index -> appState.deleteMessage(peerKeyHex, index) },
                onRetryMessage = { index -> actions.retryMessage(peerKeyHex, index) },
                onSendEdit = { messageId, newText -> actions.editMessage(peerKeyHex, messageId, newText) },
                onReact = { messageId, emoji -> actions.toggleReaction(peerKeyHex, messageId, emoji) },
                onCopyText = actions.copyToClipboard,
                onMarkVerified = { appState.markVerified(peerKeyHex) },
                onBack = {
                    if (navController.currentBackStackEntry?.isResumed() != false) {
                        navController.popBackStack()
                    }
                },
                onOpen = {
                    appState.openPeerKeyHex = peerKeyHex
                    appState.markRead(peerKeyHex)
                    actions.markChatSeen(peerKeyHex)
                },
                onClose = {
                    if (appState.openPeerKeyHex == peerKeyHex) appState.openPeerKeyHex = null
                },
                disappearAfterMillis = appState.disappearAfterFor(peerKeyHex),
                onStartCall = { actions.startCall(peerKeyHex) },
                isPeerTyping = appState.typingPeerKeys.contains(peerKeyHex),
                jumpToMessageId = appState.pendingSearchJumpMessageId,
                onJumpToMessageConsumed = { appState.pendingSearchJumpMessageId = null },
            )
        }
        composable("group/{groupId}") { backStackEntry ->
            val groupIdHex = backStackEntry.arguments?.getString("groupId") ?: return@composable
            var messageInput by remember(groupIdHex) { mutableStateOf("") }
            val ownKeyHex = (appState.connection as? ConnectionState.Connected)?.ownKeyHex.orEmpty()
            GroupChatScreen(
                group = appState.groupFor(groupIdHex),
                ownKeyHex = ownKeyHex,
                messages = appState.conversationFor(groupIdHex),
                nicknameFor = { appState.nicknameFor(it) },
                messageInput = messageInput,
                onMessageInputChange = { messageInput = it },
                onSend = {
                    if (messageInput.isNotBlank()) {
                        actions.sendGroupMessage(hexToBytes(groupIdHex), messageInput)
                        messageInput = ""
                    }
                },
                onBack = {
                    if (navController.currentBackStackEntry?.isResumed() != false) {
                        navController.popBackStack()
                    }
                },
                onOpenInfo = {
                    if (navController.currentBackStackEntry?.isResumed() != false) {
                        navController.navigate("groupinfo/$groupIdHex")
                    }
                },
            )
        }
        composable("groupinfo/{groupId}") { backStackEntry ->
            val groupIdHex = backStackEntry.arguments?.getString("groupId") ?: return@composable
            val groupId = hexToBytes(groupIdHex)
            val ownKeyHex = (appState.connection as? ConnectionState.Connected)?.ownKeyHex.orEmpty()
            GroupInfoScreen(
                group = appState.groupFor(groupIdHex),
                ownKeyHex = ownKeyHex,
                nicknameFor = { appState.nicknameFor(it) },
                onBack = {
                    if (navController.currentBackStackEntry?.isResumed() != false) {
                        navController.popBackStack()
                    }
                },
                onOpenMember = { keyHex ->
                    if (navController.currentBackStackEntry?.isResumed() != false) {
                        navController.navigate("chat/$keyHex")
                    }
                },
                onRemoveMember = { keyHex -> actions.removeGroupMember(groupId, keyHex) },
                onPromoteAdmin = { keyHex, permissions -> actions.promoteGroupAdmin(groupId, keyHex, permissions) },
                onDemoteAdmin = { keyHex -> actions.demoteGroupAdmin(groupId, keyHex) },
                onTransferOwnership = { keyHex -> actions.transferGroupOwnership(groupId, keyHex) },
                onSetAnnouncementMode = { enabled -> actions.setGroupAnnouncementMode(groupId, enabled) },
                onSetGroupAvatar = { iconId -> actions.setGroupAvatar(groupId, iconId.wireValue) },
                onSetInviteLinksEnabled = { enabled -> actions.setGroupInviteLinksEnabled(groupId, enabled) },
                onCreateInviteLink = { actions.createGroupInviteLink(groupId) },
                onCopyToClipboard = actions.copyToClipboard,
                onLeaveGroup = {
                    actions.leaveGroup(groupId)
                    navController.popBackStack("chats", inclusive = false)
                },
                onAddMember = { addMemberToGroup = groupId },
            )
        }
    }

    if (showAddContact) {
        AddContactSheet(
            onDismiss = { showAddContact = false },
            onAdd = { keyHex ->
                appState.addContactIfMissing(Contact(nickname = keyHex.take(8), deviceKeyHex = keyHex, nicknameConfirmed = false))
                showAddContact = false
            },
        )
    }

    if (showProfile) {
        val ownKey = (appState.connection as? ConnectionState.Connected)?.ownKeyHex.orEmpty()
        ProfileSheet(
            ownKeyHex = ownKey,
            displayName = appState.displayName,
            avatarIconId = appState.avatarIconId,
            onAvatarIconChange = appState::updateAvatarIcon,
            onEditName = {
                showProfile = false
                showEditNickname = true
            },
            onDismiss = { showProfile = false },
            onCopyKey = { actions.copyToClipboard(ownKey) },
            onDisconnect = {
                showProfile = false
                actions.disconnect()
            },
        )
    }

    if (showEditNickname) {
        EditNicknameSheet(
            currentName = appState.displayName,
            onDismiss = { showEditNickname = false },
            onSave = { name ->
                appState.updateDisplayName(name)
                showEditNickname = false
            },
        )
    }

    if (showCreateGroup) {
        CreateGroupSheet(
            contacts = appState.contacts,
            onDismiss = { showCreateGroup = false },
            onCreate = { name, memberKeys ->
                showCreateGroup = false
                actions.createGroup(name, memberKeys) { groupId ->
                    navController.navigate("group/${groupId.toHex()}")
                }
            },
        )
    }

    if (showJoinGroup) {
        JoinGroupSheet(
            onDismiss = { showJoinGroup = false },
            onJoin = { link, onResult -> actions.joinGroupViaInvite(link, onResult) },
        )
    }

    addMemberToGroup?.let { groupId ->
        val group = appState.groupFor(groupId.toHex())
        val existingKeys = group?.members?.keys.orEmpty()
        AddGroupMemberSheet(
            contacts = appState.contacts.filter { it.deviceKeyHex != DRAFTS_DEVICE_KEY && it.deviceKeyHex !in existingKeys },
            onDismiss = { addMemberToGroup = null },
            onPick = { keyHex -> actions.addGroupMember(groupId, keyHex) },
        )
    }

    appState.availableUpdate?.let { update ->
        UpdateAvailableSheet(
            versionName = update.versionName,
            downloading = appState.updateDownloading,
            readyToInstall = appState.updateReadyApkPath != null,
            onDismiss = { appState.availableUpdate = null },
            onInstall = actions.installUpdate,
        )
    }
}

@Composable
private fun LockScreen(onUnlockClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = voronEncryptedColor(), modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Voron is locked", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlockClick, shape = RoundedCornerShape(26.dp)) {
            Text("Unlock", fontWeight = FontWeight.SemiBold)
        }
    }
}
