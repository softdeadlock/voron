package messenger.common.onion

import kotlin.random.Random
import messenger.common.crypto.Aead
import messenger.common.crypto.Hkdf
import messenger.common.crypto.X25519
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [OnionCircuit] tests, including a regression test for the traffic-correlation gap found by
 * `OnionTrafficCorrelationExploit`/`correlate_onion_traffic.py`: an unpadded circuit let a passive
 * observer at both ends deanonymize a connection purely from frame sizes, without decrypting
 * anything. Every frame is now padded to a fixed bucket (see [OnionCircuit]'s own docs) before it
 * ever reaches the AEAD layers, so distinct plaintext sizes that fall in the same bucket produce
 * identical on-wire sizes at every hop of the circuit, not just the final one.
 *
 * [FakeHop] plays the role [messenger.server.routing.configureOnionNode] would in real life --
 * deriving the same shared secret from the client's ephemeral public key and its own static
 * private key (DH is symmetric) and peeling/wrapping exactly one layer -- without spinning up a
 * real Ktor server, since the server module isn't a dependency of `common`'s tests.
 */
class OnionCircuitTest {

    private class FakeHop(val info: OnionNodeInfo, private val staticPrivateKey: ByteArray) {
        fun deriveSharedSecret(clientEphemeralPublic: ByteArray): ByteArray = X25519.dh(staticPrivateKey, clientEphemeralPublic)
    }

    private fun fakeHop(): FakeHop {
        val keyPair = X25519.generateKeyPair()
        return FakeHop(OnionNodeInfo(wsUrl = "wss://example.invalid/v1/onion-relay", staticPublicKey = keyPair.publicKey), keyPair.privateKey)
    }

    private fun clientEphemeralFrom(circuit: OnionCircuit): ByteArray {
        val query = circuit.entryWsUrl.substringAfter("?ek=")
        return java.util.Base64.getDecoder().decode(query)
    }

    /** Peels one hop's layer off an outbound (client -> exit) frame, exactly like the server side of [messenger.server.routing.configureOnionNode] does. */
    private fun peelOutbound(hop: FakeHop, clientEphemeralPublic: ByteArray, frame: ByteArray, counter: Long): ByteArray {
        val shared = hop.deriveSharedSecret(clientEphemeralPublic)
        val peelKey = Hkdf.derive(shared, "voron-onion-client-to-exit", Aead.KEY_LENGTH)
        return Aead.decrypt(peelKey, Aead.counterNonce(counter), ByteArray(0), frame)
    }

    /** Wraps a reply (exit -> client) frame in one hop's layer, exactly like the server side does on the way back. */
    private fun wrapInbound(hop: FakeHop, clientEphemeralPublic: ByteArray, frame: ByteArray, counter: Long): ByteArray {
        val shared = hop.deriveSharedSecret(clientEphemeralPublic)
        val wrapKey = Hkdf.derive(shared, "voron-onion-exit-to-client", Aead.KEY_LENGTH)
        return Aead.encrypt(wrapKey, Aead.counterNonce(counter), ByteArray(0), frame)
    }

    @Test
    fun `a 2-hop circuit round-trips outbound plaintext through both simulated hops down to the real payload`() {
        val guard = fakeHop()
        val middle = fakeHop()
        val circuit = OnionCircuit.build(guard.info, middle.info)
        val ephemeral = clientEphemeralFrom(circuit)

        val plaintext = "hello real relay".toByteArray()
        val wrapped = circuit.encodeOutgoing(plaintext)

        val afterGuard = peelOutbound(guard, ephemeral, wrapped, counter = 0)
        val afterMiddle = peelOutbound(middle, ephemeral, afterGuard, counter = 0)
        val realLength = java.nio.ByteBuffer.wrap(afterMiddle, 0, 4).int
        val realPayload = afterMiddle.copyOfRange(4, 4 + realLength)

        assertArrayEquals(plaintext, realPayload)
    }

    @Test
    fun `a 3-hop circuit round-trips outbound plaintext through all three simulated hops`() {
        val entry = fakeHop()
        val guard = fakeHop()
        val middle = fakeHop()
        val circuit = OnionCircuit.build(listOf(entry.info, guard.info, middle.info))
        val ephemeral = clientEphemeralFrom(circuit)

        val plaintext = Random.nextBytes(3000)
        val wrapped = circuit.encodeOutgoing(plaintext)

        val afterEntry = peelOutbound(entry, ephemeral, wrapped, counter = 0)
        val afterGuard = peelOutbound(guard, ephemeral, afterEntry, counter = 0)
        val afterMiddle = peelOutbound(middle, ephemeral, afterGuard, counter = 0)
        val realLength = java.nio.ByteBuffer.wrap(afterMiddle, 0, 4).int
        val realPayload = afterMiddle.copyOfRange(4, 4 + realLength)

        assertArrayEquals(plaintext, realPayload)
    }

    @Test
    fun `an inbound reply round-trips back through decodeIncoming across two hops`() {
        val guard = fakeHop()
        val middle = fakeHop()
        val circuit = OnionCircuit.build(guard.info, middle.info)
        val ephemeral = clientEphemeralFrom(circuit)

        val realReply = "reply from the real relay".toByteArray()
        val padded = ByteArray(4 + realReply.size)
        java.nio.ByteBuffer.wrap(padded).putInt(realReply.size)
        System.arraycopy(realReply, 0, padded, 4, realReply.size)

        // Middle wraps first (closest to the exit), then guard wraps again on top -- mirroring
        // OnionNodeRoute's fromNextHop loop at each hop.
        val afterMiddle = wrapInbound(middle, ephemeral, padded, counter = 0)
        val afterGuard = wrapInbound(guard, ephemeral, afterMiddle, counter = 0)

        val decoded = circuit.decodeIncoming(afterGuard)

        assertArrayEquals(realReply, decoded)
    }

    @Test
    fun `frames of different plaintext sizes that fall in the same bucket produce identical wire sizes`() {
        val circuit = OnionCircuit.build(fakeHop().info, fakeHop().info)

        val small = circuit.encodeOutgoing(ByteArray(10))
        val alsoSmall = circuit.encodeOutgoing(ByteArray(200))

        assertEquals(small.size, alsoSmall.size)
    }

    @Test
    fun `frames big enough to cross a bucket boundary produce different wire sizes`() {
        val circuit = OnionCircuit.build(fakeHop().info, fakeHop().info)

        val fitsSmallBucket = circuit.encodeOutgoing(ByteArray(100))
        val needsBiggerBucket = circuit.encodeOutgoing(ByteArray(2000))

        assertTrue(needsBiggerBucket.size > fitsSmallBucket.size)
    }

    @Test
    fun `an overflow-sized frame beyond the largest bucket still round-trips`() {
        val guard = fakeHop()
        val middle = fakeHop()
        val circuit = OnionCircuit.build(guard.info, middle.info)
        val ephemeral = clientEphemeralFrom(circuit)

        val plaintext = Random.nextBytes(400_000)
        val wrapped = circuit.encodeOutgoing(plaintext)

        val afterGuard = peelOutbound(guard, ephemeral, wrapped, counter = 0)
        val afterMiddle = peelOutbound(middle, ephemeral, afterGuard, counter = 0)
        val realLength = java.nio.ByteBuffer.wrap(afterMiddle, 0, 4).int
        val realPayload = afterMiddle.copyOfRange(4, 4 + realLength)

        assertArrayEquals(plaintext, realPayload)
    }
}
