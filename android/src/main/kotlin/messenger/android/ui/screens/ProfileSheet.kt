package messenger.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import messenger.android.data.AvatarIconId
import messenger.android.ui.Avatar
import messenger.android.ui.QR_KEY_PREFIX
import messenger.android.ui.QrCodeCard
import messenger.android.ui.drawAvatarGlyph
import messenger.android.ui.theme.VoronAvatarGradient
import messenger.android.ui.theme.voronNeutralIconContainerColor
import messenger.android.ui.theme.voronSheetContainerColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    ownKeyHex: String,
    displayName: String,
    avatarIconId: AvatarIconId?,
    onAvatarIconChange: (AvatarIconId?) -> Unit,
    onEditName: () -> Unit,
    onDismiss: () -> Unit,
    onCopyKey: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAvatarPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = voronSheetContainerColor(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                Avatar(
                    displayName.ifBlank { ownKeyHex },
                    size = 64.dp,
                    iconId = avatarIconId,
                    modifier = Modifier.clickable { showAvatarPicker = true },
                )
                DropdownMenu(
                    expanded = showAvatarPicker,
                    onDismissRequest = { showAvatarPicker = false },
                    modifier = Modifier.background(voronSheetContainerColor(), RoundedCornerShape(20.dp)),
                ) {
                    AvatarPickerGrid(
                        current = avatarIconId,
                        onSelect = { icon ->
                            onAvatarIconChange(icon)
                            showAvatarPicker = false
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName.ifBlank { "No name set" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onEditName, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit name", modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Sent encrypted to a peer only once you exchange a message with them — never visible to the relay.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "SCAN TO ADD ME",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(10.dp))
            // The QR carries the same device key — a friend scans it from the Add-contact
            // sheet instead of typing 64 hex chars.
            QrCodeCard(content = QR_KEY_PREFIX + ownKeyHex, size = 200.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Your device key",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                ownKeyHex.chunked(32).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onCopyKey,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Copy key", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvatarPickerGrid(current: AvatarIconId?, onSelect: (AvatarIconId?) -> Unit) {
    FlowRow(
        modifier = Modifier
            .width(220.dp)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AvatarOption(selected = current == null, onClick = { onSelect(null) }) {
            Text("Aa", color = Color.White, fontWeight = FontWeight.Bold)
        }
        AvatarIconId.entries.forEach { icon ->
            AvatarOption(selected = current == icon, onClick = { onSelect(icon) }) {
                Canvas(modifier = Modifier.size(26.dp)) { drawAvatarGlyph(icon) }
            }
        }
    }
}

@Composable
private fun AvatarOption(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Brush.linearGradient(VoronAvatarGradient), CircleShape)
            .then(if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
