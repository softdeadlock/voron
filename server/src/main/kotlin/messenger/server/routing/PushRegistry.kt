package messenger.server.routing

import java.util.Collections

/** Longer than any real UnifiedPush endpoint needs — just a backstop against a client registering an oversized string. */
private const val MAX_ENDPOINT_URL_LENGTH = 2048
private const val MAX_DEVICES = 10_000

/**
 * Maps a device's Noise static key (hex) to the UnifiedPush endpoint URL it last registered, so
 * [routeMessage]/[routeCallSignal] know where to send a wakeup POST when mailboxing a frame for
 * an offline device. In-memory only (lost on relay restart) — used when no `DATABASE_URL` is
 * configured, otherwise [messenger.server.db.SqlPushRegistry] is used instead. A device
 * re-registers on every fresh connect either way, same story as [PreKeyDirectory].
 *
 * LRU-capped at [MAX_DEVICES] distinct keys, same reasoning as [Mailbox]: an authenticated device
 * can be any fabricated key the relay never required to have registered anything else, so without
 * a cap this map would grow without bound. Oversized URLs are rejected outright rather than
 * truncated (a truncated URL is just a different, wrong URL, not a safe smaller one).
 */
class PushRegistry : PushEndpointStore {
    private val endpoints = Collections.synchronizedMap(lruCappedMap<String, String>(MAX_DEVICES))

    override fun register(deviceHex: String, endpointUrl: String) {
        when {
            endpointUrl.isEmpty() -> endpoints.remove(deviceHex)
            endpointUrl.length > MAX_ENDPOINT_URL_LENGTH -> endpoints.remove(deviceHex)
            else -> endpoints[deviceHex] = endpointUrl
        }
    }

    override fun lookup(deviceHex: String): String? = endpoints[deviceHex]
}
