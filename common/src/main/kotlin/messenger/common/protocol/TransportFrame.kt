package messenger.common.protocol

/**
 * The plaintext that travels inside a Noise transport session between a
 * client and the relay. A one-byte type tag selects between message routing
 * and prekey-directory control operations, so a single authenticated
 * channel carries everything.
 *
 * Bodies are opaque to this codec; each type documents its own layout:
 *  - [ROUTE]: body is a [RoutingEnvelope] (peer key + E2E-opaque payload).
 *  - [PUBLISH_PREKEYS]: body is `PreKeyCodec.encodePublished(...)`.
 *  - [FETCH_PREKEYS]: body is a 32-byte target device DH identity key.
 *  - [PREKEYS_RESULT]: relay -> client; body is [FOUND][bundle] or [NOT_FOUND].
 *  - [SESSION_RESET_NOTICE]: body is a [RoutingEnvelope] (target/sender key +
 *    empty payload), relayed exactly like [ROUTE]. Sent by a client that
 *    received an E2eeMessage.Normal it has no session for (the sender's
 *    session predates a local session loss, e.g. a reinstall) so the
 *    original sender can drop its now-stale session and re-initiate X3DH.
 *  - [DELIVERY_ACK]: body is a [RoutingEnvelope] (original sender's key +
 *    the message ID being acknowledged), relayed like [ROUTE]. Sent
 *    automatically once a message decrypts successfully, so the sender can
 *    show delivered instead of just sent.
 *  - [CALL_SIGNAL]: body is a [RoutingEnvelope] whose payload is E2E-opaque
 *    call-signaling plaintext (see `messenger.common.e2ee.CallSignal`),
 *    relayed like [ROUTE]. Unlike [ROUTE], the relay does NOT mailbox this
 *    for later delivery when the recipient is offline — see [CALL_UNAVAILABLE].
 *  - [CALL_UNAVAILABLE]: relay -> caller only, NOT E2E-encrypted (the relay
 *    generates this itself; it cannot forge a valid E2E HANGUP as if from
 *    the callee). Body is the 32-byte callee key that was unreachable.
 *  - [PUSH_REGISTER]: body is a UTF-8 UnifiedPush endpoint URL (empty body =
 *    unregister). Not E2E-encrypted — this is metadata about how to reach the
 *    device, not message content, and the relay itself is the only party that
 *    ever needs to read it. The relay POSTs to this endpoint (no body) to wake
 *    the device's distributor app when it mailboxes a frame for it while
 *    offline, so a killed app can still be woken for a message or an
 *    incoming call instead of only finding out on its next manual launch.
 *  - [READ_ACK]: body is a [RoutingEnvelope] (original sender's key + the
 *    message ID being acknowledged), relayed like [ROUTE]. Unlike
 *    [DELIVERY_ACK] (sent automatically the instant a message decrypts),
 *    this is sent by the app layer only once the message is actually shown
 *    on screen — see [messenger.android.data.ConnectionManager.markChatSeen].
 *  - [TYPING_INDICATOR]: body is a [RoutingEnvelope] whose payload is an
 *    E2E-opaque empty ratchet message (proves it's really from an
 *    established session with that peer, same reasoning as [CALL_SIGNAL]).
 *    Routed live-only (see the relay's `routeEphemeral`) — never mailboxed
 *    and never triggers a push wakeup, since a "typing" ping is worthless by
 *    the time either could deliver it.
 *  - [FILE_TRANSFER]: body is a [RoutingEnvelope] whose payload is E2E-opaque
 *    file-transfer plaintext (see `messenger.common.e2ee.FileSignal`). Routed
 *    live-only like [TYPING_INDICATOR] — the relay NEVER mailboxes or persists
 *    file bytes, so a file only ever exists on the two endpoints. If the
 *    recipient is offline the sender gets a [FILE_UNAVAILABLE] instead.
 *  - [FILE_UNAVAILABLE]: relay -> sender only, NOT E2E-encrypted. Body is the
 *    32-byte recipient key that was unreachable, so the sender can cancel the
 *    in-progress transfer immediately rather than waiting it out.
 *  - [REACTION]: body is a [RoutingEnvelope] whose payload is E2E-opaque
 *    reaction plaintext (see `messenger.common.e2ee.ReactionSignal`), relayed
 *    and mailboxed like [ROUTE] — a reaction on an offline peer's message
 *    should still arrive once they reconnect, same as the message itself did.
 *  - [EDIT_MESSAGE]: body is a [RoutingEnvelope] whose payload is E2E-opaque
 *    edit plaintext (see `messenger.common.e2ee.EditSignal`), relayed and
 *    mailboxed like [ROUTE]. A client that doesn't recognize this type (an
 *    older build) falls through the reader loop's default case and simply
 *    ignores it.
 *  - [TURN_CREDENTIALS_REQUEST]: body is empty. Asks the relay to mint a
 *    fresh, short-lived TURN username/password for a call about to start —
 *    the relay is the only party that ever holds the actual TURN provider's
 *    API secret (an env var, never shipped to any client), so a device can
 *    never itself carry a credential that outlives one call attempt.
 *  - [TURN_CREDENTIALS_RESULT]: relay -> client only, NOT E2E-encrypted (this
 *    is transport-provider config, not message content). Body is
 *    [FOUND][2-byte username length][username UTF-8][2-byte password
 *    length][password UTF-8], or just [NOT_FOUND] if the relay has no TURN
 *    provider configured or the mint call failed.
 *  - [REGISTER_ALIAS]: body is a 32-byte random routing alias this connection
 *    is now reachable under, in addition to its real static key -- see
 *    `messenger.server.routing.AliasStore`. Not E2E-encrypted (there's
 *    nothing to protect: it's a fresh random value, meaningless until this
 *    same device tells a contact about it over an already-encrypted
 *    channel). Every [ROUTE]-family frame's [RoutingEnvelope] header may
 *    from now on be either a real static key (legacy/fallback) or one of
 *    these aliases -- the relay resolves either the same way before
 *    routing/mailboxing, so nothing downstream needs to know which kind it
 *    got.
 *  - [ALIAS_UPDATE]: body is a [RoutingEnvelope] whose payload is E2E-opaque
 *    (the new 32-byte alias), relayed and mailboxed like [ROUTE]. Tells one
 *    specific contact this device's current alias, so they can address
 *    future [ROUTE]-family frames to that alias instead of this device's
 *    real static key -- the relay only ever learns "some device rotated
 *    some alias," never which of a sender's contacts was told.
 */
