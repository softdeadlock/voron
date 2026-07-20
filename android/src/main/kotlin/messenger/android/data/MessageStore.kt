package messenger.android.data

/**
 * Persists message history as tab-separated lines, one per message, encrypted at rest via
 * [SecureStore]. Column order (25): peerKeyHex, fromMe, timestampMillis, pinned, messageId,
 * deliveryStatus, expiresAtMillis, readAckSent, replyToMessageId, replyToFromMe, replyToPreview,
 * attachmentPath, attachmentName, attachmentMime, attachmentSize, transferStatus, edited,
 * reactionMine, reactionTheirs, linkPreviewUrl, linkPreviewTitle, linkPreviewPageText,
 * linkPreviewImagePath, senderKeyHex, text. [peerKeyHex] doubles as a group id (hex) for a group
 * conversation — see [GroupManager]; the two never collide since a group id is 16 bytes (32 hex
 * chars) and a device key is 32 bytes (64 hex chars). Free-form fields (text, reply preview,
 * attachment path/name, reactions, link preview fields) are escaped so an embedded tab/newline
 * can't corrupt the line format. Older shorter lines (predating later columns) still parse, with
 * sensible defaults.
 */
class MessageStore(private val store: SecureStore) {

    fun loadAll(): Map<String, List<ChatMessage>> {
        val text = store.readText() ?: return emptyMap()
        return text.lines()
            .filter { it.isNotBlank() }
            .mapNotNull(::parseLine)
            .groupBy({ it.first }, { it.second })
    }

    fun append(peerKeyHex: String, message: ChatMessage) {
        store.writeText((allLines() + encodeLine(peerKeyHex, message)).joinToString("\n"))
    }

    /** Rewrites the store without any messages for [peerKeyHex] (used when a contact is removed). */
    fun deleteConversation(peerKeyHex: String) {
        val remaining = allLines().filter { line -> line.substringBefore('\t') != peerKeyHex }
        store.writeText(remaining.joinToString("\n"))
    }

    /** Wipes every conversation's history on this device (contacts/nicknames are untouched). */
    fun deleteAll() {
        store.writeText("")
    }

    /** Rewrites [peerKeyHex]'s lines to match [messages] (used for in-place edits like pin/unpin). */
    fun replaceConversation(peerKeyHex: String, messages: List<ChatMessage>) {
        val otherLines = allLines().filter { line -> line.substringBefore('\t') != peerKeyHex }
        val newLines = messages.map { encodeLine(peerKeyHex, it) }
        store.writeText((otherLines + newLines).joinToString("\n"))
    }

    private fun allLines(): List<String> = store.readText()?.lines()?.filter { it.isNotBlank() } ?: emptyList()

    private fun encodeLine(peerKeyHex: String, message: ChatMessage): String =
        listOf(
            peerKeyHex,
            if (message.fromMe) "1" else "0",
            message.timestampMillis.toString(),
            if (message.pinned) "1" else "0",
            message.messageId.orEmpty(),
            message.deliveryStatus.name,
            message.expiresAtMillis?.toString().orEmpty(),
            if (message.readAckSent) "1" else "0",
            message.replyToMessageId.orEmpty(),
            if (message.replyToFromMe) "1" else "0",
            escape(message.replyToPreview.orEmpty()),
            escape(message.attachmentPath.orEmpty()),
            escape(message.attachmentName.orEmpty()),
            message.attachmentMime.orEmpty(),
            message.attachmentSize?.toString().orEmpty(),
            message.transferStatus?.name.orEmpty(),
            if (message.edited) "1" else "0",
            escape(message.reactionMine.orEmpty()),
            escape(message.reactionTheirs.orEmpty()),
            escape(message.linkPreviewUrl.orEmpty()),
            escape(message.linkPreviewTitle.orEmpty()),
            escape(message.linkPreviewPageText.orEmpty()),
            escape(message.linkPreviewImagePath.orEmpty()),
            message.senderKeyHex.orEmpty(),
            escape(message.text),
        ).joinToString("\t")

