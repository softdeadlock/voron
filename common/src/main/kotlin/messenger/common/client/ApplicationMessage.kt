package messenger.common.client

/**
 * The plaintext wrapped inside every E2eeMessage: a length-prefixed sender
 * display name, a one-byte avatar glyph selector, a fixed-size message ID,
 * then the message body. Since this whole blob only exists once
 * [messenger.common.e2ee.E2eeManager.decrypt] has succeeded, the nickname
 * (and avatar choice) is exactly as protected as the message text — relay
 * and any network observer see neither, and a peer whose session isn't
 * established yet can't see it either.
 *
 * [senderAvatarIcon] is an opaque byte as far as this layer is concerned (0
 * means "no glyph, show the initial letter") — the actual icon set is a
 * client-side UI concept (see `messenger.android.data.AvatarIconId`), kept
 * out of :common so the wire format doesn't depend on a specific icon list.
 *
 * The message ID has no cryptographic role — it's just a correlation handle
 * so a [messenger.common.protocol.TransportFrame.DELIVERY_ACK] sent back by
 * the recipient can tell the sender *which* message got delivered.
 */
object ApplicationMessage {
    const val MESSAGE_ID_LENGTH = 8
    private const val MAX_REPLY_PREVIEW_BYTES = 200

    // A link preview is fetched and archived entirely by the *sender* (see
    // messenger.android.data.LinkPreviewFetcher) and embedded here so the recipient never makes a
    // network request of their own for it — opening a shared link on the recipient's device would
    // otherwise hand that link's server the recipient's real IP and a precise timestamp, exactly
    // the kind of correlation this app's onion-routed relay connection exists to prevent. Capped
    // generously above any real article/thumbnail so one oversized page can't bloat every offline
    // mailbox entry without bound.
    private const val MAX_PREVIEW_URL_BYTES = 512
    private const val MAX_PREVIEW_TITLE_BYTES = 200
    const val MAX_PREVIEW_TEXT_BYTES = 8 * 1024
    const val MAX_PREVIEW_IMAGE_BYTES = 48 * 1024

    // Voice messages travel embedded in the message itself (like a link preview's thumbnail)
    // rather than through FileTransferManager's live-only file-transfer pipe, specifically so they
    // get the same mailbox store-and-forward guarantee as text: FILE_TRANSFER frames are
    // deliberately never mailboxed (see FileSignal's doc comment) because arbitrary file transfers
    // could be large enough to make that expensive — a voice note is small and bounded (client caps
    // recording length), so there's no reason to give up offline delivery for one.
    const val MAX_VOICE_BYTES = 1024 * 1024

    /**
     * A quoted reference to an earlier message, carried alongside a reply so the recipient can
     * show the quote even if their own copy of the original was deleted/expired locally.
     * [repliedToWasFromReplier] is from the sender's own point of view — true means *they*
     * authored the message being quoted — so the recipient can flip it to figure out whether the
     * quote is of their own past message or the sender's (a 1:1 chat only has two possible
     * authors, so this single bit is enough to disambiguate on the other end).
     */
    data class ReplyReference(val repliedToMessageId: ByteArray, val previewText: String, val repliedToWasFromReplier: Boolean)

    /**
     * A link preview the sender fetched and is archiving alongside the message: [pageText] is a
     * plain-text reader-mode extraction of the linked page's main content (see
     * `LinkPreviewFetcher`), so the recipient can read it without ever visiting [url] themselves.
     * [imageBytes] is a small pre-downsampled JPEG thumbnail (og:image), or null if the page had
     * none or it didn't fit under [MAX_PREVIEW_IMAGE_BYTES].
     */
    data class LinkPreviewRef(val url: String, val title: String, val pageText: String, val imageBytes: ByteArray?)

    /** A voice message's raw audio bytes (AAC/M4A) and its recorded duration, embedded directly — see [MAX_VOICE_BYTES]. */
    data class VoiceAttachmentRef(val audioBytes: ByteArray, val durationMillis: Long)

    fun encode(
        senderDisplayName: String,
        senderAvatarIcon: Int,
        messageId: ByteArray,
        body: ByteArray,
        replyTo: ReplyReference? = null,
        linkPreview: LinkPreviewRef? = null,
        voiceAttachment: VoiceAttachmentRef? = null,
    ): ByteArray {
        require(messageId.size == MESSAGE_ID_LENGTH) { "message id must be $MESSAGE_ID_LENGTH bytes" }
        require(senderAvatarIcon in 0..255) { "avatar icon selector must fit in a byte" }
        val nameBytes = senderDisplayName.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 255) { "display name too long once UTF-8 encoded (max 255 bytes)" }

