package messenger.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import messenger.android.data.ChatMessage
import messenger.android.ui.theme.VoronAvatarGradient
import messenger.android.ui.theme.VoronViolet
import messenger.common.group.GroupRole
import messenger.common.group.GroupState

/**
 * A group's chat — deliberately simpler than [ChatDetailScreen]: text only for v1 (no reply/react/
 * edit/voice/files/link-previews yet), but real end-to-end group encryption underneath (see
 * [messenger.android.data.GroupManager]). [nicknameFor] resolves a member's device key to whatever
 * name is known for them (falls back to a key prefix for members who aren't 1:1 contacts).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    group: GroupState?,
    ownKeyHex: String,
    messages: List<ChatMessage>,
    nicknameFor: (String) -> String,
    messageInput: String,
    onMessageInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpen: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    DisposableEffect(Unit) {
        onOpen()
        onDispose(onClose)
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val selfRole = group?.members?.get(ownKeyHex)?.role
    val canSend = group != null && !(group.announcementMode && selfRole == GroupRole.MEMBER)

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.height(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(group?.name.orEmpty().ifBlank { "Group" }, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Text(
                            "${group?.members?.size ?: 0} members" + if (group?.isFrozen == true) " · no admin" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onOpenInfo) { Icon(Icons.Filled.Groups, contentDescription = "Group info") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (canSend) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = onMessageInputChange,
                        placeholder = { Text("Message") },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onSend) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.height(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Only admins can post in this group",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (group == null) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(messages, key = { it.messageId ?: it.timestampMillis }) { message ->
                    GroupMessageBubble(message, senderName = message.senderKeyHex?.let(nicknameFor))
                }
            }
        }
    }
}

@Composable
private fun GroupMessageBubble(message: ChatMessage, senderName: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start,
    ) {
        if (!message.fromMe && senderName != null) {
            Text(
                senderName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 1.dp),
            )
        }
        val bubbleShape = remember(message.fromMe) {
            RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = if (message.fromMe) 20.dp else 6.dp,
                bottomEnd = if (message.fromMe) 6.dp else 20.dp,
            )
        }
        val outgoingBrush = remember { Brush.linearGradient(VoronAvatarGradient) }
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .shadow(
                    elevation = if (message.fromMe) 5.dp else 1.dp,
                    shape = bubbleShape,
                    ambientColor = if (message.fromMe) VoronViolet.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.1f),
                    spotColor = if (message.fromMe) VoronViolet.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.1f),
                )
                .background(if (message.fromMe) outgoingBrush else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)), bubbleShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                message.text,
                color = if (message.fromMe) Color.White else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        // Timestamp lives OUTSIDE the bubble, same as the 1:1 MessageBubble -- putting it inside
        // with fillMaxWidth() (the original mistake here) forced every bubble to stretch to the
        // full 280.dp max width regardless of how short the text was.
        Text(
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestampMillis)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}
