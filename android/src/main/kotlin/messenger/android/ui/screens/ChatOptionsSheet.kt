package messenger.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit
import messenger.android.data.Contact
import messenger.android.data.DRAFTS_DEVICE_KEY
import messenger.android.ui.Avatar
import messenger.android.ui.theme.voronNeutralIconTint
import messenger.android.ui.theme.voronPinColor
import messenger.android.ui.theme.voronSheetContainerColor

/** The four disappearing-messages durations offered — null means off/never. */
val DISAPPEARING_DURATIONS: List<Pair<String, Long?>> = listOf(
    "Off" to null,
    "1 hour" to TimeUnit.HOURS.toMillis(1),
    "1 day" to TimeUnit.DAYS.toMillis(1),
    "1 week" to TimeUnit.DAYS.toMillis(7),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatOptionsSheet(
    contact: Contact,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onToggleBlock: () -> Unit,
    onSetDisappearAfter: (Long?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDurationPicker by remember { mutableStateOf(false) }
    val isDrafts = contact.deviceKeyHex == DRAFTS_DEVICE_KEY

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = voronSheetContainerColor(),
    ) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(contact.nickname, size = 40.dp, isDrafts = isDrafts, iconId = contact.avatarIconId)
                Spacer(Modifier.width(12.dp))
                Text(contact.nickname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.width(4.dp))

            if (showDurationPicker) {
                DISAPPEARING_DURATIONS.forEach { (label, durationMillis) ->
                    ChatOptionRow(
                        icon = Icons.Filled.Check,
                        label = label,
                        tint = if (contact.disappearAfterMillis == durationMillis) voronPinColor() else Color.Transparent,
                        onClick = {
                            onSetDisappearAfter(durationMillis)
                            showDurationPicker = false
                        },
                    )
                }
            } else {
                ChatOptionRow(
                    icon = Icons.Filled.PushPin.takeIf { contact.pinned } ?: Icons.Outlined.PushPin,
                    label = if (contact.pinned) "Unpin chat" else "Pin chat",
                    tint = voronPinColor(),
                    onClick = onTogglePin,
                )
                ChatOptionRow(
                    icon = Icons.Filled.Timer,
                    label = "Disappearing messages",
                    detail = DISAPPEARING_DURATIONS.first { it.second == contact.disappearAfterMillis }.first,
                    tint = voronNeutralIconTint(),
                    onClick = { showDurationPicker = true },
                )
                if (!isDrafts) {
                    ChatOptionRow(
                        icon = Icons.Filled.Block,
                        label = if (contact.blocked) "Unblock contact" else "Block contact",
                        tint = if (contact.blocked) voronNeutralIconTint() else MaterialTheme.colorScheme.error,
                        onClick = onToggleBlock,
                    )
                }
                ChatOptionRow(
                    icon = Icons.Filled.DeleteOutline,
                    label = "Delete conversation",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun ChatOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String? = null,
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
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (tint == Color.Transparent) MaterialTheme.colorScheme.onBackground else contentColor)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
