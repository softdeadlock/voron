package messenger.android.ui.screens

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import messenger.android.data.ThemeMode
import messenger.android.data.ThemeVariant
import messenger.android.ui.theme.displayName
import messenger.android.ui.theme.previewBackground
import messenger.android.ui.theme.previewColors
import messenger.android.ui.theme.voronEncryptedColor
import messenger.android.ui.theme.voronNeutralIconContainerColor
import messenger.android.ui.theme.voronNeutralIconTint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    themeVariant: ThemeVariant,
    onThemeVariantChange: (ThemeVariant) -> Unit,
    isFounder: Boolean,
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onionRoutingEnabled: Boolean,
    onOnionRoutingChange: (Boolean) -> Unit,
    onionWifiOnly: Boolean,
    onOnionWifiOnlyChange: (Boolean) -> Unit,
    onionCircuitActive: Boolean,
    onRebuildCircuit: () -> Unit,
    notificationsEnabled: Boolean,
    onNotificationsChange: (Boolean) -> Unit,
    pushEnabled: Boolean,
    onPushEnabledChange: (Boolean) -> Unit,
    hideNotificationSender: Boolean,
    onHideNotificationSenderChange: (Boolean) -> Unit,
    hideNotificationContent: Boolean,
    onHideNotificationContentChange: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    appLockEnabled: Boolean,
    onAppLockChange: (Boolean) -> Unit,
    appVersionLabel: String,
    onBack: () -> Unit,
) {
    var showClearHistory by remember { mutableStateOf(false) }
    var showExportBackup by remember { mutableStateOf(false) }
    var showRestoreBackup by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .fillMaxWidth(),
        ) {
            Spacer(Modifier.height(8.dp))
            SectionLabel("Appearance")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "Automatic"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                val availableVariants = ThemeVariant.entries.filter { it != ThemeVariant.FOUNDER || isFounder }
                availableVariants.forEach { variant ->
                    ThemeVariantSwatch(
                        variant = variant,
                        selected = themeVariant == variant,
                        onClick = { onThemeVariantChange(variant) },
                    )
                }
            }
            Text(
                "Every theme but Corvid only applies in Dark",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Font size")
            Card(
                colors = CardDefaults.cardColors(containerColor = voronNeutralIconContainerColor()),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        "Aa",
                        style = MaterialTheme.typography.headlineSmall,
                        fontSize = MaterialTheme.typography.headlineSmall.fontSize * fontScale,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = fontScale,
                        onValueChange = onFontScaleChange,
                        valueRange = 0.85f..1.3f,
                        steps = 3,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Small", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Large", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            SectionLabel("Security")
            Card(
                colors = CardDefaults.cardColors(containerColor = voronNeutralIconContainerColor()),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SwitchRow(
                    icon = Icons.Filled.Fingerprint,
                    tint = if (appLockEnabled) voronEncryptedColor() else voronNeutralIconTint(),
                    label = "App lock",
                    detail = "Require your fingerprint or device PIN when Voron comes back to the foreground",
                    checked = appLockEnabled,
                    onCheckedChange = onAppLockChange,
                )
            }

            Spacer(Modifier.height(28.dp))
            SectionLabel("Privacy")
            Card(
                colors = CardDefaults.cardColors(containerColor = voronNeutralIconContainerColor()),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    SwitchRow(
                        icon = Icons.AutoMirrored.Filled.AltRoute,
                        tint = if (onionRoutingEnabled) voronEncryptedColor() else voronNeutralIconTint(),
                        label = "Onion routing",
                        detail = if (onionRoutingEnabled) {
                            if (onionCircuitActive) "Active — tunneled through 2 relay hops" else "Enabled — connecting…"
                        } else {
                            "Hide your IP from the relay behind 2 relay hops"
                        },
                        checked = onionRoutingEnabled,
                        onCheckedChange = onOnionRoutingChange,
                    )
                    if (onionRoutingEnabled) {
                        DividerSpace()
                        SwitchRow(
                            icon = Icons.Filled.Wifi,
                            tint = voronNeutralIconTint(),
                            label = "Wi-Fi only",
                            detail = "Skip the extra hops on mobile data",
                            checked = onionWifiOnly,
                            onCheckedChange = onOnionWifiOnlyChange,
                        )
                        DividerSpace()
                        ActionRow(
                            icon = Icons.Filled.Refresh,
                            label = "New circuit",
                            detail = "Rebuild the tunnel with a fresh key",
                            onClick = onRebuildCircuit,
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            SectionLabel("Notifications")
            Card(
                colors = CardDefaults.cardColors(containerColor = voronNeutralIconContainerColor()),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    SwitchRow(
                        icon = Icons.Filled.Notifications,
                        tint = voronNeutralIconTint(),
                        label = "Enable notifications",
                        detail = "Show a banner for messages that arrive in the background",
                        checked = notificationsEnabled,
                        onCheckedChange = onNotificationsChange,
                    )
                    DividerSpace()
                    SwitchRow(
                        icon = Icons.Filled.Sync,
                        tint = if (pushEnabled) voronEncryptedColor() else voronNeutralIconTint(),
                        label = "Wake up when the app is closed",
                        detail = "Uses a UnifiedPush distributor (e.g. ntfy) — no Google account needed. Without this, messages and calls only arrive once you reopen Voron.",
                        checked = pushEnabled,
                        onCheckedChange = onPushEnabledChange,
                    )
                    if (notificationsEnabled) {
                        DividerSpace()
                        SwitchRow(
                            icon = Icons.Filled.VisibilityOff,
                            tint = voronNeutralIconTint(),
                            label = "Hide sender name",
                            detail = "Show \"Voron\" instead of the contact's name",
                            checked = hideNotificationSender,
                            onCheckedChange = onHideNotificationSenderChange,
                        )
                        DividerSpace()
                        SwitchRow(
                            icon = Icons.Filled.VisibilityOff,
                            tint = voronNeutralIconTint(),
                            label = "Hide message text",
                            detail = "Show \"New message\" instead of the content",
                            checked = hideNotificationContent,
                            onCheckedChange = onHideNotificationContentChange,
                        )
                    }
                    DividerSpace()
                    val context = LocalContext.current
                    ActionRow(
                        icon = Icons.Filled.Tune,
                        label = "Sound & vibration",
                        detail = "Managed by Android's system settings",
                        onClick = {
                            val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                            context.startActivity(intent)
                        },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            SectionLabel("Backup")
            ActionCard(
                icon = Icons.Filled.CloudUpload,
                label = "Back up account",
                detail = "Encrypted export protected by a recovery phrase",
                onClick = { showExportBackup = true },
            )
            Spacer(Modifier.height(8.dp))
            ActionCard(
                icon = Icons.Filled.CloudDownload,
                label = "Restore from backup",
                detail = "Replaces this device's identity, contacts, and history",
                onClick = { showRestoreBackup = true },
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Data & storage")
            ActionCard(
                icon = Icons.Filled.DeleteForever,
                label = "Delete all message history",
                detail = "Wipes every conversation on this device",
                destructive = true,
                onClick = { showClearHistory = true },
            )

            Spacer(Modifier.height(28.dp))
            SectionLabel("Coming soon")
            ComingSoonRow(icon = Icons.Filled.Language, label = "Language", detail = "English")

            Spacer(Modifier.height(28.dp))
            SectionLabel("About")
            ActionCard(icon = Icons.Filled.Info, label = "Voron", detail = appVersionLabel, onClick = null)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showClearHistory) {
        ClearHistorySheet(
            onDismiss = { showClearHistory = false },
            onConfirm = {
                onClearHistory()
                showClearHistory = false
            },
        )
    }
    if (showExportBackup) {
        ExportBackupSheet(onDismiss = { showExportBackup = false })
    }
    if (showRestoreBackup) {
        RestoreBackupSheet(onDismiss = { showRestoreBackup = false })
    }
}

@Composable
private fun ThemeVariantSwatch(variant: ThemeVariant, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        val colors = variant.previewColors()
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(variant.previewBackground(), CircleShape)
                .border(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                )
                .padding(7.dp)
                .background(Brush.sweepGradient(colors + colors.first()), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(variant.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun DividerSpace() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.background,
    )
}

@Composable
private fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = voronEncryptedColor(),
                uncheckedThumbColor = voronNeutralIconTint(),
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = voronNeutralIconTint(),
            ),
        )
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = voronNeutralIconTint())
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    destructive: Boolean = false,
    onClick: (() -> Unit)?,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = voronNeutralIconContainerColor()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = if (destructive) MaterialTheme.colorScheme.error else voronNeutralIconTint())
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                )
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ComingSoonRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, detail: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = voronNeutralIconContainerColor()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = voronNeutralIconTint())
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Soon",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
