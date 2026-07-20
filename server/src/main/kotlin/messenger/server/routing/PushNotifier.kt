package messenger.server.routing

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("messenger.server.routing.PushNotifier")

private const val NOTIFY_COOLDOWN_MILLIS = 3_000L

/**
 * Fires a best-effort, fire-and-forget wakeup POST at a UnifiedPush distributor endpoint whenever
 * a frame gets mailboxed for a device that registered one. The POST body is empty — the push
 * carries no message content, it only needs to wake the device's distributor so the app can
 * reconnect to the relay and drain its own mailbox itself. A slow or unreachable distributor must
 * never block the caller's own read loop, so [notifyAsync] launches on its own scope rather than
 * suspending.
 *
 * [endpointUrl] is a client-supplied, unauthenticated string ([PushRegistry] only proves the
 * *device*, not the URL) — without [isSafePublicHttpsUrl], any authenticated device could point
 * this at an internal address (cloud metadata endpoint, localhost admin panel, another host on the
 * relay's own network) and use the relay as an SSRF proxy by having a second device of their own
 * message the (deliberately offline) registered key on demand.
 */
class PushNotifier {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
        }
    }

    // Bounds how often the *same* endpoint gets POSTed to, independent of how many frames land in
    // the mailbox behind it — otherwise a device messaging its own (offline) registered key in a
    // tight loop turns the relay into an anonymous request amplifier against whatever endpoint it
    // registered. Unbounded like Mailbox/PushRegistry's own concern, so capped the same LRU way.
    private val lastNotifiedAt = lruCappedMap<String, Long>(10_000)

    fun notifyAsync(endpointUrl: String) {
        if (!isSafePublicHttpsUrl(endpointUrl)) {
            logger.warn("refusing push wakeup to non-public/non-https endpoint (SSRF guard)")
            return
        }
        val now = System.currentTimeMillis()
        val shouldSkip = synchronized(lastNotifiedAt) {
            val last = lastNotifiedAt[endpointUrl]
            if (last != null && now - last < NOTIFY_COOLDOWN_MILLIS) {
                true
            } else {
                lastNotifiedAt[endpointUrl] = now
                false
            }
        }
        if (shouldSkip) return

        scope.launch {
            try {
                client.post(endpointUrl)
            } catch (e: Exception) {
                logger.info("push wakeup POST to distributor failed (best-effort, ignoring): ${e.message}")
            }
        }
    }
}

/**
 * Requires `https` and resolves the host to make sure none of its addresses land in a
 * private/loopback/link-local/reserved range — re-checked on every call (not just at
 * registration time) since a distributor's DNS could rebind between registration and wakeup.
 */
private fun isSafePublicHttpsUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (e: Exception) {
        return false
    }
    if (uri.scheme != "https") return false
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return false
    return try {
        InetAddress.getAllByName(host).all { addr -> !isPrivateOrReserved(addr) }
    } catch (e: Exception) {
        false
    }
}

private fun isPrivateOrReserved(addr: InetAddress): Boolean {
    if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
        addr.isAnyLocalAddress || addr.isMulticastAddress
    ) {
        return true
    }
    // java.net's isSiteLocalAddress only recognizes the deprecated IPv6 site-local range
    // (fec0::/10), not the modern Unique Local Address range (fc00::/7) — check that ourselves.
    val bytes = addr.address
    if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return true
    return false
}
