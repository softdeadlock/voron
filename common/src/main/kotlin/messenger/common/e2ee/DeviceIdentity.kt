package messenger.common.e2ee

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import messenger.common.crypto.Ed25519Signatures
import messenger.common.crypto.X25519
import messenger.common.transport.NoiseStaticKeyPair

/**
 * A device's long-term identity for end-to-end encryption:
 *  - [dhIdentity]: an X25519 keypair used for X3DH DH operations. This is
 *    the SAME key the device uses as its Noise transport static key, so a
 *    peer's routing address and its crypto identity are one and the same.
 *  - [signingIdentity]: an Ed25519 keypair used only to sign prekeys, so a
 *    fetched bundle can be authenticated as really coming from this device.
 */
class DeviceIdentity(
    val dhIdentity: NoiseStaticKeyPair,
    val signingIdentity: Ed25519Signatures.SigningKeyPair,
) {
    val dhIdentityPublicKey: ByteArray get() = dhIdentity.publicKey
    val signingIdentityPublicKey: ByteArray get() = signingIdentity.publicKey

    companion object {
        private const val ENCODED_LENGTH = 128

        fun generate(): DeviceIdentity =
            DeviceIdentity(X25519.generateKeyPair(), Ed25519Signatures.generateKeyPair())

        /**
         * Loads a device identity persisted by [saveTo], or generates and
         * persists a new one if [file] doesn't exist yet. This is what lets a
         * device keep the same address (its DH public key) across restarts —
         * without it every process start would be an unreachable stranger to
         * every peer it talked to before.
         *
         * Same caveat as the relay's identity file: plain file storage, not a
         * production key-storage story (real clients belong in OS
         * Keystore/Keychain).
         */
        fun loadOrCreate(file: File): DeviceIdentity {
            if (file.exists()) return decode(file.readBytes())
            val identity = generate()
            file.parentFile?.mkdirs()
            Files.write(file.toPath(), identity.encode())
            runCatching {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
            return identity
        }

        /**
         * Byte-oriented variant of [loadOrCreate] for callers that manage their own encrypted
         * storage instead of a plain [File] (e.g. Android's Keystore-backed file wrapper).
         * Pass the previously-persisted bytes (or null if none exist yet); [persist] is invoked
         * with the newly-generated identity's bytes exactly once, only when [existingBytes] is null.
         */
        fun loadOrCreate(existingBytes: ByteArray?, persist: (ByteArray) -> Unit): DeviceIdentity {
            if (existingBytes != null) return decode(existingBytes)
            val identity = generate()
            persist(identity.encode())
            return identity
        }

        private fun decode(bytes: ByteArray): DeviceIdentity {
            require(bytes.size == ENCODED_LENGTH) { "corrupt device identity: expected $ENCODED_LENGTH bytes, got ${bytes.size}" }
            val dh = NoiseStaticKeyPair(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64))
            val signing = Ed25519Signatures.SigningKeyPair(bytes.copyOfRange(64, 96), bytes.copyOfRange(96, 128))
            return DeviceIdentity(dh, signing)
        }

        private fun DeviceIdentity.encode(): ByteArray =
            dhIdentity.privateKey + dhIdentity.publicKey + signingIdentity.privateKey + signingIdentity.publicKey
    }
}
