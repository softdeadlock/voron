package messenger.server.identity

import messenger.common.transport.NoiseStaticKeyPair
import java.io.File
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.Files

/**
 * The relay's own long-term Noise static identity. Clients pin this
 * public key out-of-band (TOFU on first connect, or an app-bundled pin) —
 * that pinning story is not implemented yet, this is dev/local scope only.
 *
 * Loaded once at startup and kept stable across restarts by persisting the
 * raw private key to disk. This is NOT how a production relay should store
 * its identity key (needs an HSM / KMS-backed secret, file perms alone are
 * not sufficient) — flagged for the later hardening pass.
 */
object RelayIdentity {

    fun loadOrCreate(path: File): NoiseStaticKeyPair {
        if (path.exists()) {
            val privateKey = path.readBytes()
            return NoiseStaticKeyPair.fromPrivateKey(privateKey)
        }

        val keyPair = NoiseStaticKeyPair.generate()
        path.parentFile?.mkdirs()
        Files.write(path.toPath(), keyPair.privateKey)
        runCatching {
            Files.setPosixFilePermissions(path.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        return keyPair
    }
}