    private fun parseLine(line: String): Pair<String, ChatMessage>? {
        val parts = line.split("\t", limit = 25)
        return when (parts.size) {
            25 -> {
                val timestamp = parts[2].toLongOrNull() ?: return null
                val status = runCatching { DeliveryStatus.valueOf(parts[5]) }.getOrDefault(DeliveryStatus.SENT)
                    .let { if (it == DeliveryStatus.RETRYING) DeliveryStatus.FAILED else it }
                // A transfer left mid-flight when the process died can't still be running — surface
                // it as FAILED so it isn't stuck showing a frozen progress bar forever.
                val transferStatus = parts[15].ifEmpty { null }?.let {
                    runCatching { FileTransferStatus.valueOf(it) }.getOrNull()
                }?.let { if (it == FileTransferStatus.OFFERED || it == FileTransferStatus.TRANSFERRING) FileTransferStatus.FAILED else it }
                parts[0] to ChatMessage(
                    fromMe = parts[1] == "1",
                    text = unescape(parts[24]),
                    timestampMillis = timestamp,
                    pinned = parts[3] == "1",
                    messageId = parts[4].ifEmpty { null },
                    deliveryStatus = status,
                    expiresAtMillis = parts[6].toLongOrNull(),
                    readAckSent = parts[7] == "1",
                    replyToMessageId = parts[8].ifEmpty { null },
                    replyToFromMe = parts[9] == "1",
                    replyToPreview = unescape(parts[10]).ifEmpty { null },
                    attachmentPath = unescape(parts[11]).ifEmpty { null },
                    attachmentName = unescape(parts[12]).ifEmpty { null },
                    attachmentMime = parts[13].ifEmpty { null },
                    attachmentSize = parts[14].toLongOrNull(),
                    transferStatus = transferStatus,
                    transferProgress = if (transferStatus == FileTransferStatus.COMPLETE) 1f else 0f,
                    edited = parts[16] == "1",
                    reactionMine = unescape(parts[17]).ifEmpty { null },
                    reactionTheirs = unescape(parts[18]).ifEmpty { null },
                    linkPreviewUrl = unescape(parts[19]).ifEmpty { null },
                    linkPreviewTitle = unescape(parts[20]).ifEmpty { null },
                    linkPreviewPageText = unescape(parts[21]).ifEmpty { null },
                    linkPreviewImagePath = unescape(parts[22]).ifEmpty { null },
                    senderKeyHex = parts[23].ifEmpty { null },
                )
            }
            24 -> {
                val timestamp = parts[2].toLongOrNull() ?: return null
                val status = runCatching { DeliveryStatus.valueOf(parts[5]) }.getOrDefault(DeliveryStatus.SENT)
                    .let { if (it == DeliveryStatus.RETRYING) DeliveryStatus.FAILED else it }
                // A transfer left mid-flight when the process died can't still be running — surface
                // it as FAILED so it isn't stuck showing a frozen progress bar forever.
                val transferStatus = parts[15].ifEmpty { null }?.let {
                    runCatching { FileTransferStatus.valueOf(it) }.getOrNull()
                }?.let { if (it == FileTransferStatus.OFFERED || it == FileTransferStatus.TRANSFERRING) FileTransferStatus.FAILED else it }
                parts[0] to ChatMessage(
                    fromMe = parts[1] == "1",
                    text = unescape(parts[23]),
                    timestampMillis = timestamp,
                    pinned = parts[3] == "1",
                    messageId = parts[4].ifEmpty { null },
                    deliveryStatus = status,
                    expiresAtMillis = parts[6].toLongOrNull(),
                    readAckSent = parts[7] == "1",
                    replyToMessageId = parts[8].ifEmpty { null },
                    replyToFromMe = parts[9] == "1",
                    replyToPreview = unescape(parts[10]).ifEmpty { null },
                    attachmentPath = unescape(parts[11]).ifEmpty { null },
                    attachmentName = unescape(parts[12]).ifEmpty { null },
                    attachmentMime = parts[13].ifEmpty { null },
                    attachmentSize = parts[14].toLongOrNull(),
                    transferStatus = transferStatus,
                    transferProgress = if (transferStatus == FileTransferStatus.COMPLETE) 1f else 0f,
                    edited = parts[16] == "1",
                    reactionMine = unescape(parts[17]).ifEmpty { null },
                    reactionTheirs = unescape(parts[18]).ifEmpty { null },
                    linkPreviewUrl = unescape(parts[19]).ifEmpty { null },
                    linkPreviewTitle = unescape(parts[20]).ifEmpty { null },
                    linkPreviewPageText = unescape(parts[21]).ifEmpty { null },
                    linkPreviewImagePath = unescape(parts[22]).ifEmpty { null },
                )
            }
            20 -> {
                val timestamp = parts[2].toLongOrNull() ?: return null
                val status = runCatching { DeliveryStatus.valueOf(parts[5]) }.getOrDefault(DeliveryStatus.SENT)
                    .let { if (it == DeliveryStatus.RETRYING) DeliveryStatus.FAILED else it }
                // A transfer left mid-flight when the process died can't still be running — surface
                // it as FAILED so it isn't stuck showing a frozen progress bar forever.
                val transferStatus = parts[15].ifEmpty { null }?.let {
                    runCatching { FileTransferStatus.valueOf(it) }.getOrNull()
                }?.let { if (it == FileTransferStatus.OFFERED || it == FileTransferStatus.TRANSFERRING) FileTransferStatus.FAILED else it }
                parts[0] to ChatMessage(
                    fromMe = parts[1] == "1",
                    text = unescape(parts[19]),
                    timestampMillis = timestamp,
                    pinned = parts[3] == "1",
                    messageId = parts[4].ifEmpty { null },
                    deliveryStatus = status,
                    expiresAtMillis = parts[6].toLongOrNull(),
                    readAckSent = parts[7] == "1",
                    replyToMessageId = parts[8].ifEmpty { null },
                    replyToFromMe = parts[9] == "1",
                    replyToPreview = unescape(parts[10]).ifEmpty { null },
                    attachmentPath = unescape(parts[11]).ifEmpty { null },
                    attachmentName = unescape(parts[12]).ifEmpty { null },
                    attachmentMime = parts[13].ifEmpty { null },
                    attachmentSize = parts[14].toLongOrNull(),
                    transferStatus = transferStatus,
                    transferProgress = if (transferStatus == FileTransferStatus.COMPLETE) 1f else 0f,
                    edited = parts[16] == "1",
                    reactionMine = unescape(parts[17]).ifEmpty { null },
                    reactionTheirs = unescape(parts[18]).ifEmpty { null },
                )
            }
            17 -> {
                val timestamp = parts[2].toLongOrNull() ?: return null
                val status = runCatching { DeliveryStatus.valueOf(parts[5]) }.getOrDefault(DeliveryStatus.SENT)
                    .let { if (it == DeliveryStatus.RETRYING) DeliveryStatus.FAILED else it }
                // A transfer left mid-flight when the process died can't still be running — surface
                // it as FAILED so it isn't stuck showing a frozen progress bar forever.
                val transferStatus = parts[15].ifEmpty { null }?.let {
                    runCatching { FileTransferStatus.valueOf(it) }.getOrNull()
                }?.let { if (it == FileTransferStatus.OFFERED || it == FileTransferStatus.TRANSFERRING) FileTransferStatus.FAILED else it }
                parts[0] to ChatMessage(
                    fromMe = parts[1] == "1",
                    text = unescape(parts[16]),
                    timestampMillis = timestamp,
                    pinned = parts[3] == "1",
                    messageId = parts[4].ifEmpty { null },
                    deliveryStatus = status,
                    expiresAtMillis = parts[6].toLongOrNull(),
                    readAckSent = parts[7] == "1",
                    replyToMessageId = parts[8].ifEmpty { null },
                    replyToFromMe = parts[9] == "1",
                    replyToPreview = unescape(parts[10]).ifEmpty { null },
                    attachmentPath = unescape(parts[11]).ifEmpty { null },
                    attachmentName = unescape(parts[12]).ifEmpty { null },
                    attachmentMime = parts[13].ifEmpty { null },
                    attachmentSize = parts[14].toLongOrNull(),
                    transferStatus = transferStatus,
                    transferProgress = if (transferStatus == FileTransferStatus.COMPLETE) 1f else 0f,
                )
            }
            12 -> {
                val timestamp = parts[2].toLongOrNull() ?: return null
                val status = runCatching { DeliveryStatus.valueOf(parts[5]) }.getOrDefault(DeliveryStatus.SENT)
                    .let { if (it == DeliveryStatus.RETRYING) DeliveryStatus.FAILED else it }
                parts[0] to ChatMessage(
                    fromMe = parts[1] == "1",
                    text = unescape(parts[11]),
                    timestampMillis = timestamp,
                    pinned = parts[3] == "1",
                    messageId = parts[4].ifEmpty { null },
                    deliveryStatus = status,
                    expiresAtMillis = parts[6].toLongOrNull(),
                    readAckSent = parts[7] == "1",
                    replyToMessageId = parts[8].ifEmpty { null },
                    replyToFromMe = parts[9] == "1",
                    replyToPreview = unescape(parts[10]).ifEmpty { null },
                )
            }
            9 -> {
                val timestamp = parts[2].toLongOrNull() ?: return null
                // RETRYING only ever means "a retry coroutine is live right now" — one from a
                // previous process can't still be running, so treat it as FAILED again on load.
                val status = runCatching { DeliveryStatus.valueOf(parts[5]) }.getOrDefault(DeliveryStatus.SENT)
                    .let { if (it == DeliveryStatus.RETRYING) DeliveryStatus.FAILED else it }
                parts[0] to ChatMessage(
                    fromMe = parts[1] == "1",
                    text = unescape(parts[8]),
                    timestampMillis = timestamp,
                    pinned = parts[3] == "1",
                    messageId = parts[4].ifEmpty { null },
                    deliveryStatus = status,
                    expiresAtMillis = parts[6].toLongOrNull(),
                    readAckSent = parts[7] == "1",
                )
            }
            8 -> {
                val timestamp = parts[2].toLongOrNull() ?: return null
                val status = runCatching { DeliveryStatus.valueOf(parts[5]) }.getOrDefault(DeliveryStatus.SENT)
                    .let { if (it == DeliveryStatus.RETRYING) DeliveryStatus.FAILED else it }
                parts[0] to ChatMessage(
                    fromMe = parts[1] == "1",
                    text = unescape(parts[7]),
                    timestampMillis = timestamp,
                    pinned = parts[3] == "1",
                    messageId = parts[4].ifEmpty { null },
                    deliveryStatus = status,
                    expiresAtMillis = parts[6].toLongOrNull(),
                )
            }
            7 -> {
                val timestamp = parts[2].toLongOrNull() ?: return null
                val status = runCatching { DeliveryStatus.valueOf(parts[5]) }.getOrDefault(DeliveryStatus.SENT)
                    .let { if (it == DeliveryStatus.RETRYING) DeliveryStatus.FAILED else it }
                parts[0] to ChatMessage(
                    fromMe = parts[1] == "1",
                    text = unescape(parts[6]),
                    timestampMillis = timestamp,
                    pinned = parts[3] == "1",
                    messageId = parts[4].ifEmpty { null },
                    deliveryStatus = status,
                )
            }
            5 -> {
                val timestamp = parts[2].toLongOrNull() ?: return null
                parts[0] to ChatMessage(fromMe = parts[1] == "1", text = unescape(parts[4]), timestampMillis = timestamp, pinned = parts[3] == "1")
            }
            4 -> {
                val timestamp = parts[2].toLongOrNull() ?: return null
                parts[0] to ChatMessage(fromMe = parts[1] == "1", text = unescape(parts[3]), timestampMillis = timestamp)
            }
            else -> null
        }
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c); i += 1
            }
        }
        return sb.toString()
    }
}