        val previewBytes = replyTo?.let { truncateToUtf8Bytes(it.previewText, MAX_REPLY_PREVIEW_BYTES) }
        if (replyTo != null) {
            require(replyTo.repliedToMessageId.size == MESSAGE_ID_LENGTH) { "replied-to message id must be $MESSAGE_ID_LENGTH bytes" }
        }
        val replySectionSize = if (replyTo != null) 1 + MESSAGE_ID_LENGTH + 1 + previewBytes!!.size + 1 else 1

        val linkUrlBytes = linkPreview?.url?.toByteArray(Charsets.UTF_8)
        val linkTitleBytes = linkPreview?.let { truncateToUtf8Bytes(it.title, MAX_PREVIEW_TITLE_BYTES) }
        val linkTextBytes = linkPreview?.pageText?.toByteArray(Charsets.UTF_8)
        val linkImageBytes = linkPreview?.imageBytes
        if (linkPreview != null) {
            require(linkUrlBytes!!.size <= MAX_PREVIEW_URL_BYTES) { "link preview url too long" }
            require(linkTextBytes!!.size <= MAX_PREVIEW_TEXT_BYTES) { "link preview text too long" }
            require(linkImageBytes == null || linkImageBytes.size <= MAX_PREVIEW_IMAGE_BYTES) { "link preview image too large" }
        }
        val previewSectionSize = if (linkPreview != null) {
            1 + 2 + linkUrlBytes!!.size + 1 + linkTitleBytes!!.size + 4 + linkTextBytes!!.size + 4 + (linkImageBytes?.size ?: 0)
        } else {
            1
        }

        val voiceAudioBytes = voiceAttachment?.audioBytes
        if (voiceAttachment != null) {
            require(voiceAudioBytes!!.size <= MAX_VOICE_BYTES) { "voice message too large" }
        }
        val voiceSectionSize = if (voiceAttachment != null) 1 + 4 + voiceAudioBytes!!.size + 8 else 1

