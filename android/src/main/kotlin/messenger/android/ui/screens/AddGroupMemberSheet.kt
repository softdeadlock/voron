package messenger.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import messenger.android.data.Contact
import messenger.android.ui.Avatar
import messenger.android.ui.theme.voronSheetContainerColor

/** Tap-to-add contact picker for [GroupInfoScreen]'s "Add member" — [contacts] should already be filtered to exclude existing members. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGroupMemberSheet(contacts: List<Contact>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = voronSheetContainerColor()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Add member", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (contacts.isEmpty()) {
                Text("Everyone in your contacts is already in this group.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (contact in contacts) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(contact.deviceKeyHex); onDismiss() }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(contact.nickname, iconId = contact.avatarIconId, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(contact.nickname, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
