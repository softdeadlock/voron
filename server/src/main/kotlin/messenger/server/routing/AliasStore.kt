package messenger.server.routing

import java.util.Collections

/**
 * Maps rotating routing aliases (random 32-byte tokens a client registers for itself, see
 * [TransportFrame.REGISTER_ALIAS][messenger.common.protocol.TransportFrame.REGISTER_ALIAS]) to
 * the real static device key they currently resolve to, so [rewrapForRecipient] can address
 * [ROUTE]-family frames by alias without the relay ever needing to persist which alias belongs to
 * which identity anywhere else -- this table is the only place that link exists, in memory, and
 * expires on its own.
 *
 * Deliberately in-memory only, like [ConnectionRegistry] and the prekey directory: an alias
 * surviving a relay restart isn't a guarantee this system makes, and every alias is re-registered
 * every rotation anyway.
 *
 * SECURITY (audit finding, 2026-07-21): a completing Noise_IK handshake is cheap and requires no
 * allowlist (same reasoning as [PreKeyDirectory]'s own cap), so without a size bound here,
 * unlimited REGISTER_ALIAS spam grows this map -- and the JVM heap behind it -- without bound.
 * LRU-capped the same way [Mailbox]/[PreKeyDirectory]/[GroupEventRateLimiter.buckets] already are;
 * [Routes.kt][registerAlias] additionally rate-limits how *fast* one device can register aliases.
 */
class AliasStore(private val ttlMillis: Long = DEFAULT_TTL_MILLIS, maxAliases: Int = 50_000) {
    private data class Entry(val deviceKeyHex: String, val expiresAtMillis: Long)

    private val aliases = Collections.synchronizedMap(lruCappedMap<String, Entry>(maxAliases))

    /** Registers (or refreshes) [aliasHex] as currently resolving to [deviceKeyHex], superseding whatever alias(es) that device had registered before is left to the caller -- multiple live aliases per device are harmless, just extra memory, and simplify rotation races. */
    fun register(aliasHex: String, deviceKeyHex: String) {
        aliases[aliasHex] = Entry(deviceKeyHex, System.currentTimeMillis() + ttlMillis)
    }

    /** Resolves [hex] to the real device key it's currently aliasing, or null if it isn't a live alias (the common case: [hex] is already a real static key, the legacy/fallback addressing path). */
    fun resolve(hex: String): String? {
        val entry = aliases[hex] ?: return null
        if (entry.expiresAtMillis < System.currentTimeMillis()) {
            aliases.remove(hex, entry)
            return null
        }
        return entry.deviceKeyHex
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 48L * 60 * 60 * 1000
    }
}
