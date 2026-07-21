package messenger.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import messenger.android.data.ChatMessage
import messenger.android.data.Contact
import messenger.android.data.DRAFTS_DEVICE_KEY
import messenger.android.data.GroupAvatarIconId
import messenger.android.ui.Avatar
import messenger.android.ui.VoronLogo
import messenger.android.ui.theme.voronAccentGradient
import messenger.android.ui.theme.VoronViolet
import messenger.android.ui.theme.voronEncryptedColor
import messenger.android.ui.theme.voronPinColor
import messenger.common.group.GroupState
import messenger.common.util.toHex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    contacts: List<Contact>,
    groups: List<GroupState> = emptyList(),
    lastMessageFor: (String) -> ChatMessage?,
    onOpenChat: (Contact) -> Unit,
    onOpenGroup: (GroupState) -> Unit = {},
    onAddContact: () -> Unit,
    onCreateGroup: () -> Unit = {},
    onJoinGroup: () -> Unit = {},
    onOpenProfile: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onRemoveContact: (Contact) -> Unit,
    onTogglePinnedChat: (Contact) -> Unit,
    onToggleBlockedContact: (Contact) -> Unit,
    onSetDisappearAfter: (peerKeyHex: String, durationMillis: Long?) -> Unit,
    isReconnecting: Boolean = false,
) {
    var showFabMenu by remember { mutableStateOf(false) }
    var optionsContact by remember { mutableStateOf<Contact?>(null) }
    var deleteContact by remember { mutableStateOf<Contact?>(null) }
    // Not wrapped in remember(): contacts is the live SnapshotStateList from AppState, and its
    // element identity doesn't change on mutation — memoizing on it would go stale after a pin toggle.
    // Groups have no pin concept yet, so they sort between pinned and unpinned contacts rather than
    // always floating to the very top regardless of what the user pinned.
    val pinnedContacts = contacts.filter { it.pinned }
    val unpinnedContacts = contacts.filterNot { it.pinned }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(onClick = onOpenSettings),
                        ) {
                            VoronLogo(size = 26.dp, tint = MaterialTheme.colorScheme.onBackground)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Voron",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    brush = Brush.linearGradient(voronAccentGradient()),
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                        }
                        if (isReconnecting) {
                            Text(
                                "Reconnecting…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search messages")
                    }
                    IconButton(onClick = onOpenProfile) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = "Profile")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (showFabMenu) {
                    FabMenuItem(label = "Join group", icon = Icons.Filled.GroupAdd, onClick = { showFabMenu = false; onJoinGroup() })
                    Spacer(Modifier.height(10.dp))
                    FabMenuItem(label = "New group", icon = Icons.Filled.Groups, onClick = { showFabMenu = false; onCreateGroup() })
                    Spacer(Modifier.height(10.dp))
                    FabMenuItem(label = "Add contact", icon = Icons.Filled.Add, onClick = { showFabMenu = false; onAddContact() })
                    Spacer(Modifier.height(10.dp))
                }
                // A gradient chip instead of the default flat-color FAB — ties it to the same
                // violet→lavender accent used on avatars, sent bubbles, and the QR card.
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .shadow(10.dp, CircleShape, ambientColor = VoronViolet.copy(alpha = 0.45f), spotColor = VoronViolet.copy(alpha = 0.45f))
                        .background(Brush.linearGradient(voronAccentGradient()), CircleShape)
                        .clickable(onClick = { showFabMenu = !showFabMenu }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New chat", tint = Color.White)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (contacts.isEmpty() && groups.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(pinnedContacts, key = { it.deviceKeyHex }) { contact ->
                    Box(modifier = Modifier.animateItem()) {
                        ContactRow(
                            contact = contact,
                            lastMessage = lastMessageFor(contact.deviceKeyHex),
                            onClick = { onOpenChat(contact) },
                            onLongClick = { optionsContact = contact },
                        )
                    }
                }
                items(groups, key = { "group:" + it.groupId.toHex() }) { group ->
                    Box(modifier = Modifier.animateItem()) {
                        GroupRow(group = group, lastMessage = lastMessageFor(group.groupId.toHex()), onClick = { onOpenGroup(group) })
                    }
                }
                items(unpinnedContacts, key = { it.deviceKeyHex }) { contact ->
                    Box(modifier = Modifier.animateItem()) {
                        ContactRow(
                            contact = contact,
                            lastMessage = lastMessageFor(contact.deviceKeyHex),
                            onClick = { onOpenChat(contact) },
                            onLongClick = { optionsContact = contact },
                        )
                    }
                }
            }
        }
    }

    optionsContact?.let { contact ->
        ChatOptionsSheet(
            contact = contact,
            onDismiss = { optionsContact = null },
            onTogglePin = {
                onTogglePinnedChat(contact)
                optionsContact = null
            },
            onDelete = {
                optionsContact = null
                deleteContact = contact
            },
            onToggleBlock = {
                onToggleBlockedContact(contact)
                optionsContact = null
            },
            onSetDisappearAfter = { duration -> onSetDisappearAfter(contact.deviceKeyHex, duration) },
        )
    }

    deleteContact?.let { contact ->
        DeleteChatSheet(
            nickname = contact.nickname,
            onDismiss = { deleteContact = null },
            onConfirm = {
                onRemoveContact(contact)
                deleteContact = null
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        VoronLogo(size = 40.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            "No conversations yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap + to add someone by their device key",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    contact: Contact,
    lastMessage: ChatMessage?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val isDrafts = contact.deviceKeyHex == DRAFTS_DEVICE_KEY
    val unreadTint = if (contact.hasUnread) {
        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(unreadTint)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(
                contact.nickname,
                isDrafts = isDrafts,
                iconId = contact.avatarIconId,
                size = 50.dp,
                modifier = Modifier.shadow(
                    3.dp, CircleShape,
                    ambientColor = VoronViolet.copy(alpha = 0.3f),
                    spotColor = VoronViolet.copy(alpha = 0.3f),
                ),
            )
            if (contact.hasUnread) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
            }
            if (contact.disappearAfterMillis != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = "Disappearing messages on",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (contact.pinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = "Pinned chat",
                        tint = voronPinColor(),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = contact.nickname,
                    style = MaterialTheme.typography.titleMedium,
                    fontStyle = if (contact.nicknameConfirmed) FontStyle.Normal else FontStyle.Italic,
                    fontWeight = if (contact.hasUnread) FontWeight.Bold else null,
                    color = if (contact.nicknameConfirmed) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.size(2.dp))
            Text(
                text = lastMessage?.let {
                    val prefix = if (it.fromMe && !isDrafts) "You: " else ""
                    val body = if (it.stickerId != null) "🐦 Sticker" else if (it.transferStatus != null) "📎 " + (it.attachmentName ?: "File") else it.text
                    prefix + body
                }
                    ?: if (isDrafts) "No notes yet" else if (contact.nicknameConfirmed) "No messages yet" else "Waiting for their first message…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (lastMessage != null) {
                Text(
                    text = relativeTime(lastMessage.timestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
            }
            Icon(
                when {
                    isDrafts -> Icons.Filled.EditNote
                    contact.nicknameConfirmed -> Icons.Filled.Lock
                    else -> Icons.Filled.HourglassEmpty
                },
                contentDescription = when {
                    isDrafts -> "Notes"
                    contact.nicknameConfirmed -> "Encrypted"
                    else -> "Session not established yet"
                },
                tint = if (contact.nicknameConfirmed && !isDrafts) voronEncryptedColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun FabMenuItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.padding(end = 10.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GroupRow(group: GroupState, lastMessage: ChatMessage?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(group.name, size = 50.dp, isGroup = true, groupIconId = GroupAvatarIconId.fromWireValue(group.avatarIconId))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(group.name.ifBlank { "Group" }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(2.dp))
            Text(
                text = lastMessage?.let { (if (it.fromMe) "You: " else "") + it.text } ?: "${group.members.size} members",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (lastMessage != null) {
            Text(relativeTime(lastMessage.timestampMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun relativeTime(timestampMillis: Long): String {
    val diffMinutes = (System.currentTimeMillis() - timestampMillis) / 60_000
    return when {
        diffMinutes < 1 -> "now"
        diffMinutes < 60 -> "${diffMinutes}m"
        diffMinutes < 60 * 24 -> "${diffMinutes / 60}h"
        diffMinutes < 60 * 24 * 7 -> "${diffMinutes / (60 * 24)}d"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestampMillis))
    }
}
