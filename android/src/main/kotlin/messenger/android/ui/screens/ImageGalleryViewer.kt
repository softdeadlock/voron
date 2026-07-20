package messenger.android.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen swipe-through viewer for every image in the current conversation, replacing the
 * external-gallery hand-off — [imagePaths] is the conversation's images in on-screen order (see
 * [ChatDetailScreen]'s `conversationImagePaths`), [startPath] is which one was tapped.
 */
@Composable
internal fun ImageGalleryViewer(imagePaths: List<String>, startPath: String, onDismiss: () -> Unit) {
    // A plain composable placed as a sibling of the Scaffold wouldn't reliably overlay full-screen
    // (depends on whether the enclosing NavHost destination happens to wrap content in a Box) —
    // a Dialog gets its own window, guaranteeing full-screen coverage and back-gesture dismissal
    // regardless, the same way ModalBottomSheet (used for every other overlay in this screen)
    // already does internally.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImageGalleryViewerContent(imagePaths, startPath, onDismiss)
    }
}

@Composable
private fun ImageGalleryViewerContent(imagePaths: List<String>, startPath: String, onDismiss: () -> Unit) {
    val startIndex = remember(imagePaths, startPath) { imagePaths.indexOf(startPath).coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = startIndex) { imagePaths.size }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            ZoomableImage(path = imagePaths[page])
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            IconButton(onClick = { saveToGallery(context, imagePaths[pagerState.currentPage]) }) {
                Icon(Icons.Filled.Download, contentDescription = "Save", tint = Color.White)
            }
            IconButton(onClick = { shareImage(context, imagePaths[pagerState.currentPage]) }) {
                Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
            }
        }
        if (imagePaths.size > 1) {
            Text(
                "${pagerState.currentPage + 1} / ${imagePaths.size}",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .statusBarsPadding()
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun ZoomableImage(path: String) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    // Full-resolution-ish (capped well above screen size, not the 720px chat-bubble thumbnail) —
    // this is a dedicated full-screen viewer, so it's worth the extra decode cost the small inline
    // bubble version deliberately avoids.
    val bitmap by produceState<ImageBitmap?>(initialValue = attachmentThumbnailCache.get(path), key1 = path) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { decodeSampled(path, 2048) }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(path) {
                // Hand-rolled instead of detectTransformGestures: that helper unconditionally
                // consumes every pointer move it sees, including a plain one-finger horizontal
                // drag at 1x zoom — which starved the enclosing HorizontalPager of the same drag
                // and made swiping between images silently do nothing. Only consuming once there's
                // a real pinch (2+ pointers) or the image is already zoomed in lets a one-finger
                // drag at 1x fall through untouched, so the pager's own drag detector still sees it.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (event.changes.size > 1 || scale > 1f) {
                            val newScale = (scale * zoomChange).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale <= 1f) Offset.Zero else offset + panChange
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(path) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

private fun saveToGallery(context: Context, path: String) {
    val source = File(path)
    if (!source.exists()) return
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, source.name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Voron")
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        Toast.makeText(context, "Couldn't save image", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
        Toast.makeText(context, "Saved to Pictures/Voron", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "Couldn't save image", Toast.LENGTH_SHORT).show()
    }
}

private fun shareImage(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
