package messenger.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import messenger.android.data.AvatarIconId
import messenger.android.data.CallUiState
import messenger.android.ui.Avatar

/** Full-screen overlay for the active call, shown by [messenger.android.MainActivity] whenever [messenger.android.data.AppState.activeCall] is non-null. */
@Composable
fun CallScreen(
    call: CallUiState,
    peerNickname: String,
    peerAvatarIconId: AvatarIconId?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Avatar(peerNickname, size = 120.dp, iconId = peerAvatarIconId)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(peerNickname, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                "BETA",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (call) {
                is CallUiState.IncomingRinging -> "Incoming call…"
                is CallUiState.OutgoingRinging -> "Calling…"
                is CallUiState.Connected -> elapsedTimeText(call.startedAtMillis)
                is CallUiState.Unavailable -> "Unavailable"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(64.dp))

        when (call) {
            is CallUiState.IncomingRinging -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallActionButton(icon = Icons.Filled.CallEnd, label = "Decline", containerColor = MaterialTheme.colorScheme.error, onClick = onDecline)
                CallActionButton(icon = Icons.Filled.Call, label = "Accept", containerColor = voronCallAcceptColor(), onClick = onAnswer)
            }
            is CallUiState.OutgoingRinging -> CallActionButton(
                icon = Icons.Filled.CallEnd,
                label = "Cancel",
                containerColor = MaterialTheme.colorScheme.error,
                onClick = onHangUp,
            )
            is CallUiState.Connected -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallActionButton(
                    icon = if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (call.muted) "Unmute" else "Mute",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onToggleMute,
                )
                CallActionButton(icon = Icons.Filled.CallEnd, label = "End", containerColor = MaterialTheme.colorScheme.error, onClick = onHangUp)
            }
            is CallUiState.Unavailable -> Unit
        }
    }
}

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = containerColor,
            contentColor = contentColor,
            shape = CircleShape,
            modifier = Modifier.size(64.dp),
        ) {
            Icon(icon, contentDescription = label)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun elapsedTimeText(startedAtMillis: Long): String {
    var elapsedSeconds by remember(startedAtMillis) { mutableStateOf((System.currentTimeMillis() - startedAtMillis) / 1000) }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            delay(1_000)
            elapsedSeconds = (System.currentTimeMillis() - startedAtMillis) / 1000
        }
    }
    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** A distinct green, not otherwise in the palette, so accept/decline read as unambiguously different actions. */
@Composable
private fun voronCallAcceptColor(): Color = Color(0xFF2E7D4F)
