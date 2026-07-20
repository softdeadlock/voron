package messenger.android.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.text.format.Formatter
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import messenger.android.data.ChatMessage
import messenger.android.data.FileTransferStatus
import messenger.android.ui.theme.voronEncryptedColor

/**
 * Process-wide cache of decoded attachment thumbnails, keyed by file path. Without it, every image
 * bubble that scrolls out of and back into the LazyColumn's viewport re-decodes its full JPEG from
 * disk from scratch — LazyColumn disposes a recycled item's composition (and its `remember` state)
 * when it leaves the viewport, so per-item `remember` alone can't survive a scroll-away-and-back.
 * Sized as a fraction of heap so a long scroll through many photos can't itself pressure memory.
 */
internal val attachmentThumbnailCache = object : LruCache<String, ImageBitmap>(
    (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtMost(64 * 1024 * 1024),
) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

/** True for exactly the messages [AttachmentContent] renders as an inline image thumbnail — shared with the chat screen so it can build the same ordered image list for the swipe gallery. */
internal fun isInlineImageAttachment(message: ChatMessage): Boolean =
    message.attachmentMime?.startsWith("image/") == true &&
        message.transferStatus == FileTransferStatus.COMPLETE &&
        message.attachmentPath != null

/** Renders an attachment inside a message bubble: an inline thumbnail for a received image, a play/pause row for a voice message, otherwise a file card with name/size and (while transferring) a progress bar. */
@Composable
internal fun AttachmentContent(message: ChatMessage, onImageClick: (String) -> Unit = {}) {
    val context = LocalContext.current
    val onLight = message.fromMe
    val isImage = message.attachmentMime?.startsWith("image/") == true
    val isVoice = message.attachmentMime?.startsWith("audio/") == true
    val done = message.transferStatus == FileTransferStatus.COMPLETE
    val failed = message.transferStatus == FileTransferStatus.FAILED

    if (isVoice && done && message.attachmentPath != null) {
        VoiceMessageContent(path = message.attachmentPath, onLight = onLight)
        return
    }

    if (isImage && done && message.attachmentPath != null) {
        val path = message.attachmentPath
        // Decoding runs off the main thread and only on a cache miss — a LazyColumn disposes a
        // recycled item's composition (and any `remember` state) once it scrolls out of the
        // viewport, so without the process-wide cache every image bubble that scrolls back into
        // view during a fast scroll would re-decode its full JPEG from disk synchronously, which is
        // exactly what was stuttering scrolling through image-heavy chats.
        val bitmap by produceState<ImageBitmap?>(initialValue = attachmentThumbnailCache.get(path), key1 = path) {
            if (value == null) {
                value = withContext(Dispatchers.IO) { decodeSampled(path, 720) }
                    ?.also { attachmentThumbnailCache.put(path, it) }
            }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = message.attachmentName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onImageClick(path) },
            )
            return
        }
    }

    val tint = if (onLight) Color.White else MaterialTheme.colorScheme.onSurface
    val subTint = if (onLight) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val sizeLabel = message.attachmentSize?.let { Formatter.formatShortFileSize(context, it) }.orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = 240.dp)) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (failed) MaterialTheme.colorScheme.error else tint,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = message.attachmentName ?: "File",
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (message.transferStatus) {
                    FileTransferStatus.COMPLETE -> sizeLabel
                    FileTransferStatus.FAILED -> "Failed"
                    else -> "$sizeLabel · ${(message.transferProgress * 100).roundToInt()}%"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else subTint,
            )
            if (message.transferStatus == FileTransferStatus.TRANSFERRING || message.transferStatus == FileTransferStatus.OFFERED) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { message.transferProgress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = if (onLight) Color.White else voronEncryptedColor(),
                )
            }
        }
    }
}

internal fun decodeSampled(path: String, maxDimenPx: Int): ImageBitmap? {
    if (!File(path).exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimenPx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        applyExifRotation(path, bitmap).asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/**
 * `BitmapFactory` decodes raw pixel data only — it ignores the EXIF orientation tag most camera
 * photos carry (a portrait photo is very often stored as landscape pixels plus a "rotate 90"
 * flag), which is exactly why photos rendered sideways in both the inline bubble thumbnail and the
 * fullscreen gallery viewer (both funnel through this function). Rotates/flips the decoded bitmap
 * to match what every other viewer already shows.
 */
private fun applyExifRotation(path: String, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        else -> return bitmap
    }
    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }.getOrDefault(bitmap)
}

/** Owns at most one live [MediaPlayer] across the whole chat screen, so tapping a second voice bubble stops whichever one was already playing instead of overlapping audio. */
private object VoicePlaybackController {
    private var player: MediaPlayer? = null
    var playingPath by mutableStateOf<String?>(null)
        private set

    fun toggle(path: String) {
        if (playingPath == path) {
            stop()
            return
        }
        stop()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(path)
            mp.setOnCompletionListener { stop() }
            mp.prepare()
            mp.start()
            player = mp
            playingPath = path
        } catch (e: Exception) {
            mp.release()
        }
    }

    fun stop() {
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        playingPath = null
    }

    fun currentPositionMillis(): Int = player?.let { runCatching { it.currentPosition }.getOrDefault(0) } ?: 0
}

/** Play/pause row for a voice message — [VoicePlaybackController] keeps at most one playing across the whole chat. */
@Composable
private fun VoiceMessageContent(path: String, onLight: Boolean) {
    val isPlaying = VoicePlaybackController.playingPath == path
    val durationMillis = remember(path) {
        runCatching {
            MediaMetadataRetriever().use {
                it.setDataSource(path)
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull() ?: 0L
    }
    var positionMillis by remember(path) { mutableStateOf(0) }
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMillis = VoicePlaybackController.currentPositionMillis()
            delay(200)
        }
    }
    // Stop playback if this bubble scrolls out of composition mid-play, rather than leaving audio
    // running with no visible control left to stop it.
    DisposableEffect(path) { onDispose { if (VoicePlaybackController.playingPath == path) VoicePlaybackController.stop() } }

    val tint = if (onLight) Color.White else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 160.dp, max = 240.dp)) {
        IconButton(onClick = { VoicePlaybackController.toggle(path) }) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play voice message",
                tint = tint,
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.Mic, contentDescription = null, tint = tint.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        val shownMillis = if (isPlaying) positionMillis.toLong() else durationMillis
        Text(formatDurationMillis(shownMillis), style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

private fun formatDurationMillis(millis: Long): String {
    val totalSeconds = millis / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

internal fun openAttachment(context: Context, path: String, mime: String?) {
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}
