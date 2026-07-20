package messenger.android.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import messenger.android.data.ChatMessage
import messenger.android.data.DeliveryStatus
import messenger.android.data.FileTransferStatus
import messenger.android.ui.theme.VoronAvatarGradient
import messenger.android.ui.theme.VoronViolet
import messenger.android.ui.theme.voronDeliveredColor
import messenger.android.ui.theme.voronEncryptedColor
import messenger.android.ui.theme.voronPinColor

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** How far a message bubble can be dragged to reveal the reply icon — capped, not a free drag, so it can never approach screen width. */
private val SWIPE_REPLY_MAX_DRAG = 64.dp
private val SWIPE_REPLY_TRIGGER_DRAG = 44.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    message: ChatMessage,
    peerNickname: String,
    onLongClick: () -> Unit,
    onRetry: () -> Unit,
    onSwipeReply: () -> Unit,
    onQuoteClick: (String) -> Unit,
    onOpenLinkPreview: (ChatMessage) -> Unit = {},
    onImageClick: (String) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val canRetry = message.fromMe && message.deliveryStatus == DeliveryStatus.FAILED
    val canOpenAttachment = message.transferStatus == FileTransferStatus.COMPLETE && message.attachmentPath != null

    val density = LocalDensity.current
    val maxDragPx = remember(density) { with(density) { SWIPE_REPLY_MAX_DRAG.toPx() } }
    val triggerPx = remember(density) { with(density) { SWIPE_REPLY_TRIGGER_DRAG.toPx() } }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var hasTriggeredReply by remember { mutableStateOf(false) }
    val draggableState = rememberDraggableState { delta ->
        offsetX = (offsetX + delta).coerceIn(-maxDragPx, maxDragPx)
        if (!hasTriggeredReply && abs(offsetX) >= triggerPx) {
            hasTriggeredReply = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onSwipeReply()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    animate(
                        initialValue = offsetX,
                        targetValue = 0f,
                        // Settle time scales roughly as 1/sqrt(stiffness), so ~0.44x the stiffness
                        // of StiffnessLow (200f) gets to about 1.5x its duration.
                        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 90f),
                    ) { value, _ -> offsetX = value }
                    hasTriggeredReply = false
                },
            ),
    ) {
        if (offsetX != 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = 20.dp),
                contentAlignment = if (offsetX > 0) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = (abs(offsetX) / maxDragPx).coerceIn(0f, 1f)),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start,
        ) {
            // Rounder, pill-like bubbles per the reference design; the slightly flatter corner
            // on the sender's side keeps the direction cue.
            // PERFORMANCE: both only depend on message.fromMe, which never changes for a given
            // message — remembering them avoids reallocating a Shape/Brush on every incidental
            // recomposition of this bubble (a delivery-status change, a reaction being added...),
            // which matters once a fast scroll is composing/recomposing many bubbles in a burst.
            val bubbleShape = remember(message.fromMe) {
                RoundedCornerShape(
                    topStart = 22.dp,
                    topEnd = 22.dp,
                    bottomStart = if (message.fromMe) 22.dp else 8.dp,
                    bottomEnd = if (message.fromMe) 8.dp else 22.dp,
                )
            }
            val outgoingBrush = remember { Brush.linearGradient(VoronAvatarGradient) }
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    // A soft violet-tinted shadow under sent bubbles and a faint neutral one under
                    // received bubbles gives the stack real depth instead of flat cutout shapes.
                    .shadow(
                        elevation = if (message.fromMe) 6.dp else 2.dp,
                        shape = bubbleShape,
                        ambientColor = if (message.fromMe) VoronViolet.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.12f),
                        spotColor = if (message.fromMe) VoronViolet.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.12f),
                    )
                    .combinedClickable(
                        onClick = {
                            when {
                                canRetry -> onRetry()
                                canOpenAttachment -> openAttachment(context, message.attachmentPath!!, message.attachmentMime)
                                message.replyToMessageId != null -> onQuoteClick(message.replyToMessageId)
                            }
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        },
                    )
                    .then(
                        // Sent bubbles carry the app's signature violet→lavender gradient (same
                        // one used on avatars/QR/FAB) instead of a flat fill, matching the
                        // reference mockup's glossier bubble look.
                        if (message.fromMe) {
                            Modifier.background(outgoingBrush, bubbleShape)
                        } else {
                            Modifier.background(MaterialTheme.colorScheme.surfaceVariant, bubbleShape)
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column {
                    if (message.replyToMessageId != null) {
                        QuoteBlock(
                            label = if (message.replyToFromMe) "You" else peerNickname,
                            preview = message.replyToPreview.orEmpty(),
                            onLight = message.fromMe,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (message.transferStatus != null) {
                        AttachmentContent(message, onImageClick = onImageClick)
                        if (message.text.isNotBlank()) Spacer(Modifier.height(4.dp))
                    }
                    if (message.text.isNotBlank()) {
                        Text(
                            text = message.text,
                            color = if (message.fromMe) Color.White else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (message.linkPreviewUrl != null) {
                        if (message.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                        LinkPreviewCard(message, onLight = message.fromMe, onClick = { onOpenLinkPreview(message) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                if (message.pinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = "Pinned",
                        tint = voronPinColor(),
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = timeFormat.format(Date(message.timestampMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message.edited) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "edited",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                }
                if (message.fromMe) {
                    Spacer(Modifier.width(4.dp))
                    // Sent -> Delivered -> Seen morphs with a quick fade+scale instead of an
                    // instant icon swap, so the status change reads as a deliberate transition.
                    AnimatedContent(
                        targetState = message.deliveryStatus,
                        transitionSpec = {
                            (fadeIn(tween(150)) + scaleIn(initialScale = 0.6f, animationSpec = tween(150)))
                                .togetherWith(fadeOut(tween(100)) + scaleOut(targetScale = 0.6f, animationSpec = tween(100)))
                        },
                        label = "delivery-status",
                    ) { status ->
                        if (status == DeliveryStatus.RETRYING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val (icon, tint) = when (status) {
                                DeliveryStatus.SENT -> Icons.Filled.Done to MaterialTheme.colorScheme.onSurfaceVariant
                                DeliveryStatus.DELIVERED -> Icons.Filled.DoneAll to voronDeliveredColor()
                                DeliveryStatus.SEEN -> Icons.Filled.DoneAll to voronEncryptedColor()
                                DeliveryStatus.FAILED -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
                                DeliveryStatus.RETRYING -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
                            }
                            Icon(
                                icon,
                                contentDescription = status.name,
                                tint = tint,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    if (message.deliveryStatus == DeliveryStatus.FAILED || message.deliveryStatus == DeliveryStatus.RETRYING) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (message.deliveryStatus == DeliveryStatus.RETRYING) "Retrying…" else "Tap to retry",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (message.reactionMine != null || message.reactionTheirs != null) {
                ReactionChip(message.reactionMine, message.reactionTheirs)
            }
        }
    }
}

/**
 * A link-preview card inside a bubble — thumbnail (if any) + title + bare domain, tapping opens the
 * in-app reader ([LinkPreviewReaderSheet]) rather than a browser, since the whole point of
 * archiving the page text is reading it without ever visiting [ChatMessage.linkPreviewUrl].
 */
@Composable
private fun LinkPreviewCard(message: ChatMessage, onLight: Boolean, onClick: () -> Unit) {
    val path = message.linkPreviewImagePath
    val thumbnail by if (path != null) {
        produceState<ImageBitmap?>(initialValue = attachmentThumbnailCache.get(path), key1 = path) {
            if (value == null) {
                value = withContext(Dispatchers.IO) { decodeSampled(path, 480) }?.also { attachmentThumbnailCache.put(path, it) }
            }
        }
    } else {
        produceState<ImageBitmap?>(initialValue = null) {}
    }
    val domain = remember(message.linkPreviewUrl) {
        message.linkPreviewUrl?.substringAfter("://")?.substringBefore("/")?.removePrefix("www.").orEmpty()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (onLight) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(
                    if (onLight) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Link, contentDescription = null, tint = if (onLight) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                message.linkPreviewTitle.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (onLight) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                domain,
                style = MaterialTheme.typography.labelSmall,
                color = if (onLight) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Small pill under a bubble showing up to both parties' reactions on it — [mine]/[theirs] are independent of who authored the message itself. */
@Composable
private fun ReactionChip(mine: String?, theirs: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 4.dp, end = 4.dp, top = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        listOfNotNull(mine, theirs).forEachIndexed { index, emoji ->
            if (index > 0) Spacer(Modifier.width(2.dp))
            Text(emoji, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A received-style bubble with three dots pulsing out of phase, shown at the bottom of the conversation while the peer is typing. */
@Composable
internal fun TypingIndicatorBubble() {
    Box(
        modifier = Modifier
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 8.dp, bottomEnd = 22.dp),
                ambientColor = Color.Black.copy(alpha = 0.12f),
                spotColor = Color.Black.copy(alpha = 0.12f),
            )
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 8.dp, bottomEnd = 22.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TypingDot(delayMillis = 0)
            TypingDot(delayMillis = 150)
            TypingDot(delayMillis = 300)
        }
    }
}

@Composable
private fun TypingDot(delayMillis: Int) {
    val transition = rememberInfiniteTransition(label = "typing-dot")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = delayMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "typing-dot-phase",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .graphicsLayer {
                val scale = 0.6f + phase * 0.4f
                scaleX = scale
                scaleY = scale
                alpha = 0.4f + phase * 0.6f
            }
            .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
    )
}

/** A small quoted-message strip shown inside a bubble (when the message is a reply) or above the input bar (while composing one). */
@Composable
internal fun QuoteBlock(label: String, preview: String, onLight: Boolean) {
    val barColor = if (onLight) Color.White else voronEncryptedColor()
    val textColor = if (onLight) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(modifier = Modifier.fillMaxHeight().width(3.dp).background(barColor))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = barColor)
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
