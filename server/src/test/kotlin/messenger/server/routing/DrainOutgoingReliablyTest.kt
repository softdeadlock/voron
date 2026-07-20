package messenger.server.routing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import messenger.common.transport.NoiseIkInitiatorHandshake
import messenger.common.transport.NoiseIkResponderHandshake
import messenger.common.transport.NoiseStaticKeyPair
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the mailbox-loses-in-flight-frames bug: a client disconnecting (or a
 * socket write failing) while [Connection.outgoing] still has buffered/in-flight frames used to
 * silently drop them instead of putting them back in the mailbox for the next connection attempt.
 */
class DrainOutgoingReliablyTest {

    /** [drainOutgoingReliably] never touches `transportSession` itself, so any valid one will do. */
    private fun testConnection(): Connection {
        val relayIdentity = NoiseStaticKeyPair.generate()
        val clientIdentity = NoiseStaticKeyPair.generate()
        val initiator = NoiseIkInitiatorHandshake(clientIdentity, relayIdentity.publicKey)
        val responder = NoiseIkResponderHandshake(relayIdentity)
        responder.consumeMessage1(initiator.createMessage1())
        val (_, transportSession) = responder.createMessage2()
        return Connection(staticPublicKeyHex = "deadbeef", transportSession = transportSession)
    }

    @Test
    fun `a clean drain with no failures sends everything and leaves the mailbox empty`() {
        val connection = testConnection()
        val mailbox = Mailbox()
        connection.outgoing.trySend("one".toByteArray())
        connection.outgoing.trySend("two".toByteArray())
        connection.outgoing.close()

        val sentFrames = mutableListOf<String>()
        runBlocking {
            drainOutgoingReliably(connection, mailbox) { frame -> sentFrames.add(frame.decodeToString()) }
        }

        assertEquals(listOf("one", "two"), sentFrames)
        assertTrue(mailbox.drain("deadbeef").isEmpty())
    }

    @Test
    fun `a frame that fails mid-send is put back in the mailbox, along with anything still buffered`() {
        val connection = testConnection()
        val mailbox = Mailbox()
        connection.outgoing.trySend("one".toByteArray())
        connection.outgoing.trySend("two".toByteArray())
        connection.outgoing.trySend("three".toByteArray())

        runBlocking {
            var sent = 0
            runCatching {
                drainOutgoingReliably(connection, mailbox) { frame ->
                    sent++
                    if (sent == 2) throw RuntimeException("socket write failed")
                }
            }
        }

        val requeued = mailbox.drain("deadbeef")
        assertEquals(2, requeued.size)
        assertArrayEquals("two".toByteArray(), requeued[0])
        assertArrayEquals("three".toByteArray(), requeued[1])
    }

    @Test
    fun `cancellation mid-send (the client disconnecting) re-queues the in-flight frame and anything still buffered`() = runBlocking {
        val connection = testConnection()
        val mailbox = Mailbox()
        connection.outgoing.trySend("one".toByteArray())
        connection.outgoing.trySend("two".toByteArray())
        connection.outgoing.trySend("three".toByteArray())

        val reachedSecondFrame = CompletableDeferred<Unit>()
        val writer = launch {
            drainOutgoingReliably(connection, mailbox) { frame ->
                // "one" sends instantly; "two" simulates a socket write that's still in flight
                // when the client disconnects, hanging until this coroutine is cancelled from
                // outside — exactly what `writer.cancel()` in Routes.kt does on every disconnect.
                if (frame.decodeToString() == "two") {
                    reachedSecondFrame.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        reachedSecondFrame.await()
        writer.cancel()
        writer.join()

        val requeued = mailbox.drain("deadbeef")
        assertEquals(2, requeued.size)
        assertArrayEquals("two".toByteArray(), requeued[0])
        assertArrayEquals("three".toByteArray(), requeued[1])
    }
}