        val out = ByteArray(1 + nameBytes.size + 1 + MESSAGE_ID_LENGTH + replySectionSize + previewSectionSize + voiceSectionSize + body.size)
        var offset = 0
        out[offset] = nameBytes.size.toByte(); offset += 1
        nameBytes.copyInto(out, offset); offset += nameBytes.size
        out[offset] = senderAvatarIcon.toByte(); offset += 1
        messageId.copyInto(out, offset); offset += MESSAGE_ID_LENGTH
        if (replyTo != null) {
            out[offset] = 1; offset += 1
            replyTo.repliedToMessageId.copyInto(out, offset); offset += MESSAGE_ID_LENGTH
            out[offset] = previewBytes!!.size.toByte(); offset += 1
            previewBytes.copyInto(out, offset); offset += previewBytes.size
            out[offset] = if (replyTo.repliedToWasFromReplier) 1 else 0; offset += 1
        } else {
            out[offset] = 0; offset += 1
        }
        if (linkPreview != null) {
            out[offset] = 1; offset += 1
            offset = putShort(out, offset, linkUrlBytes!!.size)
            linkUrlBytes.copyInto(out, offset); offset += linkUrlBytes.size
            out[offset] = linkTitleBytes!!.size.toByte(); offset += 1
            linkTitleBytes.copyInto(out, offset); offset += linkTitleBytes.size
            offset = putInt(out, offset, linkTextBytes!!.size)
            linkTextBytes.copyInto(out, offset); offset += linkTextBytes.size
            offset = putInt(out, offset, linkImageBytes?.size ?: 0)
            linkImageBytes?.copyInto(out, offset)
            offset += (linkImageBytes?.size ?: 0)
        } else {
            out[offset] = 0; offset += 1
        }
        if (voiceAttachment != null) {
            out[offset] = 1; offset += 1
            offset = putInt(out, offset, voiceAudioBytes!!.size)
            voiceAudioBytes.copyInto(out, offset); offset += voiceAudioBytes.size
            offset = putLong(out, offset, voiceAttachment.durationMillis)
        } else {
            out[offset] = 0; offset += 1
        }
        body.copyInto(out, offset)
        return out
    }

    data class Decoded(
        val senderDisplayName: String,
        val senderAvatarIcon: Int,
        val messageId: ByteArray,
        val body: ByteArray,
        val replyTo: ReplyReference? = null,
        val linkPreview: LinkPreviewRef? = null,
        val voiceAttachment: VoiceAttachmentRef? = null,
    )

    /**
     * Encodes [text] to UTF-8 and trims it to at most [maxBytes] *without* splitting a multi-byte
     * character — a plain `take(maxBytes)` on the byte array would cut a Cyrillic char (2 bytes each)
     * in half, decoding to a U+FFFD replacement glyph on the recipient's side. Backs the cut off any
     * trailing UTF-8 continuation byte (`10xxxxxx`) so only whole code points survive.
     */
    private fun truncateToUtf8Bytes(text: String, maxBytes: Int): ByteArray {
        val full = text.toByteArray(Charsets.UTF_8)
        if (full.size <= maxBytes) return full
        var end = maxBytes
        while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) end--
        return full.copyOfRange(0, end)
    }

    private fun putShort(out: ByteArray, offset: Int, value: Int): Int {
        out[offset] = (value ushr 8).toByte()
        out[offset + 1] = value.toByte()
        return offset + 2
    }

    private fun getShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun putInt(out: ByteArray, offset: Int, value: Int): Int {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
        return offset + 4
    }

    private fun getInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun putLong(out: ByteArray, offset: Int, value: Long): Int {
        for (i in 0 until 8) out[offset + i] = (value ushr (56 - i * 8)).toByte()
        return offset + 8
    }

    private fun getLong(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return result
    }

    fun decode(bytes: ByteArray): Decoded {
        require(bytes.isNotEmpty()) { "empty application message" }
        var offset = 0
        val nameLen = bytes[offset].toInt() and 0xFF; offset += 1
        require(bytes.size >= offset + nameLen + 1 + MESSAGE_ID_LENGTH + 1) { "truncated application message" }
        val name = String(bytes, offset, nameLen, Charsets.UTF_8); offset += nameLen
        val avatarIcon = bytes[offset].toInt() and 0xFF; offset += 1
        val messageId = bytes.copyOfRange(offset, offset + MESSAGE_ID_LENGTH); offset += MESSAGE_ID_LENGTH
        val hasReply = bytes[offset].toInt() and 0xFF == 1; offset += 1
        val replyTo = if (hasReply) {
            require(bytes.size >= offset + MESSAGE_ID_LENGTH + 1) { "truncated reply reference" }
            val repliedToId = bytes.copyOfRange(offset, offset + MESSAGE_ID_LENGTH); offset += MESSAGE_ID_LENGTH
            val previewLen = bytes[offset].toInt() and 0xFF; offset += 1
            require(bytes.size >= offset + previewLen + 1) { "truncated reply preview" }
            val preview = String(bytes, offset, previewLen, Charsets.UTF_8); offset += previewLen
            val repliedToWasFromReplier = bytes[offset].toInt() and 0xFF == 1; offset += 1
            ReplyReference(repliedToId, preview, repliedToWasFromReplier)
        } else {
            null
        }
        require(bytes.size >= offset + 1) { "truncated application message (no preview flag)" }
        val hasPreview = bytes[offset].toInt() and 0xFF == 1; offset += 1
        val linkPreview = if (hasPreview) {
            require(bytes.size >= offset + 2) { "truncated link preview url length" }
            val urlLen = getShort(bytes, offset); offset += 2
            require(bytes.size >= offset + urlLen + 1) { "truncated link preview url" }
            val url = String(bytes, offset, urlLen, Charsets.UTF_8); offset += urlLen
            val titleLen = bytes[offset].toInt() and 0xFF; offset += 1
            require(bytes.size >= offset + titleLen + 4) { "truncated link preview title" }
            val title = String(bytes, offset, titleLen, Charsets.UTF_8); offset += titleLen
            val textLen = getInt(bytes, offset); offset += 4
            require(textLen in 0..MAX_PREVIEW_TEXT_BYTES) { "link preview text length out of range: $textLen" }
            require(bytes.size >= offset + textLen + 4) { "truncated link preview text" }
            val pageText = String(bytes, offset, textLen, Charsets.UTF_8); offset += textLen
            val imageLen = getInt(bytes, offset); offset += 4
            require(imageLen in 0..MAX_PREVIEW_IMAGE_BYTES) { "link preview image length out of range: $imageLen" }
            require(bytes.size >= offset + imageLen) { "truncated link preview image" }
            val imageBytes = if (imageLen > 0) bytes.copyOfRange(offset, offset + imageLen) else null
            offset += imageLen
            LinkPreviewRef(url, title, pageText, imageBytes)
        } else {
            null
        }
        require(bytes.size >= offset + 1) { "truncated application message (no voice flag)" }
        val hasVoice = bytes[offset].toInt() and 0xFF == 1; offset += 1
        val voiceAttachment = if (hasVoice) {
            require(bytes.size >= offset + 4) { "truncated voice attachment length" }
            val audioLen = getInt(bytes, offset); offset += 4
            require(audioLen in 0..MAX_VOICE_BYTES) { "voice attachment length out of range: $audioLen" }
            require(bytes.size >= offset + audioLen + 8) { "truncated voice attachment audio" }
            val audioBytes = bytes.copyOfRange(offset, offset + audioLen); offset += audioLen
            val durationMillis = getLong(bytes, offset); offset += 8
            VoiceAttachmentRef(audioBytes, durationMillis)
        } else {
            null
        }
        val body = bytes.copyOfRange(offset, bytes.size)
        return Decoded(name, avatarIcon, messageId, body, replyTo, linkPreview, voiceAttachment)
    }
}
