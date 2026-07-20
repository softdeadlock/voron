package messenger.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import messenger.common.group.AdminPermission
import messenger.common.group.GroupRole
import messenger.common.group.GroupState

/**
 * Members, roles, and settings for one group. Owner-only actions (promote/demote/transfer/
 * announcement-mode/invite-links toggle) and admin-gated ones (add/remove) are hidden entirely
 * rather than shown-disabled — the authorization is enforced for real in
 * [messenger.common.group.GroupControlLog] regardless, this is just not offering an action that
 * would silently be dropped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    group: GroupState?,
    ownKeyHex: String,
    nicknameFor: (String) -> String,
    onBack: () -> Unit,
    onRemoveMember: (String) -> Unit,
    onPromoteAdmin: (String, Int) -> Unit,
    onDemoteAdmin: (String) -> Unit,
    onTransferOwnership: (String) -> Unit,
    onSetAnnouncementMode: (Boolean) -> Unit,
    onSetInviteLinksEnabled: (Boolean) -> Unit,
    onCreateInviteLink: () -> String?,
    onCopyToClipboard: (String) -> Unit,
    onLeaveGroup: () -> Unit,
    onAddMember: () -> Unit,
) {
    var confirmLeave by remember { mutableStateOf(false) }
    var memberOptionsFor by remember { mutableStateOf<String?>(null) }
    var inviteLinkToShow by remember { mutableStateOf<String?>(null) }

    val self = group?.members?.get(ownKeyHex)
    val isOwner = self?.role == GroupRole.OWNER
    val canRemove = isOwner || self?.hasPermission(AdminPermission.REMOVE_MEMBERS) == true
    val canAdd = isOwner || self?.hasPermission(AdminPermission.ADD_MEMBERS) == true || group?.inviteLinksEnabled == true
    // Owner leaving/last-admin leaving needs a hand-off first — see the plan's orphaned-group rule.
    val isLastAdminOrOwner = self != null && (self.role == GroupRole.OWNER || self.role == GroupRole.ADMIN) &&
        group?.members?.values?.count { it.role == GroupRole.OWNER || it.role == GroupRole.ADMIN } == 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.name.orEmpty().ifBlank { "Group info" }, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (group == null) return@Scaffold
        LazyColumn(modifier = Modifier.padding(padding).fillMaxWidth().padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                if (isOwner) {
                    SettingsRow(
                        icon = Icons.Filled.Campaign,
                        label = "Announcement mode",
                        detail = "Only admins can post",
                        checked = group.announcementMode,
                        onCheckedChange = onSetAnnouncementMode,
                    )
                    SettingsRow(
                        icon = Icons.Filled.Link,
                        label = "Invite links",
                        detail = "Anyone with the link (or any member, once on) can add people",
                        checked = group.inviteLinksEnabled,
                        onCheckedChange = onSetInviteLinksEnabled,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (canAdd) {
                    ActionRow(icon = Icons.Filled.PersonAdd, label = "Add member", onClick = onAddMember)
                }
                if (group.inviteLinksEnabled) {
                    ActionRow(
                        icon = Icons.Filled.QrCode,
                        label = "Show invite QR",
                        onClick = { inviteLinkToShow = onCreateInviteLink() },
                    )
                    ActionRow(
                        icon = Icons.Filled.ContentCopy,
                        label = "Copy invite link",
                        onClick = { onCreateInviteLink()?.let(onCopyToClipboard) },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "${group.members.size} MEMBERS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(group.members.values.toList(), key = { it.dhKey.toList() }) { member ->
                val keyHex = member.dhKey.let { it.joinToString("") { b -> "%02x".format(b) } }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = keyHex != ownKeyHex && (canRemove || isOwner)) { memberOptionsFor = keyHex }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (keyHex == ownKeyHex) "You" else nicknameFor(keyHex),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (member.role != GroupRole.MEMBER) {
                        Text(
                            if (member.role == GroupRole.OWNER) "Owner" else "Admin",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (memberOptionsFor == keyHex) {
                    MemberOptionsDialog(
                        isOwner = isOwner,
                        canRemove = canRemove,
                        memberRole = member.role,
                        onDismiss = { memberOptionsFor = null },
                        onRemove = { onRemoveMember(keyHex); memberOptionsFor = null },
                        onPromote = { onPromoteAdmin(keyHex, AdminPermission.ADD_MEMBERS or AdminPermission.REMOVE_MEMBERS or AdminPermission.CHANGE_INFO); memberOptionsFor = null },
                        onDemote = { onDemoteAdmin(keyHex); memberOptionsFor = null },
                        onTransfer = { onTransferOwnership(keyHex); memberOptionsFor = null },
                    )
                }
            }
            item {
                Spacer(Modifier.height(24.dp))
                ActionRow(
                    icon = Icons.Filled.ExitToApp,
                    label = "Leave group",
                    destructive = true,
                    onClick = { confirmLeave = true },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (isLastAdminOrOwner) "You're the only admin" else "Leave group?") },
            text = {
                Text(
                    if (isLastAdminOrOwner) {
                        "Promote someone else to admin first, or the group will be left with nobody able to manage it."
                    } else {
                        "You'll stop receiving messages from this group."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmLeave = false; if (!isLastAdminOrOwner) onLeaveGroup() },
                    enabled = !isLastAdminOrOwner,
                ) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }

    inviteLinkToShow?.let { link ->
        AlertDialog(
            onDismissRequest = { inviteLinkToShow = null },
            title = { Text("Scan to join") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Anyone who scans this becomes a member once you approve it — it's not automatic.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    messenger.android.ui.QrCodeCard(content = link, size = 200.dp)
                }
            },
            confirmButton = { TextButton(onClick = { onCopyToClipboard(link); inviteLinkToShow = null } ) { Text("Copy link") } },
            dismissButton = { TextButton(onClick = { inviteLinkToShow = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun MemberOptionsDialog(
    isOwner: Boolean,
    canRemove: Boolean,
    memberRole: GroupRole,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onTransfer: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Member options") },
        text = {
            Column {
                if (isOwner && memberRole == GroupRole.MEMBER) {
                    TextButton(onClick = onPromote) { Text("Make admin") }
                }
                if (isOwner && memberRole == GroupRole.ADMIN) {
                    TextButton(onClick = onDemote) { Text("Remove admin") }
                }
                if (isOwner && memberRole != GroupRole.OWNER) {
                    TextButton(onClick = onTransfer) { Text("Make owner") }
                }
                if (canRemove && memberRole != GroupRole.OWNER) {
                    TextButton(onClick = onRemove) { Text("Remove from group") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}
