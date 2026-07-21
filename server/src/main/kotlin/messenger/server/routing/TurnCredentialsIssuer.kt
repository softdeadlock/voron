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

    // SECURITY (audit finding, 2026-07-21): the per-device cooldown above only bounds how fast
    // *one* device can mint -- it does nothing against many cheap, unauthenticated connections
    // (up to the relay's own connection cap) each minting once every cooldown window, which
    // aggregately can exhaust the operator's real Metered.ca account quota/cost far faster than a
    // single device spamming ever could. This is a global budget on top, independent of deviceHex.
    private val globalMintTimestamps = java.util.ArrayDeque<Long>()
    private val globalMintLock = Any()

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

        val globallyThrottled = synchronized(globalMintLock) {
            while (globalMintTimestamps.isNotEmpty() && now - globalMintTimestamps.peekFirst() > GLOBAL_BUDGET_WINDOW_MILLIS) {
                globalMintTimestamps.pollFirst()
            }
            if (globalMintTimestamps.size >= GLOBAL_BUDGET_MAX_MINTS) {
                true
            } else {
                globalMintTimestamps.addLast(now)
                false
            }
        }
        if (globallyThrottled) {
            logger.warn("throttling TURN credential mint for $deviceHex: relay-wide mint budget exhausted")
            return null
        }

        return try {
            // secretKey travels as a URL query parameter because that's Metered.ca's own documented
            // REST contract for this endpoint, not a choice made here -- a header/body alternative
            // isn't offered. That means it's exactly as exposed as this request URL is to whatever
            // sits on the path to metered.live (their own access logs, any CDN/proxy in front of
            // them) -- this relay's own HTTP client has no logging plugin installed, so it never
            // writes the URL anywhere itself. Residual risk accepted, not something fixable from
            // this side without Metered.ca changing their API.
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
        // 120/minute is comfortably above any real deployment's concurrent-call rate (each call
        // mints once) while still capping worst-case cost if every connection slot tried at once.
        const val GLOBAL_BUDGET_MAX_MINTS = 120
        const val GLOBAL_BUDGET_WINDOW_MILLIS = 60_000L
    }
}
