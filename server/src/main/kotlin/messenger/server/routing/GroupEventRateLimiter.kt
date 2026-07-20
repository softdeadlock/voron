package messenger.server.routing

import java.util.Collections

/**
 * Per-device sliding-window cap on group *control* traffic — [GROUP_CONTROL_EVENT] carries
 * membership changes (add/remove member, epoch bumps) that every other member's
 * [messenger.common.group.GroupControlLog] has to replay and re-validate, and [GROUP_JOIN_REQUEST]
 * triggers a signature-verification pass on the receiving device. Neither is free to process, and
 * the relay fans both out to every group member — so one misbehaving or compromised device
 * spamming either frame type is a cheap way to burn every other member's CPU and, for control
 * events, spam their control-log UI with churn. Ordinary [GROUP_MESSAGE]/[GROUP_SENDER_KEY]
 * traffic is left alone: that's normal chat volume, not administrative churn.
 */
class GroupEventRateLimiter(private val maxEvents: Int = 20, private val windowMillis: Long = 10_000L) {
    private val buckets = Collections.synchronizedMap(lruCappedMap<String, Bucket>(10_000))

    private class Bucket(var windowStart: Long, var count: Int)

    fun allow(deviceHex: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(buckets) {
            val bucket = buckets.getOrPut(deviceHex) { Bucket(now, 0) }
            if (now - bucket.windowStart >= windowMillis) {
                bucket.windowStart = now
                bucket.count = 0
            }
            bucket.count++
            return bucket.count <= maxEvents
        }
    }
}
