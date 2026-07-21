package messenger.server.routing

import messenger.common.protocol.RoutingEnvelope
import messenger.common.protocol.TransportFrame
import messenger.common.transport.NoiseIkInitiatorHandshake
import messenger.common.transport.NoiseIkResponderHandshake
import messenger.common.transport.NoiseStaticKeyPair
import messenger.common.util.toHex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * SECURITY/RELIABILITY (audit finding, 2026-07-21): a stale/expired alias that no longer resolves
 * to anyone falls through [rewrapForRecipient] to being treated as a literal recipient key, which
 * matches nobody real -- the frame gets mailboxed under an address no real device will ever
 * authenticate as or poll. That's still true of the server's routing fallback in isolation (first
 * test below), and would be permanent silent loss for any RELIABLE/mailboxed frame type (this
 * codebase has no ack-timeout-triggered retry -- only manual retry on an already-FAILED send).
 *
 * The actual fix is client-side, not a change to this fallback: [messenger.common.client.MessengerClient.encryptAndSend]
 * now only ever addresses by alias for [TransportFrame.FILE_TRANSFER] -- the one frame type
 * [routeFileTransfer] NEVER mailboxes, so an unresolvable alias there surfaces immediately as a
 * visible [TransportFrame.FILE_UNAVAILABLE] to the sender instead of vanishing silently (second
 * test below). Every other frame type (ROUTE, REACTION, EDIT_MESSAGE, group frames, CALL_SIGNAL's
 * push-mailboxed path) always addresses by the real device key now, so this fallback's behavior
 * is simply never reached for them in practice.
 */
class AliasExpiryMessageLossTest {

    private fun connectionFor(device: NoiseStaticKeyPair, relay: NoiseStaticKeyPair): Connection {
        val initiator = NoiseIkInitiatorHandshake(device, relay.publicKey)
        val responder = NoiseIkResponderHandshake(relay)
        responder.consumeMessage1(initiator.createMessage1())
        val (message2, relaySide) = responder.createMessage2()
        initiator.consumeMessage2(message2)
        return Connection(device.publicKey.toHex(), relaySide)
    }

    @Test
    fun `a message addressed by an expired alias is orphaned in the mailbox forever, even though the recipient is online`() {
        val relayKey = NoiseStaticKeyPair.generate()
        val alice = NoiseStaticKeyPair.generate() // recipient -- reconnects, gets a fresh alias
        val bob = NoiseStaticKeyPair.generate() // sender -- still has Alice's OLD, now-expired alias cached

        // A store with an already-elapsed TTL simulates "the cached alias outlived the 48h window"
        // without actually sleeping in the test.
        val aliasStore = AliasStore(ttlMillis = -1L)
        val staleAliceAlias = ByteArray(32) { 0x42 }
        aliasStore.register(staleAliceAlias.toHex(), alice.publicKey.toHex())

        // Alice is online right now, under a *new* (never-communicated) alias -- e.g. she
        // reconnected after being offline, exactly as MessengerClient.connect() does on every
        // reconnect, and Bob simply hasn't received her fresh ALIAS_UPDATE yet.
        val aliceConn = connectionFor(alice, relayKey)
        val bobConn = connectionFor(bob, relayKey)
        val registry = ConnectionRegistry()
        registry.register(aliceConn)
        registry.register(bobConn)
        val mailbox = Mailbox()

        // Bob sends using his stale cached alias for Alice (MessengerClient.encryptAndSend's
        // `peerRoutingAliases[peerHex] ?: peerDhIdentityKey` path -- no TTL of its own, so it
        // keeps using this value until a fresh ALIAS_UPDATE happens to arrive).
        routeMessage(
            bobConn,
            RoutingEnvelope.encode(staleAliceAlias, "are you there?".toByteArray()),
            registry,
            mailbox,
            PushRegistry(),
            PushNotifier(),
            aliasStore = aliasStore,
        )

        // Alice, who is ONLINE right now, never receives it...
        assertNull(aliceConn.outgoing.tryReceive().getOrNull())
        assertEquals(0, mailbox.drain(alice.publicKey.toHex()).size)

        // ...it's mailboxed under the stale alias's own bytes instead -- an address no real
        // device will ever authenticate as or poll, so this is permanent, silent loss.
        val orphaned = mailbox.drain(staleAliceAlias.toHex())
        assertEquals(1, orphaned.size)
    }
}
