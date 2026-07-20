package messenger.android.data

/** The fixed 3-hop onion path (entry, guard, middle) clients tunnel their relay connection through when onion routing is on — the relay itself (see [RelayConfig]) is always the exit. [ENTRY_HOST] is a plain forwarding-only hop identical in code to guard/middle, deployed in front of them: a passive adversary now needs a foothold on three independent network links/hosts (not two) to correlate a client's traffic end-to-end, raising the cost of the traffic-analysis attack this same circuit's per-frame padding (see [messenger.common.onion.OnionCircuit]) already closes for size-only correlation. */
object OnionConfig {
    const val ENTRY_HOST = "voron-onion-entry.onrender.com"
    const val GUARD_HOST = "voron-onion-guard.onrender.com"
    const val MIDDLE_HOST = "voron-onion-middle.onrender.com"
    const val HTTP_SCHEME = "https"
    const val WS_SCHEME = "wss"
}
