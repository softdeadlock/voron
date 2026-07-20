package messenger.android.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import messenger.android.ui.parseContactQr
import messenger.android.ui.theme.voronNeutralIconContainerColor
import messenger.android.ui.theme.voronNeutralIconTint
import messenger.android.ui.theme.voronSheetContainerColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactSheet(onDismiss: () -> Unit, onAdd: (deviceKeyHex: String) -> Unit) {
    var keyHex by remember { mutableStateOf("") }
    val trimmedKey = keyHex.trim()
    val keyValid = trimmedKey.length == 64 && trimmedKey.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    // The embedded (non-ML-Kit) scanner: handles its own CAMERA runtime permission and works
    // on de-Googled ROMs. A successfully scanned Voron QR adds the contact immediately — no
    // extra confirm tap on a key the user can't meaningfully review anyway.
    // `remember`ed rather than constructed inline: rememberLauncherForActivityResult re-registers
    // with the OS whenever the contract's object identity changes, and this composable recomposes
    // on every `keyHex` keystroke — see ChatDetailScreen's photoPicker/documentPicker for the full
    // explanation of the request-code exhaustion this causes if the contract isn't stable.
    val scanContract = remember { ScanContract() }
    val qrScanner = rememberLauncherForActivityResult(scanContract) { result ->
        val scanned = result.contents ?: return@rememberLauncherForActivityResult
        val parsedKey = parseContactQr(scanned)
        if (parsedKey != null) {
            onAdd(parsedKey)
        } else {
            Toast.makeText(context, "Not a Voron contact QR", Toast.LENGTH_SHORT).show()
        }
    }
    val launchScanner = {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan your friend's Voron QR")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            // zxing's own CaptureActivity is manifest-locked to landscape (sideways camera
            // preview for anyone holding the phone normally) and has no back-button chrome at
            // all — VoronScanActivity is the same scanner with just those two fixed.
            .setCaptureActivity(messenger.android.VoronScanActivity::class.java)
        runCatching { qrScanner.launch(options) }
            .onFailure {
                VoronLog.w("VoronPicker", "QR scanner launch failed", it)
                Toast.makeText(context, "Couldn't open the camera", Toast.LENGTH_SHORT).show()
            }
        Unit
    }

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
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(voronNeutralIconContainerColor(), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = voronNeutralIconTint())
            }
            Spacer(Modifier.height(16.dp))
            Text("Add contact", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Scan their QR from Profile, or paste their device key. Their name appears automatically — encrypted — once you exchange a message.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = launchScanner,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Scan QR code", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant)
                Text(
                    "  or  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = keyHex,
                onValueChange = { keyHex = it },
                label = { Text("Device key (64 hex chars)") },
                isError = keyHex.isNotEmpty() && !keyValid,
                maxLines = 3,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { onAdd(trimmedKey) },
                enabled = keyValid,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Add by key", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
