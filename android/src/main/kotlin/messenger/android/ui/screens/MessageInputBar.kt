package messenger.android.ui.screens

import android.graphics.BitmapFactory
import java.io.File
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.InsertEmoticon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import messenger.android.data.ChatMessage
import messenger.android.data.StickerId
import messenger.android.data.VoiceRecorder
import messenger.android.ui.theme.voronAccentGradient
import messenger.android.ui.theme.VoronViolet
import messenger.android.ui.theme.voronSheetContainerColor
import messenger.common.client.ApplicationMessage

/** The quoted-message strip pinned above the input bar while the user is composing a reply. */
@Composable
internal fun ReplyPreviewBar(message: ChatMessage, peerNickname: String, onCancel: () -> Unit) {
    // Replying to a file-only or sticker message would otherwise show an empty preview line.
    val preview = if (message.stickerId != null) "🐦 Sticker" else message.text.ifBlank { "📎 " + (message.attachmentName ?: "File") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            QuoteBlock(label = if (message.fromMe) "You" else peerNickname, preview = preview, onLight = false)
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel reply", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The quoted-original-text strip pinned above the input bar while the user is editing one of their own already-sent messages. */
@Composable
internal fun EditPreviewBar(originalText: String, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            QuoteBlock(label = "Editing message", preview = originalText, onLight = false)
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The compose-bar strip covering a detected link's whole lifecycle: an unobtrusive suggestion
 * ("Add preview?") the moment a URL shows up in [messenger.android.ui.screens.ChatDetailScreen]'s
 * `messageInput`, a brief loading state once tapped, then the fetched title/thumbnail with a way
 * to remove it before sending. Nothing here ever fetches on its own — [onFetch] only fires from an
 * explicit tap, per [ApplicationMessage.LinkPreviewRef]'s reasoning on why an automatic fetch would
 * be a real address/timing leak, just to the sender's own network this time rather than the
 * recipient's.
 */
@Composable
internal fun LinkPreviewComposeBanner(
    url: String,
    preview: ApplicationMessage.LinkPreviewRef?,
    isLoading: Boolean,
    isDismissed: Boolean,
    onFetch: () -> Unit,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
) {
    if (preview != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumbnail = remember(preview.imageBytes) {
                preview.imageBytes?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            }
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp).padding(8.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(preview.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Preview will be sent — recipient reads it without visiting the link",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove preview", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (isDismissed) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Fetching preview…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Link detected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onFetch) { Text("Add preview") }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The attach choice as a bottom sheet in the app's regular sheet style — the previous anchored
 * [DropdownMenu][androidx.compose.material3.DropdownMenu] popped jarringly next to the keyboard.
 * The sheet animates fully closed *before* the system picker launches, so the picker's own
 * transition doesn't overlap and fight it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun dismissThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = voronSheetContainerColor(),
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                "Add attachment",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            AttachOptionRow(
                icon = Icons.Filled.Image,
                label = "Photo or video",
                description = "From the gallery",
                onClick = { dismissThen(onPickImage) },
            )
            AttachOptionRow(
                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                label = "File",
                description = "Any type, sent encrypted device-to-device",
                onClick = { dismissThen(onPickDocument) },
            )
        }
    }
}

@Composable
private fun AttachOptionRow(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The app's signature violet→lavender gradient chip (same treatment as avatars/QR/FAB)
        // instead of a flat neutral circle — makes the sheet feel like part of the same app.
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(4.dp, CircleShape, ambientColor = VoronViolet.copy(alpha = 0.3f), spotColor = VoronViolet.copy(alpha = 0.3f))
                .background(Brush.linearGradient(voronAccentGradient()), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImage: (() -> Unit)? = null,
    onPickDocument: () -> Unit = {},
    onVoiceMessageRecorded: ((File, Long) -> Unit)? = null,
    onSendSticker: ((StickerId) -> Unit)? = null,
    isFounder: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    var showAttachSheet by remember { mutableStateOf(false) }
    var showStickerSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartMillis by remember { mutableLongStateOf(0L) }
    // Released if this composable leaves composition mid-recording (e.g. navigating away) —
    // otherwise the mic stays held by a MediaRecorder no UI can reach anymore.
    DisposableEffect(Unit) { onDispose { if (isRecording) recorder.cancel() } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onPickImage != null) {
            IconButton(onClick = { showAttachSheet = true }, enabled = !isRecording) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = "Attach",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onSendSticker != null && !isRecording) {
            IconButton(onClick = { showStickerSheet = true }) {
                Icon(
                    Icons.Filled.InsertEmoticon,
                    contentDescription = "Stickers",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isRecording) {
            RecordingIndicator(startMillis = recordingStartMillis, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                recorder.cancel()
                isRecording = false
            }) {
                Icon(Icons.Filled.Delete, contentDescription = "Cancel recording", tint = MaterialTheme.colorScheme.error)
            }
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                shape = RoundedCornerShape(24.dp),
                maxLines = 5,
            )
        }
        Spacer(Modifier.width(8.dp))
        val canSend = value.isNotBlank()
        IconButton(
            onClick = {
                when {
                    isRecording -> {
                        val file = recorder.stop()
                        val durationMillis = System.currentTimeMillis() - recordingStartMillis
                        isRecording = false
                        if (file != null && onVoiceMessageRecorded != null) {
                            onVoiceMessageRecorded(file, durationMillis)
                        }
                    }
                    canSend -> onSend()
                    onVoiceMessageRecorded != null -> {
                        if (recorder.start()) {
                            isRecording = true
                            recordingStartMillis = System.currentTimeMillis()
                        }
                    }
                }
            },
            enabled = canSend || isRecording || onVoiceMessageRecorded != null,
            modifier = Modifier
                .size(48.dp)
                .background(
                    if (canSend || isRecording) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    CircleShape,
                ),
        ) {
            when {
                isRecording -> Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice message", tint = Color.White)
                canSend -> Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
                else -> Icon(Icons.Filled.Mic, contentDescription = "Record voice message", tint = Color.White)
            }
        }
    }

    if (showAttachSheet && onPickImage != null) {
        AttachSheet(
            onDismiss = { showAttachSheet = false },
            onPickImage = onPickImage,
            onPickDocument = onPickDocument,
        )
    }

    if (showStickerSheet && onSendSticker != null) {
        StickerPickerSheet(
            isFounder = isFounder,
            onDismiss = { showStickerSheet = false },
            onPick = onSendSticker,
        )
    }
}

/** A pulsing red dot + live mm:ss elapsed, replacing the text field while [MessageInputBar] is recording a voice message. */
@Composable
private fun RecordingIndicator(startMillis: Long, modifier: Modifier = Modifier) {
    var elapsedMillis by remember(startMillis) { mutableLongStateOf(0L) }
    LaunchedEffect(startMillis) {
        while (true) {
            elapsedMillis = System.currentTimeMillis() - startMillis
            delay(200)
        }
    }
    val transition = rememberInfiniteTransition(label = "recording-dot")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "recording-dot-alpha",
    )
    Row(modifier = modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .graphicsLayer { alpha = dotAlpha }
                .background(MaterialTheme.colorScheme.error, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        val totalSeconds = elapsedMillis / 1000
        Text(
            "%d:%02d".format(totalSeconds / 60, totalSeconds % 60),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
