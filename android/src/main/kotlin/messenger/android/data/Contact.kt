package messenger.android.data

/**
 * Sentinel "device key" for the built-in Drafts self-notes chat — a real device key is always 64
 * hex chars, so this can never collide with one. Messages sent here never touch the network; see
 * [messenger.android.data.ConnectionManager.sendMessage].
 */
const val DRAFTS_DEVICE_KEY = "drafts"

/**
 * A saved peer: a human-friendly label over a raw 32-byte device key.
 * [nicknameConfirmed] is false while [nickname] is just a placeholder (the
 * key prefix) — it flips to true once the peer's real display name arrives
 * inside a successfully decrypted message, i.e. only after an E2EE session
 * with them is actually established. [verified] means the user compared
 * safety numbers with this peer out of band and confirmed they match.
 */
data class Contact(
    val nickname: String,
    val deviceKeyHex: String,
    val nicknameConfirmed: Boolean = false,
    val pinned: Boolean = false,
    val verified: Boolean = false,
    val hasUnread: Boolean = false,
    /** The avatar glyph this peer has chosen, as told to us the same way their nickname is — see [AppState.applyIncomingProfile]. */
    val avatarIconId: AvatarIconId? = null,
    /** Blocked contacts' incoming messages are silently dropped — see [ConnectionManager]'s incoming-message handler. Purely local: they aren't told. */
    val blocked: Boolean = false,
    /** How long a message sent/received in this chat sticks around before this device deletes it — see [ChatMessage.expiresAtMillis]. Local-only per device, not a synced Signal-style timer told to the peer. */
    val disappearAfterMillis: Long? = null,
)

/** Delivery state of a message this device sent — meaningless for incoming messages. [RETRYING] only ever exists transiently in memory, see [messenger.android.data.MessageStore]. */
enum class DeliveryStatus { SENT, DELIVERED, SEEN, FAILED, RETRYING }

/**
 * Progress of a file attachment's transfer (see [messenger.android.data.FileTransferManager]).
 * Null on a plain text message. [OFFERED] = offer sent/received, waiting to start; [TRANSFERRING] =
 * chunks in flight ([ChatMessage.transferProgress] tracks it); [COMPLETE] = the whole file is on
 * this device; [FAILED] = the transfer was aborted (peer offline, cancelled, or an error).
 */
enum class FileTransferStatus { OFFERED, TRANSFERRING, COMPLETE, FAILED }

/**
 * One message in a conversation, already decrypted/plaintext. At most one message per
 * conversation is [pinned]. [messageId] is always the ID the message's *original sender* stamped
 * on it, so we can match acks against it either direction. [deliveryStatus] only matters when
 * [fromMe] is true — it tracks whether/how far the peer's device has acknowledged it. [readAckSent]
 * is the mirror image for incoming messages: whether we've told the sender we've shown this one on
 * screen yet (see [messenger.android.data.ConnectionManager.markChatSeen]) — avoids re-sending a
 * READ_ACK for the same message every time the chat is reopened.
 */
data class ChatMessage(
    val fromMe: Boolean,
    val text: String,
    val timestampMillis: Long,
    val pinned: Boolean = false,
    val messageId: String? = null,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.SENT,
    val readAckSent: Boolean = false,
    /** Set at send/receive time from the chat's disappearing-messages duration then in effect — later changing that duration doesn't retroactively touch messages already sent. Null means it never expires. */
    val expiresAtMillis: Long? = null,
    /** Non-null exactly when this message was sent as a reply — a quote of the referenced message, carried in the wire payload itself (see [messenger.common.client.ApplicationMessage.ReplyReference]) so it still renders even if the original has since disappeared/been deleted locally. */
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val replyToFromMe: Boolean = false,
    /**
     * File-attachment fields, all null/default on a plain text message. When [transferStatus] is
     * non-null this message is an attachment: [messageId] doubles as the transfer's file id.
     * [attachmentPath] is the local path once (and only once) the bytes are on this device — a file
     * lives only on the two endpoints, never on the relay. [transferProgress] is 0..1 while
     * [FileTransferStatus.TRANSFERRING].
     */
    val attachmentPath: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val transferStatus: FileTransferStatus? = null,
    val transferProgress: Float = 0f,
    /** True once this message's text has been replaced by an [messenger.common.e2ee.EditSignal] — shown as an "edited" label, no history of the previous text is kept. */
    val edited: Boolean = false,
    /** Non-null exactly when this is a sticker rather than a text message — its wire value (see [StickerId.wireValue]); [text] is empty on a sticker message. Resolved back to art via [StickerId.fromWireValue] at render time so an unrecognized ID (older/newer build) degrades gracefully instead of crashing. */
    val stickerId: Int? = null,
    /** This device's own reaction on this message (either party's own message), null if none. */
    val reactionMine: String? = null,
    /** The peer's reaction on this message, null if none. */
    val reactionTheirs: String? = null,
    /**
     * Link-preview fields, all null on a message without one. The preview is fetched and archived
     * entirely by whichever side *sent* the link (see `messenger.android.data.LinkPreviewFetcher`)
     * and travels inside the encrypted message itself — the recipient never fetches anything, so
     * opening a chat can never leak a shared URL's real IP/timing to that URL's own server.
     * [linkPreviewPageText] is a reader-mode plain-text extraction of the article, shown in-app.
     * [linkPreviewImagePath] is a local file (same pattern as [attachmentPath]) holding the
     * thumbnail JPEG once decoded, not the raw wire bytes.
     */
    val linkPreviewUrl: String? = null,
    val linkPreviewTitle: String? = null,
    val linkPreviewPageText: String? = null,
    val linkPreviewImagePath: String? = null,
    /** Who actually sent this message, as a device key hex — only meaningful in a group conversation (a 1:1 conversation's sender is always implied by which contact it belongs to). Null for `fromMe == true` messages and for every 1:1 message. */
    val senderKeyHex: String? = null,
)
