package messenger.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import messenger.android.ui.theme.voronNeutralIconContainerColor
import messenger.android.ui.theme.voronPinColor
import messenger.android.ui.theme.voronSheetContainerColor

/** Fixed quick-react set — a full emoji picker is more than a 1:1 chat needs. */
private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageOptionsSheet(
    messageText: String,
    isPinned: Boolean,
    isFromMe: Boolean,
    currentReactionMine: String?,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onReact: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = voronSheetContainerColor(),
    ) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                text = "“${messageText.take(140)}${if (messageText.length > 140) "…" else ""}”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                for (emoji in QUICK_REACTIONS) {
                    QuickReactionButton(
                        emoji = emoji,
                        selected = emoji == currentReactionMine,
                        // Tapping the reaction already on this message from me clears it — same
                        // toggle-off gesture as most messengers' quick-react rows.
                        onClick = { onReact(if (emoji == currentReactionMine) "" else emoji) },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.width(4.dp))
            MessageOptionRow(icon = Icons.AutoMirrored.Filled.Reply, label = "Reply", onClick = onReply)
            MessageOptionRow(icon = Icons.Filled.ContentCopy, label = "Copy text", onClick = onCopy)
            if (isFromMe) {
                MessageOptionRow(icon = Icons.Filled.Edit, label = "Edit message", onClick = onEdit)
            }
            MessageOptionRow(
                icon = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                label = if (isPinned) "Unpin message" else "Pin message",
                tint = voronPinColor(),
                onClick = onTogglePin,
            )
            MessageOptionRow(
                icon = Icons.Filled.DeleteOutline,
                label = "Delete message",
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun QuickReactionButton(emoji: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .background(
                color = if (selected) voronNeutralIconContainerColor() else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    ) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun MessageOptionRow(
    icon: ImageVector,
    label: String,
    tint: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    val contentColor = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onBackground else tint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = contentColor)
    }
}
