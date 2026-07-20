package messenger.android.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import messenger.android.data.VoronLog
import messenger.android.ui.theme.voronNeutralIconContainerColor
import messenger.android.ui.theme.voronNeutralIconTint
import messenger.android.ui.theme.voronSheetContainerColor
import messenger.common.group.GroupInvite

/**
 * Joins a group by invite link — paste it (from wherever it was shared: chat, a note, another
 * app) or scan the sender's QR (see [GroupInfoScreen]'s "Copy invite link", which is the same
 * string this parses). Submitting sends a [messenger.common.client.MessengerClient.sendGroupJoinRequest]
 * to the link's signer; the actual membership only takes effect once *they* accept it (see
 * [messenger.android.data.GroupManager]'s join-request handling) — this sheet only reports whether
 * the request itself was sent, not whether the join was accepted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinGroupSheet(onDismiss: () -> Unit, onJoin: (link: String, onResult: (String?) -> Unit) -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var linkText by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scanContract = remember { ScanContract() }
    val qrScanner = rememberLauncherForActivityResult(scanContract) { result ->
        val scanned = result.contents ?: return@rememberLauncherForActivityResult
        linkText = scanned
    }
    val launchScanner = {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan a Voron group invite QR")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .setCaptureActivity(messenger.android.VoronScanActivity::class.java)
        runCatching { qrScanner.launch(options) }
            .onFailure {
                VoronLog.w("VoronPicker", "QR scanner launch failed", it)
                Toast.makeText(context, "Couldn't open the camera", Toast.LENGTH_SHORT).show()
            }
        Unit
    }

    fun submit() {
        val trimmed = linkText.trim()
        if (GroupInvite.parse(trimmed) == null) {
            errorMessage = "That doesn't look like a Voron group invite link"
            return
        }
        errorMessage = null
        submitting = true
        onJoin(trimmed) { error ->
            submitting = false
            if (error == null) {
                onDismiss()
            } else {
                errorMessage = error
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = voronSheetContainerColor()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(56.dp).background(voronNeutralIconContainerColor(), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.GroupAdd, contentDescription = null, tint = voronNeutralIconTint())
            }
            Spacer(Modifier.height(16.dp))
            Text("Join a group", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste an invite link, or scan its QR code. You'll join once whoever shared it approves it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = launchScanner,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Scan invite QR", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = linkText,
                onValueChange = { linkText = it; errorMessage = null },
                label = { Text("Invite link") },
                isError = errorMessage != null,
                supportingText = errorMessage?.let { { Text(it) } },
                minLines = 2,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = ::submit,
                enabled = linkText.isNotBlank() && !submitting,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Join", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