object TransportFrame {
    const val ROUTE: Byte = 0x01
    const val PUBLISH_PREKEYS: Byte = 0x02
    const val FETCH_PREKEYS: Byte = 0x03
    const val PREKEYS_RESULT: Byte = 0x04
    const val SESSION_RESET_NOTICE: Byte = 0x05
    const val DELIVERY_ACK: Byte = 0x06
    const val CALL_SIGNAL: Byte = 0x07
    const val CALL_UNAVAILABLE: Byte = 0x08
    const val PUSH_REGISTER: Byte = 0x09
    const val READ_ACK: Byte = 0x0A
    const val TYPING_INDICATOR: Byte = 0x0B
    const val FILE_TRANSFER: Byte = 0x0C
    const val FILE_UNAVAILABLE: Byte = 0x0D
    const val REACTION: Byte = 0x0E
    const val EDIT_MESSAGE: Byte = 0x0F
    const val GROUP_SENDER_KEY: Byte = 0x10
    const val GROUP_MESSAGE: Byte = 0x11
    const val GROUP_KEY_REQUEST: Byte = 0x12
    const val GROUP_CONTROL_EVENT: Byte = 0x13
    const val GROUP_CONTROL_SYNC_REQUEST: Byte = 0x14
    const val GROUP_JOIN_REQUEST: Byte = 0x15
    const val TURN_CREDENTIALS_REQUEST: Byte = 0x16
    const val TURN_CREDENTIALS_RESULT: Byte = 0x17
    const val REGISTER_ALIAS: Byte = 0x18
    const val ALIAS_UPDATE: Byte = 0x19

    const val RESULT_FOUND: Byte = 0x01
    const val RESULT_NOT_FOUND: Byte = 0x00

    fun encode(type: Byte, body: ByteArray): ByteArray {
        val out = ByteArray(1 + body.size)
        out[0] = type
        body.copyInto(out, 1)
        return out
    }

    fun encode(type: Byte): ByteArray = byteArrayOf(type)

    class Decoded(val type: Byte, val body: ByteArray)

    fun decode(frame: ByteArray): Decoded {
        require(frame.isNotEmpty()) { "empty transport frame" }
        return Decoded(frame[0], frame.copyOfRange(1, frame.size))
    }
}
