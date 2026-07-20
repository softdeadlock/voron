package messenger.server.routing

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.util.Collections
import messenger.common.e2ee.TurnCredentials
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("messenger.server.routing.TurnCredentialsIssuer")

/**
 * Mints a fresh, short-lived TURN username/password from Metered.ca's REST API on every call —
 * see [TransportFrame][messenger.common.protocol.TransportFrame.TURN_CREDENTIALS_REQUEST]'s doc for
 * why this exists at all: a device must never carry a TURN credential that outlives one call
 * attempt, so the relay (the only party holding [secretKey], an env var never shipped to any
 * client — see `Application.kt`) is the one that talks to Metered.ca, not the app. STUN/TURN server
 * *hostnames* aren't secret and stay hardcoded client-side same as before; only the auth
 * credentials are minted here.
 */
class TurnCredentialsIssuer(private val appName: String, private val secretKey: String) {
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
        }
    }

    // Minting is a real Metered.ca API call against the account's own quota, so a device spamming
    // TURN_CREDENTIALS_REQUEST shouldn't be able to burn through it — one real call needs at most
    // one set of credentials. Same LRU-capped-map idiom as Mailbox/PushNotifier's own throttles.
    private val lastMintedAt = Collections.synchronizedMap(lruCappedMap<String, Long>(10_000))

    suspend fun mint(deviceHex: String): TurnCredentials? {
        val now = System.currentTimeMillis()
        val throttled = synchronized(lastMintedAt) {
            val last = lastMintedAt[deviceHex]
            if (last != null && now - last < MINT_COOLDOWN_MILLIS) {
                true
            } else {
                lastMintedAt[deviceHex] = now
                false
            }
        }
        if (throttled) {
            logger.info("throttling TURN credential mint for $deviceHex (asked again too soon)")
            return null
        }

        return try {
            val response = httpClient.post("https://$appName.metered.live/api/v1/turn/credential") {
                url { parameters.append("secretKey", secretKey) }
                contentType(ContentType.Application.Json)
                // deviceHex is always a hex string (from Connection.staticPublicKeyHex) -- safe to
                // embed directly in this JSON literal, no quote/backslash characters possible.
                setBody("""{"expiryInSeconds":$CREDENTIAL_LIFETIME_SECONDS,"label":"$deviceHex"}""")
            }
            val body = response.bodyAsText()
            val username = Regex(""""username"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
            val password = Regex(""""password"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
            if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
                logger.warn("Metered.ca credential response missing username/password")
                null
            } else {
                TurnCredentials(username, password)
            }
        } catch (e: Exception) {
            logger.warn("failed to mint TURN credentials from Metered.ca: ${e.message}")
            null
        }
    }

    private companion object {
        // Long enough for a call's whole ICE gathering/negotiation, short enough that a leaked
        // credential (e.g. sniffed from the wire, though this channel is Noise-encrypted) is
        // useless well before anyone could act on it.
        const val CREDENTIAL_LIFETIME_SECONDS = 600
        const val MINT_COOLDOWN_MILLIS = 5_000L
    }
}
