package messenger.server.routing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MailboxTest {

    @Test
    fun `drain on an empty mailbox returns nothing`() {
        assertTrue(Mailbox().drain("nobody").isEmpty())
    }

    @Test
    fun `queued frames are returned in order and only once`() {
        val mailbox = Mailbox()
        mailbox.enqueue("alice", "one".toByteArray())
        mailbox.enqueue("alice", "two".toByteArray())
        mailbox.enqueue("bob", "for bob".toByteArray())

        val aliceQueue = mailbox.drain("alice")
        assertEquals(2, aliceQueue.size)
        assertArrayEquals("one".toByteArray(), aliceQueue[0])
        assertArrayEquals("two".toByteArray(), aliceQueue[1])

        assertTrue(mailbox.drain("alice").isEmpty())
        assertEquals(1, mailbox.drain("bob").size)
    }

    @Test
    fun `overflow drops the oldest frame`() {
        val mailbox = Mailbox(maxPerDevice = 3)
        repeat(5) { mailbox.enqueue("alice", it.toString().toByteArray()) }

        val queue = mailbox.drain("alice")
        assertEquals(3, queue.size)
        assertArrayEquals("2".toByteArray(), queue[0])
        assertArrayEquals("3".toByteArray(), queue[1])
        assertArrayEquals("4".toByteArray(), queue[2])
    }

    /**
     * Regression test: enqueue() used to grab-or-create the device's queue under one lock, then
     * add the frame to it under a *different* (per-queue) lock -- a concurrent drain() for the
     * same device could remove the queue from the map in between those two steps, so the
     * about-to-be-added frame ended up appended to a deque no longer reachable from the map,
     * silently lost forever. Fixed by holding a single lock across the whole compound operation
     * on both sides. This hammers enqueue/drain concurrently on one device (well under
     * maxPerDevice, so nothing is *expected* to be dropped by the overflow cap) and asserts every
     * enqueued frame surfaces exactly once across all the drains.
     */
    @Test
    fun `concurrent enqueue and drain on the same device never silently drops a frame`() {
        val mailbox = Mailbox(maxPerDevice = 10_000)
        val producerCount = 8
        val framesPerProducer = 500
        val expected = ConcurrentHashMap.newKeySet<String>()
        val drained = ConcurrentHashMap.newKeySet<String>()
        val stopDraining = java.util.concurrent.atomic.AtomicBoolean(false)
        val startLatch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(producerCount + 1)

        val drainer = pool.submit {
            startLatch.await()
            while (!stopDraining.get()) {
                mailbox.drain("device").forEach { drained.add(String(it)) }
            }
            // Final sweep after producers are done, for anything still sitting in the queue.
            mailbox.drain("device").forEach { drained.add(String(it)) }
        }

        val producers = (0 until producerCount).map { producerIndex ->
            pool.submit {
                startLatch.await()
                repeat(framesPerProducer) { i ->
                    val tag = "p$producerIndex-f$i"
                    expected.add(tag)
                    mailbox.enqueue("device", tag.toByteArray())
                }
            }
        }

        startLatch.countDown()
        producers.forEach { it.get(30, TimeUnit.SECONDS) }
        stopDraining.set(true)
        drainer.get(30, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals(expected, drained, "every enqueued frame must be drained exactly once, none lost")
    }
}
