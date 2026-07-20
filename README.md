# Voron

A small end-to-end encrypted messenger, published here as a source preview. This is the
whole project — protocol library, relay, desktop test client, and the Android app — not a
trimmed excerpt.

**This is not a release.** See `LICENSE.md`: no permission is granted to build, install, or
otherwise use this code yet. It's here to be looked at, not run. A proper open-source release
(aimed at F-Droid) is planned once the project is ready for it.

## What's in here

- `common/` — the crypto and protocol core:
  - `e2ee/` — X3DH-lite (asynchronous key agreement) + a Double Ratchet on top, built on
    Curve25519/ChaCha20-Poly1305/Ed25519/HKDF (JDK-provided primitives).
  - `crypto/` — thin wrappers over those primitives.
  - `group/` — group messaging via a sender-keys scheme, plus a client-side signed hash-chain
    event log for membership/roles, since the relay itself has no concept of groups.
  - `onion/` — an optional layered-encryption transport (fixed hop count, size-bucket padding)
    so the relay can't directly link a connection's IP to its identity key.
  - `client/`, `transport/`, `backup/` — the wire protocol, Noise_IK transport handshake, and
    an encrypted-backup format.
- `server/` — the relay: store-and-forward routing, prekey directory, offline mailbox, the
  onion-hop role. Never sees plaintext, never holds a message longer than delivery takes, and
  has zero group-membership awareness by design.
- `client/` — a plain JVM console harness used to drive `common`/`server` against each other in
  integration tests. Not a real app.
- `android/` — the actual client: Compose UI, calling (WebRTC), file transfer, push wakeups.

## Design, short version

- The relay is treated as untrusted: it routes ciphertext and directory data and is assumed to
  be actively adversarial, not just curious.
- 1:1 sessions get forward secrecy (X3DH) and post-compromise security (the DH ratchet on top).
  Group sessions are sender-keys: a membership change rekeys the whole group, but there's no
  per-message ratchet at the group layer — a deliberate scope cut, not full MLS/TreeKEM.
- Onion routing hides the IP↔identity link from any single relay hop and pads frames to fixed
  size buckets against passive size correlation. It does not defend against an adversary
  watching both ends of a circuit at once — that needs cover traffic, which isn't implemented.

## Status

Pre-release. Functional across all four modules, but still under active review before a real
license and a real launch. Don't rely on it for anything yet.
