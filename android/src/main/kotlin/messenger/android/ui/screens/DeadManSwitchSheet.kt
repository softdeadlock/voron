package messenger.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import messenger.android.data.Contact
import messenger.android.data.DeadManSwitchAction
import messenger.android.data.DeadManSwitchConfig
import messenger.android.ui.theme.voronNeutralIconContainerColor
import messenger.android.ui.theme.voronSheetContainerColor

private val INTERVAL_OPTIONS = listOf(3, 7, 14, 30)

/**
 * Configures the dead man's switch (see `DeadManSwitchWorker`): fires [DeadManSwitchAction.SEND_MESSAGE]
 * to a chosen contact, or [DeadManSwitchAction.WIPE_APP_DATA], once this device hasn't been opened
 * for [DeadManSwitchConfig.intervalDays]. All local — the target/message aren't set until [onSave]
 * is tapped, and nothing about this configuration is itself sent anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeadManSwitchSheet(
    initialConfig: DeadManSwitchConfig,
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onSave: (DeadManSwitchConfig) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var enabled by remember { mutableStateOf(initialConfig.enabled) }
    var intervalDays by remember { mutableStateOf(initialConfig.intervalDays) }
    var action by remember { mutableStateOf(initialConfig.action) }
    var targetPeerKeyHex by remember { mutableStateOf(initialConfig.targetPeerKeyHex) }
    var messageText by remember { mutableStateOf(initialConfig.messageText) }
    var showContactPicker by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }

    val targetNickname = contacts.firstOrNull { it.deviceKeyHex == targetPeerKeyHex }?.nickname
    val canSave = !enabled || (action == DeadManSwitchAction.WIPE_APP_DATA) ||
        (targetPeerKeyHex != null && messageText.isNotBlank())

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
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).background(voronNeutralIconContainerColor(), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Dead man's switch", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "If this app isn't opened for the chosen number of days, it fires once automatically. " +
                    "Opening the app at any point resets the countdown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (enabled) {
                Spacer(Modifier.height(20.dp))
                Text("Trigger after", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    INTERVAL_OPTIONS.forEach { days ->
                        val selected = intervalDays == days
                        Box(
                            modifier = Modifier
                                .background(if (selected) MaterialTheme.colorScheme.primary else voronNeutralIconContainerColor(), RoundedCornerShape(20.dp))
                                .clickable { intervalDays = days }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                "${days}d",
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("Action", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                ActionChoiceRow(
                    label = "Send a message",
                    detail = "To one contact, written now, sent then",
                    selected = action == DeadManSwitchAction.SEND_MESSAGE,
                    onClick = { action = DeadManSwitchAction.SEND_MESSAGE },
                )
                Spacer(Modifier.height(8.dp))
                ActionChoiceRow(
                    label = "Wipe this app's data",
                    detail = "Contacts, messages, and keys — like a fresh install",
                    selected = action == DeadManSwitchAction.WIPE_APP_DATA,
                    destructive = true,
                    onClick = { action = DeadManSwitchAction.WIPE_APP_DATA },
                )

                if (action == DeadManSwitchAction.SEND_MESSAGE) {
                    Spacer(Modifier.height(20.dp))
                    Text("Recipient", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(voronNeutralIconContainerColor(), RoundedCornerShape(12.dp))
                            .clickable { showContactPicker = true }
                            .padding(14.dp),
                    ) {
                        Text(targetNickname ?: "Choose a contact", color = if (targetNickname == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        label = { Text("Message") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val next = DeadManSwitchConfig(
                        enabled = enabled,
                        intervalDays = intervalDays,
                        action = action,
                        targetPeerKeyHex = targetPeerKeyHex,
                        messageText = messageText,
                        lastActivityMillis = System.currentTimeMillis(),
                    )
                    if (enabled && action == DeadManSwitchAction.WIPE_APP_DATA && !initialConfig.let { it.enabled && it.action == DeadManSwitchAction.WIPE_APP_DATA }) {
                        showWipeConfirm = true
                    } else {
                        onSave(next)
                        onDismiss()
                    }
                },
                enabled = canSave,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = if (enabled && action == DeadManSwitchAction.WIPE_APP_DATA) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }

    if (showContactPicker) {
        ContactPickerDialog(
            contacts = contacts,
            onPick = { targetPeerKeyHex = it; showContactPicker = false },
            onDismiss = { showContactPicker = false },
        )
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("Arm the wipe switch?") },
            text = { Text("If this app goes untouched for $intervalDays days, every contact, message, and key on this device is deleted automatically — no confirmation, no undo.") },
            confirmButton = {
                TextButton(onClick = {
                    onSave(
                        DeadManSwitchConfig(
                            enabled = true,
                            intervalDays = intervalDays,
                            action = DeadManSwitchAction.WIPE_APP_DATA,
                            targetPeerKeyHex = null,
                            messageText = "",
                            lastActivityMillis = System.currentTimeMillis(),
                        ),
                    )
                    showWipeConfirm = false
                    onDismiss()
                }) { Text("Arm it", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showWipeConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActionChoiceRow(label: String, detail: String, selected: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(voronNeutralIconContainerColor(), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ContactPickerDialog(contacts: List<Contact>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a contact") },
        text = {
            LazyColumn {
                items(contacts) { contact ->
                    Text(
                        contact.nickname,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(contact.deviceKeyHex) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
