package messenger.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import messenger.common.client.ApplicationMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

private const val TAG = "VoronLinkPreview"
private const val MAX_HTML_DOWNLOAD_BYTES = 3L * 1024 * 1024
private const val MAX_IMAGE_DOWNLOAD_BYTES = 6L * 1024 * 1024
private const val THUMBNAIL_MAX_DIMEN_PX = 480
private val URL_REGEX = Regex("""https?://\S+""")

/**
 * Fetches and archives a link preview entirely on the *sender's* device — see
 * [ApplicationMessage.LinkPreviewRef] for why the recipient must never make this request itself.
 * [pageText] is a plain-text reader-mode extraction of the page's main content (script/style/nav
 * stripped), not just a one-line summary, per the user's request to be able to read what's being
 * shared without ever visiting the site — capped at [ApplicationMessage.MAX_PREVIEW_TEXT_BYTES].
 */
class LinkPreviewFetcher(private val appContext: Context) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** First http(s) URL in freely-typed text, or null — drives the compose-bar auto-banner. */
    fun firstUrl(text: String): String? = URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')

    suspend fun fetch(url: String): ApplicationMessage.LinkPreviewRef? = withContext(Dispatchers.IO) {
        try {
            val html = downloadText(url) ?: return@withContext null
            val doc = Jsoup.parse(html, url)
            val ogTitle = doc.select("meta[property=og:title]").attr("content")
            val title = ogTitle.ifBlank { doc.title() }.ifBlank { url }
            val pageText = extractReadableText(doc)
            val imageUrl = doc.select("meta[property=og:image]").attr("content").ifBlank { null }
            val imageBytes = imageUrl?.let { runCatching { downloadThumbnail(it) }.getOrNull() }
            ApplicationMessage.LinkPreviewRef(url = url, title = title, pageText = pageText, imageBytes = imageBytes)
        } catch (e: Exception) {
            VoronLog.w(TAG, "link preview fetch failed for $url", e)
            null
        }
    }

    /** Saves a received preview's thumbnail bytes to local storage, same directory [FileTransferManager] uses for attachments. Returns the local path. */
    fun saveThumbnail(imageBytes: ByteArray): String {
        val dir = File(appContext.filesDir, "media").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}_preview.jpg")
        file.writeBytes(imageBytes)
        return file.absolutePath
    }

    private fun extractReadableText(doc: org.jsoup.nodes.Document): String {
        doc.select("script, style, nav, header, footer, aside, noscript, form, iframe, svg").remove()
        val text = doc.body()?.text().orEmpty()
        return truncateUtf8(text, ApplicationMessage.MAX_PREVIEW_TEXT_BYTES)
    }

    private fun downloadText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) VoronLinkPreview/1.0")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val bytes = body.byteStream().readAtMost(MAX_HTML_DOWNLOAD_BYTES)
            return String(bytes, Charsets.UTF_8)
        }
    }

    private fun downloadThumbnail(imageUrl: String): ByteArray? {
        val request = Request.Builder().url(imageUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val raw = body.byteStream().readAtMost(MAX_IMAGE_DOWNLOAD_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > THUMBNAIL_MAX_DIMEN_PX) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            try {
                // Steps quality down until it fits the wire cap rather than failing outright —
                // most og:images comfortably fit at quality 80 after downsampling; a few dense
                // photos need a couple more passes.
                var quality = 80
                var compressed: ByteArray
                do {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    compressed = out.toByteArray()
                    quality -= 15
                } while (compressed.size > ApplicationMessage.MAX_PREVIEW_IMAGE_BYTES && quality > 20)
                return if (compressed.size <= ApplicationMessage.MAX_PREVIEW_IMAGE_BYTES) compressed else null
            } finally {
                bitmap.recycle()
            }
        }
    }
}

private fun InputStream.readAtMost(maxBytes: Long): ByteArray {
    val buffer = ByteArray(16 * 1024)
    val out = ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n == -1) break
        total += n
        if (total > maxBytes) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** Trims [text] to at most [maxBytes] once UTF-8 encoded without splitting a multi-byte character. */
private fun truncateUtf8(text: String, maxBytes: Int): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return text
    var end = maxBytes
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return String(bytes, 0, end, Charsets.UTF_8)
}
