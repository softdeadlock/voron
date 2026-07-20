package messenger.server.routing

import java.util.ArrayDeque
import java.util.Collections

/**
 * Holds fully-framed outbound bytes for devices that are offline right now,
 * so a sender doesn't silently lose a message just because the recipient
 * isn't connected. Frames are exactly what would have been pushed onto the
 * recipient's live connection — flushing on reconnect is a straight replay.
 *
 * In-memory only (lost on relay restart) and capped two ways: per device (on
 * overflow the oldest queued frame is dropped to make room for the newest)
 * and across devices (LRU-evicts the whole queue for the least-recently-
 * touched device once [maxDevices] distinct recipient keys are queued).
 * Without the second cap, any connected client can address ROUTE/CALL_SIGNAL
 * frames at an unbounded number of fabricated recipient keys — the relay
 * never requires a recipient to have actually registered anything, so this
 * queue would otherwise grow without limit.
 *
 * Used only when no `DATABASE_URL` is configured (see `Application.kt`) — with a database, the
 * Postgres-backed [messenger.server.db.SqlMailbox] is used instead, so a message queued for an
 * offline recipient survives a relay restart/redeploy rather than being lost with it.
 */
class Mailbox(private val maxPerDevice: Int = 200, maxDevices: Int = 10_000) : MailboxStore {
    private val queues = Collections.synchronizedMap(lruCappedMap<String, ArrayDeque<ByteArray>>(maxDevices))

    // SECURITY/RELIABILITY: enqueue's getOrPut and its deque mutation used to be two separate
    // critical sections (queues-lock then queue-lock). A drain() for the same device could run
    // its own queues-lock-then-queue-lock pair in between them: remove the queue from `queues`
    // (between enqueue's getOrPut and its addLast), read it out, and return -- then enqueue's
    // addLast finally runs against a deque no longer reachable from `queues`, silently losing
    // that one frame forever with no error anywhere. Both operations now hold the single
    // `queues` lock for their entire duration, closing the gap.
    override fun enqueue(deviceHex: String, frame: ByteArray) {
        synchronized(queues) {
            val queue = queues.getOrPut(deviceHex) { ArrayDeque() }
            if (queue.size >= maxPerDevice) queue.pollFirst()
            queue.addLast(frame)
        }
    }

    /** Removes and returns everything queued for [deviceHex], oldest first. */
    override fun drain(deviceHex: String): List<ByteArray> {
        synchronized(queues) {
            val queue = queues.remove(deviceHex) ?: return emptyList()
            return queue.toList()
        }
    }
}
