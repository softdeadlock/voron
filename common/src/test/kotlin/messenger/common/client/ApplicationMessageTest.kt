package messenger.common.client

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ApplicationMessageTest {

    @Test
    fun `round-trips without a reply`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH) { it.toByte() }
        val body = "hello".toByteArray()
        val encoded = ApplicationMessage.encode("Alice", 3, messageId, body)

        val decoded = ApplicationMessage.decode(encoded)

        assertEquals("Alice", decoded.senderDisplayName)
        assertEquals(3, decoded.senderAvatarIcon)
        assertArrayEquals(messageId, decoded.messageId)
        assertArrayEquals(body, decoded.body)
        assertNull(decoded.replyTo)
    }

    @Test
    fun `round-trips with a reply reference`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH) { it.toByte() }
        val repliedToId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH) { (it + 1).toByte() }
        val body = "reply body".toByteArray()
        val replyTo = ApplicationMessage.ReplyReference(repliedToId, "original text", repliedToWasFromReplier = true)

        val encoded = ApplicationMessage.encode("Bob", 0, messageId, body, replyTo)
        val decoded = ApplicationMessage.decode(encoded)

        assertArrayEquals(body, decoded.body)
        requireNotNull(decoded.replyTo)
        assertArrayEquals(repliedToId, decoded.replyTo!!.repliedToMessageId)
        assertEquals("original text", decoded.replyTo!!.previewText)
        assertEquals(true, decoded.replyTo!!.repliedToWasFromReplier)
    }

    @Test
    fun `long reply preview is truncated instead of overflowing the length byte`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH)
        val repliedToId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH)
        val longPreview = "x".repeat(500)
        val replyTo = ApplicationMessage.ReplyReference(repliedToId, longPreview, repliedToWasFromReplier = false)

        val encoded = ApplicationMessage.encode("Carol", 0, messageId, "body".toByteArray(), replyTo)
        val decoded = ApplicationMessage.decode(encoded)

        requireNotNull(decoded.replyTo)
        assertEquals(200, decoded.replyTo!!.previewText.length)
    }

    @Test
    fun `round-trips with a link preview including image bytes`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH)
        val imageBytes = ByteArray(1024) { it.toByte() }
        val linkPreview = ApplicationMessage.LinkPreviewRef(
            url = "https://example.com/article",
            title = "An Example Article",
            pageText = "The full readable body text of the page, archived so the recipient never has to visit it.",
            imageBytes = imageBytes,
        )

        val encoded = ApplicationMessage.encode("Dave", 0, messageId, "check this out".toByteArray(), linkPreview = linkPreview)
        val decoded = ApplicationMessage.decode(encoded)

        assertArrayEquals("check this out".toByteArray(), decoded.body)
        requireNotNull(decoded.linkPreview)
        assertEquals("https://example.com/article", decoded.linkPreview!!.url)
        assertEquals("An Example Article", decoded.linkPreview!!.title)
        assertEquals(linkPreview.pageText, decoded.linkPreview!!.pageText)
        assertArrayEquals(imageBytes, decoded.linkPreview!!.imageBytes)
    }

    @Test
    fun `link preview without an image round-trips with a null image`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH)
        val linkPreview = ApplicationMessage.LinkPreviewRef("https://example.com", "Title", "Text", imageBytes = null)

        val encoded = ApplicationMessage.encode("Eve", 0, messageId, "body".toByteArray(), linkPreview = linkPreview)
        val decoded = ApplicationMessage.decode(encoded)

        requireNotNull(decoded.linkPreview)
        assertNull(decoded.linkPreview!!.imageBytes)
    }

    @Test
    fun `a reply and a link preview coexist on the same message`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH)
        val repliedToId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH) { 1 }
        val replyTo = ApplicationMessage.ReplyReference(repliedToId, "quoted", repliedToWasFromReplier = false)
        val linkPreview = ApplicationMessage.LinkPreviewRef("https://example.com", "T", "body text", null)

        val encoded = ApplicationMessage.encode("Frank", 0, messageId, "body".toByteArray(), replyTo, linkPreview)
        val decoded = ApplicationMessage.decode(encoded)

        requireNotNull(decoded.replyTo)
        requireNotNull(decoded.linkPreview)
        assertEquals("quoted", decoded.replyTo!!.previewText)
        assertEquals("https://example.com", decoded.linkPreview!!.url)
    }

    @Test
    fun `oversized page text is rejected rather than silently truncated`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH)
        val tooLong = "x".repeat(ApplicationMessage.MAX_PREVIEW_TEXT_BYTES + 1)
        val linkPreview = ApplicationMessage.LinkPreviewRef("https://example.com", "T", tooLong, null)

        assertThrows(IllegalArgumentException::class.java) {
            ApplicationMessage.encode("Grace", 0, messageId, "body".toByteArray(), linkPreview = linkPreview)
        }
    }

    @Test
    fun `round-trips with a voice attachment`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH)
        val audioBytes = ByteArray(4096) { (it % 256).toByte() }
        val voice = ApplicationMessage.VoiceAttachmentRef(audioBytes, durationMillis = 12_345L)

        val encoded = ApplicationMessage.encode("Ivan", 0, messageId, ByteArray(0), voiceAttachment = voice)
        val decoded = ApplicationMessage.decode(encoded)

        requireNotNull(decoded.voiceAttachment)
        assertArrayEquals(audioBytes, decoded.voiceAttachment!!.audioBytes)
        assertEquals(12_345L, decoded.voiceAttachment!!.durationMillis)
    }

    @Test
    fun `a voice attachment coexists with a link preview and a reply`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH)
        val repliedToId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH) { 2 }
        val replyTo = ApplicationMessage.ReplyReference(repliedToId, "quoted", repliedToWasFromReplier = true)
        val linkPreview = ApplicationMessage.LinkPreviewRef("https://example.com", "T", "text", null)
        val voice = ApplicationMessage.VoiceAttachmentRef(ByteArray(16) { it.toByte() }, durationMillis = 500L)

        val encoded = ApplicationMessage.encode("Judy", 0, messageId, ByteArray(0), replyTo, linkPreview, voice)
        val decoded = ApplicationMessage.decode(encoded)

        requireNotNull(decoded.replyTo)
        requireNotNull(decoded.linkPreview)
        requireNotNull(decoded.voiceAttachment)
        assertEquals(500L, decoded.voiceAttachment!!.durationMillis)
    }

    @Test
    fun `oversized voice attachment is rejected`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH)
        val tooLarge = ApplicationMessage.VoiceAttachmentRef(ByteArray(ApplicationMessage.MAX_VOICE_BYTES + 1), 1000L)

        assertThrows(IllegalArgumentException::class.java) {
            ApplicationMessage.encode("Karl", 0, messageId, ByteArray(0), voiceAttachment = tooLarge)
        }
    }

    @Test
    fun `long preview title is truncated instead of overflowing the length byte`() {
        val messageId = ByteArray(ApplicationMessage.MESSAGE_ID_LENGTH)
        val longTitle = "y".repeat(500)
        val linkPreview = ApplicationMessage.LinkPreviewRef("https://example.com", longTitle, "text", null)

        val encoded = ApplicationMessage.encode("Heidi", 0, messageId, "body".toByteArray(), linkPreview = linkPreview)
        val decoded = ApplicationMessage.decode(encoded)

        requireNotNull(decoded.linkPreview)
        assertEquals(200, decoded.linkPreview!!.title.length)
    }
}
