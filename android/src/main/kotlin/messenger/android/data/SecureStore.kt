package messenger.android.data

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException

/**
 * Wraps a single file in AES-256-GCM encryption backed by a Keystore master key, so app data
 * survives more than Android's per-UID sandbox: a root shell, a forensic file copy, or a restored
 * ADB backup all see ciphertext instead of the plain TSV/key bytes a raw [File] would have held.
 *
 * Writes are delete-then-write, not temp-file-then-rename: [EncryptedFile] bakes the target
 * file's *name* into the ciphertext as associated data (see its `openFileOutput`/`openFileInput`,
 * both pass `mFile.getName()` to Tink's `StreamingAead`), so anything encrypted under a temp name
 * and then renamed becomes permanently undecryptable under its real name — a real regression a
 * previous version of this class shipped with, caught via a production crash
 * (`IOException: No matching key found for the ciphertext in the stream`). [recoverFromRenameBug]
 * exists solely to self-heal any file that got written that way before this reverted.
 */
class SecureStore(context: Context, fileName: String) {
    private val appContext = context.applicationContext
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val file = File(appContext.filesDir, fileName)

    // AppState's various markDelivered/markSeen/appendMessage-style calls run on different
    // dispatcher threads and can genuinely race on the same SecureStore instance (e.g. two ACKs
    // arriving close together). Without a lock, one writer's delete()-then-openFileOutput() can
    // interleave with another's, and EncryptedFile.openFileOutput() throws "output file already
    // exists" the moment two writers both see the file gone and both try to recreate it — this is
    // what actually crashed the app, not a missing delete (the delete was already there).
    // `synchronized` (not a Mutex) is correct here since neither method is a suspend fun, and
    // it's reentrant so readBytes -> recoverFromRenameBug -> writeBytes can't self-deadlock.
    fun readBytes(): ByteArray? = synchronized(this) {
        if (!file.exists()) return@synchronized null
        try {
            encryptedFile(file).openFileInput().use { it.readBytes() }
        } catch (e: IOException) {
            recoverFromRenameBug() ?: throw e
        }
    }

    /**
     * [file]'s bytes were encrypted under the AAD for "${file.name}.tmp" (the old, broken
     * temp-then-rename write path) — copies them into a file actually named that, decrypts
     * through *that* path (AAD now matches), then immediately re-persists correctly via
     * [writeBytes] so this only ever has to run once per affected file. Returns null (falling
     * back to surfacing the original exception) if the bytes aren't recoverable this way either.
     */
    private fun recoverFromRenameBug(): ByteArray? {
        val shadowFile = File(appContext.filesDir, "${file.name}.tmp")
        return try {
            file.copyTo(shadowFile, overwrite = true)
            val recovered = encryptedFile(shadowFile).openFileInput().use { it.readBytes() }
            writeBytes(recovered)
            recovered
        } catch (e: Exception) {
            null
        } finally {
            shadowFile.delete()
        }
    }

    fun writeBytes(bytes: ByteArray) = synchronized(this) {
        // EncryptedFile refuses to open a stream over an existing file, so each write starts fresh.
        if (file.exists()) file.delete()
        encryptedFile(file).openFileOutput().use { it.write(bytes) }
    }

    fun readText(): String? = readBytes()?.toString(Charsets.UTF_8)

    fun writeText(text: String) = writeBytes(text.toByteArray(Charsets.UTF_8))

    private fun encryptedFile(target: File): EncryptedFile =
        EncryptedFile.Builder(appContext, target, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
}
