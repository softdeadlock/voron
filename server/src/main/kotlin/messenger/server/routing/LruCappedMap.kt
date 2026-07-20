package messenger.server.routing

/**
 * An access-order [LinkedHashMap] that silently evicts its least-recently-used entry once
 * [maxSize] is exceeded — the shared cap idiom every relay-side store keyed off an
 * authenticated-but-otherwise-arbitrary device key needs (see each caller's own doc comment for
 * why). Not thread-safe by itself: callers wrap this in `Collections.synchronizedMap` or their own
 * `synchronized` blocks, since eviction is a side effect of `get`/`put` and needs the same lock as
 * whatever compound operation surrounds it.
 */
fun <K, V> lruCappedMap(maxSize: Int): LinkedHashMap<K, V> =
    object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxSize
    }
