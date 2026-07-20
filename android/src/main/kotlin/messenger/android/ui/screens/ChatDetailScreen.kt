package messenger.android.ui.screens

import android.net.Uri
import android.widget.Toast
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import messenger.android.data.AvatarIconId
import messenger.android.data.ChatMessage
import messenger.android.data.DRAFTS_DEVICE_KEY
import messenger.android.data.VoronLog
import messenger.android.ui.Avatar
import messenger.android.ui.theme.voronEncryptedColor
import messenger.android.ui.theme.voronNeutralIconContainerColor
import messenger.android.ui.theme.voronPinColor
import messenger.android.ui.theme.voronVerifiedColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.focusRequester
import messenger.common.client.ApplicationMessage
import messenger.common.e2ee.SafetyNumber
import messenger.common.util.hexToByteArray

/** First http(s) URL in freely-typed text — mirrors [messenger.android.data.LinkPreviewFetcher.firstUrl], kept local since this is a pure UI-side detection with no fetch involved. */
private val composeUrlRegex = Regex("""https?://\S+""")
private fun detectFirstUrl(text: String): String? = composeUrlRegex.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatDetailScreen(
    ownKeyHex: String,
    peerNickname: String,
    peerKeyHex: String,
    peerAvatarIconId: AvatarIconId?,
    isVerified: Boolean,
    messages: List<ChatMessage>,
    messageInput: String,
    onMessageInputChange: (String) -> Unit,
    onSend: (replyTo: ChatMessage?, linkPreview: ApplicationMessage.LinkPreviewRef?) -> Unit,
    onFetchLinkPreview: suspend (String) -> ApplicationMessage.LinkPreviewRef? = { null },
    onSendFile: (Uri) -> Unit = {},
    onSendVoice: (file: File, durationMillis: Long) -> Unit = { _, _ -> },
    onTogglePin: (index: Int) -> Unit,
    onDeleteMessage: (index: Int) -> Unit,
    onRetryMessage: (index: Int) -> Unit,
    onSendEdit: (messageId: String, newText: String) -> Unit = { _, _ -> },
    onReact: (messageId: String, emoji: String?) -> Unit = { _, _ -> },
    onCopyText: (String) -> Unit,
    onMarkVerified: () -> Unit,
    onBack: () -> Unit,
    onOpen: () -> Unit = {},
    onClose: () -> Unit = {},
    disappearAfterMillis: Long? = null,
    onStartCall: () -> Unit = {},
    isPeerTyping: Boolean = false,
    jumpToMessageId: String? = null,
    onJumpToMessageConsumed: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var optionsSheetIndex by remember { mutableStateOf<Int?>(null) }
    var showSafetyNumber by remember { mutableStateOf(false) }
    var openLinkPreviewMessage by remember(peerKeyHex) { mutableStateOf<ChatMessage?>(null) }
    var galleryStartPath by remember(peerKeyHex) { mutableStateOf<String?>(null) }
    // Same order the LazyColumn renders them in, so swiping through the gallery matches the order
    // photos actually appear in the conversation.
    val conversationImagePaths = remember(messages) { messages.filter(::isInlineImageAttachment).mapNotNull { it.attachmentPath } }
    var replyTarget by remember(peerKeyHex) { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember(peerKeyHex) { mutableStateOf<ChatMessage?>(null) }
    // Link-preview compose state: detected purely from [messageInput], fetched only on explicit
    // tap (never automatically — see ApplicationMessage.LinkPreviewRef on why an automatic fetch
    // would leak the recipient's... no, here it's the *sender's* own IP to the link's server,
    // which is still a real address correlation the sender may not have intended by just pasting
    // a link, hence "explicit tap" rather than "as soon as detected").
    var dismissedPreviewUrl by remember(peerKeyHex) { mutableStateOf<String?>(null) }
    var fetchedLinkPreview by remember(peerKeyHex) { mutableStateOf<ApplicationMessage.LinkPreviewRef?>(null) }
    var isFetchingLinkPreview by remember(peerKeyHex) { mutableStateOf(false) }
    val detectedLinkUrl = remember(messageInput) { detectFirstUrl(messageInput) }
    LaunchedEffect(detectedLinkUrl) {
        // The text changed out from under an already-fetched (or in-flight) preview — e.g. the
        // user deleted the link or pasted a different one — so whatever was attached no longer
        // matches what's actually in the message.
        if (detectedLinkUrl != fetchedLinkPreview?.url) fetchedLinkPreview = null
        if (detectedLinkUrl == null) isFetchingLinkPreview = false
    }
    // In-chat search: separate from the global search screen (SearchScreen) — this filters only
    // the messages already loaded for *this* conversation and steps between matches by scrolling,
    // no navigation involved.
    var inChatSearchActive by remember(peerKeyHex) { mutableStateOf(false) }
    var inChatSearchQuery by remember(peerKeyHex) { mutableStateOf("") }
    val inChatMatchIndices = remember(messages, inChatSearchQuery) {
        if (inChatSearchQuery.trim().length < 2) {
            emptyList()
        } else {
            messages.withIndex().filter { (_, m) -> m.text.contains(inChatSearchQuery, ignoreCase = true) }.map { it.index }
        }
    }
    var inChatMatchPosition by remember(peerKeyHex, inChatSearchQuery) { mutableStateOf(0) }
    LaunchedEffect(inChatMatchIndices, inChatMatchPosition) {
        inChatMatchIndices.getOrNull(inChatMatchPosition)?.let { listState.animateScrollToItem(it) }
    }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // Two pickers behind the paperclip's menu. The photo picker (Android Photo Picker /
    // PickVisualMedia) is embedded in the OS and needs no external app, so it works even on a
    // de-Googled ROM where the generic SAF document picker may have no handler — which is exactly
    // why the plain document picker "didn't open" on the GrapheneOS device. Both launches are
    // wrapped so a missing handler shows a toast instead of silently doing nothing or crashing.
    val chatContext = LocalContext.current
    // The contract instances MUST be `remember`ed, not constructed inline: rememberLauncherForActivityResult
    // re-registers with ActivityResultRegistry whenever the contract's object identity changes, and
    // `PickVisualMedia()`/`OpenDocument()` have no equals() so a fresh instance every recomposition
    // (which happens on every keystroke here, since this composable recomposes with messageInput)
    // looks like a "new" contract each time — silently burning one registry request-code per keystroke
    // until the 16-bit request-code space overflows and every picker throws
    // "IllegalArgumentException: Can only use lower 16 bits for requestCode" for the rest of the process's life.
    val pickVisualMediaContract = remember { ActivityResultContracts.PickVisualMedia() }
    val openDocumentContract = remember { ActivityResultContracts.OpenDocument() }
    val photoPicker = rememberLauncherForActivityResult(pickVisualMediaContract) { uri ->
        if (uri != null) onSendFile(uri)
    }
    val documentPicker = rememberLauncherForActivityResult(openDocumentContract) { uri ->
        if (uri != null) onSendFile(uri)
    }
    val pickImage: () -> Unit = {
        // The Android Photo Picker is a system module, not guaranteed present on every ROM
        // (missing on this GrapheneOS build) — fall back to the SAF document picker scoped to
        // images/video rather than a bare "couldn't open the gallery" dead end.
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(chatContext)) {
            runCatching { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                .onFailure {
                    VoronLog.w("VoronPicker", "photo picker launch failed", it)
                    Toast.makeText(chatContext, "Couldn't open the gallery", Toast.LENGTH_SHORT).show()
                }
        } else {
            runCatching { documentPicker.launch(arrayOf("image/*", "video/*")) }
                .onFailure {
                    VoronLog.w("VoronPicker", "document picker (image fallback) launch failed", it)
                    Toast.makeText(chatContext, "No app on this device can pick images", Toast.LENGTH_LONG).show()
                }
        }
        Unit
    }
    val pickDocument: () -> Unit = {
        runCatching { documentPicker.launch(arrayOf("*/*")) }
            .onFailure {
                VoronLog.w("VoronPicker", "document picker launch failed", it)
                Toast.makeText(chatContext, "No app on this device can pick files", Toast.LENGTH_LONG).show()
            }
        Unit
    }

    DisposableEffect(peerKeyHex) {
        onOpen()
        onDispose { onClose() }
    }

    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 2
        }
    }

    // Picking a reply (swipe or long-press → Reply) should drop straight into typing, not make
    // the user tap the input field themselves on top of the message they just picked — and since
    // the reply preview + input are both at the bottom, always scroll there too, regardless of
    // which message (even one scrolled way up in history) was replied to.
    LaunchedEffect(replyTarget) {
        if (replyTarget != null) {
            inputFocusRequester.requestFocus()
            keyboardController?.show()
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
    }

    // imePadding() (see the Scaffold below) shrinks the message list's viewport from the bottom
    // the instant the keyboard opens, but the LazyColumn's own scroll offset doesn't know to
    // follow that — without this, someone already at the bottom of the conversation looks like
    // they got scrolled away from it the moment they start typing. Skipped while replying: the
    // effect above already owns the scroll for that case, and both firing at once raced and
    // produced a visible double-jump.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && replyTarget == null && messages.isNotEmpty() && isNearBottom) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(peerKeyHex) {
        // Jump to the bottom the instant this chat opens — an animated/heuristic-based scroll
        // here (as below) can race the LazyColumn's first layout pass before it has measured
        // anything, landing on whatever isNearBottom read as its default instead of the actual
        // latest message. A plain, unanimated scrollToItem has no such race. Skipped when arriving
        // from a search result: that jump (below) owns the initial scroll position instead.
        if (messages.isNotEmpty() && jumpToMessageId == null) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    // Arriving from a global search result (see AppState.pendingSearchJumpMessageId): jump
    // straight to the matched message instead of the bottom-of-chat default above.
    LaunchedEffect(peerKeyHex, jumpToMessageId, messages.size) {
        val targetId = jumpToMessageId ?: return@LaunchedEffect
        val index = messages.indexOfFirst { it.messageId == targetId }
        if (index >= 0) {
            listState.scrollToItem(index)
            onJumpToMessageConsumed()
        }
    }

    LaunchedEffect(messages.size) {
        // Auto-scroll when a new message arrives only if we were already near the bottom, or
        // it's our own just-sent message — otherwise it'd yank the user away from history
        // they scrolled up to read.
        if (messages.isNotEmpty() && (isNearBottom || messages.last().fromMe)) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val pinnedIndex = messages.indexOfFirst { it.pinned }
    val isDrafts = peerKeyHex == DRAFTS_DEVICE_KEY

    // Seeded once with whatever's already in the conversation when it's opened, so scrollback
    // history never animates — only messageIds that show up *after* that (a live send/receive)
    // are "new" and get the grow-in treatment in MessageBubble's itemsIndexed below. Hoisted here
    // (not per-item) so scrolling a message off-screen and back never replays its animation.
    val seenMessageIds = remember(peerKeyHex) { messages.mapTo(mutableSetOf()) { it.messageId } }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    if (inChatSearchActive) {
                        InChatSearchField(
                            query = inChatSearchQuery,
                            onQueryChange = {
                                inChatSearchQuery = it
                                inChatMatchPosition = 0
                            },
                        )
                        return@TopAppBar
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            Avatar(peerNickname, size = 34.dp, isDrafts = isDrafts, iconId = peerAvatarIconId)
                            if (disappearAfterMillis != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(14.dp)
                                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Schedule,
                                        contentDescription = "Disappearing messages on",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(10.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(peerNickname, style = MaterialTheme.typography.titleMedium)
                                if (isVerified && !isDrafts) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Filled.VerifiedUser,
                                        contentDescription = "Verified",
                                        tint = voronVerifiedColor(),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                            Text(
                                when {
                                    isDrafts -> "Only visible to you"
                                    isPeerTyping -> "typing…"
                                    else -> "${peerKeyHex.take(12)}…"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isPeerTyping) voronEncryptedColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (inChatSearchActive) {
                        val hasMatches = inChatMatchIndices.isNotEmpty()
                        if (inChatSearchQuery.trim().length >= 2) {
                            Text(
                                if (hasMatches) "${inChatMatchPosition + 1}/${inChatMatchIndices.size}" else "0/0",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        IconButton(onClick = { if (hasMatches) inChatMatchPosition = (inChatMatchPosition - 1 + inChatMatchIndices.size) % inChatMatchIndices.size }, enabled = hasMatches) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
                        }
                        IconButton(onClick = { if (hasMatches) inChatMatchPosition = (inChatMatchPosition + 1) % inChatMatchIndices.size }, enabled = hasMatches) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
                        }
                        IconButton(onClick = {
                            inChatSearchActive = false
                            inChatSearchQuery = ""
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    } else if (!isDrafts) {
                        IconButton(onClick = { inChatSearchActive = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search in this chat")
                        }
                        IconButton(onClick = onStartCall) {
                            BadgedBox(badge = { Badge { Text("beta", style = MaterialTheme.typography.labelSmall) } }) {
                                Icon(Icons.Filled.Call, contentDescription = "Call (beta)")
                            }
                        }
                        IconButton(onClick = { showSafetyNumber = true }) {
                            Icon(
                                Icons.Filled.VerifiedUser,
                                contentDescription = "Verify safety number",
                                tint = if (isVerified) voronVerifiedColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column {
                val editing = editingMessage
                val target = replyTarget
                if (editing != null) {
                    EditPreviewBar(
                        originalText = editing.text,
                        onCancel = {
                            editingMessage = null
                            onMessageInputChange("")
                        },
                    )
                } else if (target != null) {
                    ReplyPreviewBar(
                        message = target,
                        peerNickname = peerNickname,
                        onCancel = { replyTarget = null },
                    )
                }
                if (editing == null) {
                    val urlToOffer = detectedLinkUrl
                    if (urlToOffer != null && !isDrafts) {
                        LinkPreviewComposeBanner(
                            url = urlToOffer,
                            preview = fetchedLinkPreview,
                            isLoading = isFetchingLinkPreview,
                            isDismissed = urlToOffer == dismissedPreviewUrl,
                            onFetch = {
                                isFetchingLinkPreview = true
                                scope.launch {
                                    val result = onFetchLinkPreview(urlToOffer)
                                    isFetchingLinkPreview = false
                                    // Only adopt the result if the text still contains the same
                                    // url — the user may have edited/cleared it while the fetch
                                    // (a real network round trip) was in flight.
                                    if (detectedLinkUrl == urlToOffer) fetchedLinkPreview = result
                                }
                            },
                            onDismiss = { dismissedPreviewUrl = urlToOffer },
                            onRemove = { fetchedLinkPreview = null; dismissedPreviewUrl = urlToOffer },
                        )
                    }
                }
                MessageInputBar(
                    value = messageInput,
                    onValueChange = onMessageInputChange,
                    onSend = {
                        val beingEdited = editingMessage
                        if (beingEdited != null) {
                            if (messageInput.isNotBlank()) {
                                beingEdited.messageId?.let { id -> onSendEdit(id, messageInput) }
                            }
                            editingMessage = null
                            onMessageInputChange("")
                        } else {
                            onSend(replyTarget, fetchedLinkPreview)
                            replyTarget = null
                            fetchedLinkPreview = null
                            dismissedPreviewUrl = null
                        }
                    },
                    onPickImage = if (isDrafts) null else pickImage,
                    onPickDocument = pickDocument,
                    onVoiceMessageRecorded = if (isDrafts) null else onSendVoice,
                    focusRequester = inputFocusRequester,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (messages.isEmpty()) {
                EmptyChatState(isDrafts = isDrafts, peerNickname = peerNickname)
            }
            Column(modifier = Modifier.fillMaxSize()) {
                if (pinnedIndex >= 0) {
                    PinnedBanner(
                        text = messages[pinnedIndex].text,
                        onClick = { scope.launch { listState.animateScrollToItem(pinnedIndex) } },
                        onUnpin = { onTogglePin(pinnedIndex) },
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        messages,
                        // Without a stable key, Compose identifies items by position — any
                        // insert/delete before the end (a new incoming message while scrolled up,
                        // deleting one) made it treat every item after that point as "changed" and
                        // recompose it, instead of recognizing "same item, just shifted". messageId
                        // is stable across the message's whole lifetime (edits/reactions/delivery
                        // status all mutate the same id); only a not-yet-acked FAILED send has none.
                        key = { index, message -> message.messageId ?: "pending-$index" },
                    ) { index, message ->
                        val showDateHeader = index == 0 || !isSameDay(messages[index - 1].timestampMillis, message.timestampMillis)
                        // `remember(messageId)` runs its initializer exactly once per item identity,
                        // so this both decides "is this new" and marks it seen in the same step —
                        // safe here since it's a plain in-memory flag, not a side effect anyone else reads.
                        val isNewMessage = remember(message.messageId) { seenMessageIds.add(message.messageId) }
                        // PERFORMANCE: an already-seen message (the overwhelming majority during a
                        // fast scroll through history — every item scrolling back into view after
                        // being disposed re-runs this whole block) has no reason to pay for a
                        // graphicsLayer compositing layer plus two Animatable objects just to hold
                        // scale=1/alpha=1 forever. Only a genuinely brand-new message gets the
                        // grow-in treatment at all; everything else uses a plain Modifier.
                        val itemModifier = if (isNewMessage) {
                            val growScale = remember(message.messageId) { Animatable(0.85f) }
                            val fadeAlpha = remember(message.messageId) { Animatable(0f) }
                            LaunchedEffect(message.messageId) {
                                launch { growScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) }
                                fadeAlpha.animateTo(1f, tween(180))
                            }
                            Modifier
                                .animateItem()
                                .graphicsLayer {
                                    scaleX = growScale.value
                                    scaleY = growScale.value
                                    alpha = fadeAlpha.value
                                    // Grows from the bottom corner nearest the input bar — the
                                    // corner on the sender's own side for outgoing messages.
                                    transformOrigin = TransformOrigin(if (message.fromMe) 1f else 0f, 1f)
                                }
                        } else {
                            Modifier.animateItem()
                        }
                        Column(modifier = itemModifier) {
                            if (showDateHeader) DateHeader(message.timestampMillis)
                            MessageBubble(
                                message,
                                peerNickname = peerNickname,
                                onLongClick = { optionsSheetIndex = index },
                                onRetry = { onRetryMessage(index) },
                                onSwipeReply = {
                                    editingMessage = null
                                    replyTarget = message
                                },
                                onQuoteClick = { quotedId ->
                                    val quotedIndex = messages.indexOfFirst { it.messageId == quotedId }
                                    if (quotedIndex >= 0) scope.launch { listState.animateScrollToItem(quotedIndex) }
                                },
                                onOpenLinkPreview = { openLinkPreviewMessage = it },
                                onImageClick = { path -> galleryStartPath = path },
                            )
                        }
                    }
                    if (isPeerTyping && !isDrafts) {
                        item(key = "typing-indicator") {
                            Box(modifier = Modifier.animateItem(), contentAlignment = Alignment.CenterStart) {
                                TypingIndicatorBubble()
                            }
                        }
                    }
                }
            }
            if (!isNearBottom) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(messages.size - 1) } },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to latest")
                }
            }
        }
    }

    val sheetIndex = optionsSheetIndex
    if (sheetIndex != null && sheetIndex in messages.indices) {
        MessageOptionsSheet(
            messageText = messages[sheetIndex].text,
            isPinned = messages[sheetIndex].pinned,
            isFromMe = messages[sheetIndex].fromMe,
            currentReactionMine = messages[sheetIndex].reactionMine,
            onDismiss = { optionsSheetIndex = null },
            onReply = {
                editingMessage = null
                replyTarget = messages[sheetIndex]
                optionsSheetIndex = null
            },
            onCopy = {
                onCopyText(messages[sheetIndex].text)
                optionsSheetIndex = null
            },
            onTogglePin = {
                onTogglePin(sheetIndex)
                optionsSheetIndex = null
            },
            onDelete = {
                onDeleteMessage(sheetIndex)
                optionsSheetIndex = null
            },
            onEdit = {
                replyTarget = null
                editingMessage = messages[sheetIndex]
                onMessageInputChange(messages[sheetIndex].text)
                optionsSheetIndex = null
            },
            onReact = { emoji ->
                messages[sheetIndex].messageId?.let { id -> onReact(id, emoji.ifEmpty { null }) }
                optionsSheetIndex = null
            },
        )
    }

    if (showSafetyNumber) {
        val safetyNumber = remember(ownKeyHex, peerKeyHex) {
            SafetyNumber.format(SafetyNumber.compute(ownKeyHex.hexToByteArray(), peerKeyHex.hexToByteArray()))
        }
        SafetyNumberSheet(
            peerNickname = peerNickname,
            safetyNumberFormatted = safetyNumber,
            isVerified = isVerified,
            onDismiss = { showSafetyNumber = false },
            onMarkVerified = {
                onMarkVerified()
                showSafetyNumber = false
            },
        )
    }

    val previewMessage = openLinkPreviewMessage
    if (previewMessage != null) {
        LinkPreviewReaderSheet(message = previewMessage, onDismiss = { openLinkPreviewMessage = null })
    }

    val galleryPath = galleryStartPath
    if (galleryPath != null) {
        ImageGalleryViewer(
            imagePaths = conversationImagePaths,
            startPath = galleryPath,
            onDismiss = { galleryStartPath = null },
        )
    }
}

/** Replaces the TopAppBar's title while in-chat search is active — auto-focused the moment it appears. */
@Composable
private fun InChatSearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search in this chat") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
}

/** Shown centered over an empty message list — a first-open explainer instead of a blank screen. */
@Composable
private fun EmptyChatState(isDrafts: Boolean, peerNickname: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.VerifiedUser,
            contentDescription = null,
            tint = voronEncryptedColor(),
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (isDrafts) "Notes to yourself" else "End-to-end encrypted",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (isDrafts) {
                "Only visible on this device — nothing here is ever sent anywhere."
            } else {
                "Messages to $peerNickname are secured with an encryption key only the two of you have. Not even the relay can read them."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PinnedBanner(text: String, onClick: () -> Unit, onUnpin: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(voronNeutralIconContainerColor())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.PushPin,
            contentDescription = "Pinned message",
            tint = voronPinColor(),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onUnpin, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Unpin",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
