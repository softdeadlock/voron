package messenger.android.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/** The relay's warrant canary (see `Routes.kt`'s `/v1/canary`) — a manually-renewed statement, not a cryptographic guarantee; see [CanarySheet][messenger.android.ui.screens.CanarySheet] for how it's presented. */
data class CanaryInfo(val statement: String, val asOfMillis: Long)

/** Fetches the relay's current warrant canary. Same lightweight regex-parsing style as [UpdateManager] rather than pulling in a JSON library for one small, fixed-shape response. */
class CanaryFetcher(private val httpClient: HttpClient) {
    suspend fun fetch(): CanaryInfo? = try {
        val body = httpClient.get("${RelayConfig.HTTP_SCHEME}://${RelayConfig.HOST}/v1/canary").bodyAsText()
        val statement = Regex(""""statement"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)?.groupValues?.get(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
        val asOfMillis = Regex(""""asOfMillis"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toLongOrNull()
        if (statement == null || asOfMillis == null) null else CanaryInfo(statement, asOfMillis)
    } catch (e: Exception) {
        null
    }
}
