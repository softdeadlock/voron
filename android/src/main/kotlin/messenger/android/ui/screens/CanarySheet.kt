package messenger.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import messenger.android.data.CanaryInfo
import messenger.android.ui.theme.voronNeutralIconContainerColor
import messenger.android.ui.theme.voronSheetContainerColor

/**
 * Shows the relay's warrant canary (see `Routes.kt`'s `/v1/canary`) — deliberately not framed as a
 * cryptographic guarantee, since it isn't one: the trust signal is that the operator keeps renewing
 * it at all, not a signature this app could verify. [info] null while [loading], or if the fetch
 * failed (relay unreachable, or it doesn't publish one at all).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CanarySheet(info: CanaryInfo?, loading: Boolean, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(16.dp))
            Text("Warrant canary", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "This isn't a cryptographic proof — it's a statement the relay operator renews by hand. " +
                    "The signal isn't the text itself; it's whether it keeps being renewed at all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            when {
                loading -> CircularProgressIndicator()
                info == null -> Text(
                    "Couldn't reach the relay to fetch this.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(voronNeutralIconContainerColor(), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                    ) {
                        Text(info.statement, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "As of " + SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(info.asOfMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
        }
    }
}
