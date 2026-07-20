package messenger.android.data

import android.content.Context
import messenger.common.backup.BackupArchive
import messenger.common.backup.RecoveryPhrase

/**
 * Full-account backup/restore: bundles every [SecureStore]-backed file that makes a fresh install
 * feel like "my Voron back", not just "a working crypto identity" — device identity + prekeys (to
 * keep the same address and keep talking to existing contacts), contacts, message history,
 * profile, and avatar. Deliberately excludes `app_lock.txt` — a device-local biometric/PIN
 * setting, not "my data", and carrying it to a new device with different enrolled biometrics would
 * be actively wrong (could lock the user out of their own restored account).
 *
 * Ratchet/session state is never included — it isn't persisted anywhere even on the original
 * device (see [messenger.common.e2ee.E2eeManager]) — so every contact simply gets a fresh X3DH
 * handshake on the first message after a restore, the same recovery path SESSION_RESET_NOTICE
 * already covers for a same-device restart (see [ConnectionManager]'s `sessionResetNotices`
 * collector).
 */
class BackupManager(private val context: Context) {
    private val sections = listOf(
        "device_identity" to "device_identity.key",
        "signed_prekeys" to "signed_prekeys.key",
        "contacts" to "contacts.tsv",
        "messages" to "messages.tsv",
        "profile" to "profile.txt",
        "avatar" to "avatar_icon.txt",
    )

    /** Generates a fresh recovery phrase and returns it alongside the encrypted archive bytes — the phrase is shown to the caller exactly once and is never itself persisted anywhere. */
    fun createBackup(): Pair<List<String>, ByteArray> {
        val phrase = RecoveryPhrase.generate()
        val data = LinkedHashMap<String, ByteArray>()
        for ((archiveName, fileName) in sections) {
            SecureStore(context, fileName).readBytes()?.let { data[archiveName] = it }
        }
        return phrase to BackupArchive.encrypt(data, phrase)
    }

    /**
     * Overwrites this device's own store files with [archive]'s contents. The app must be fully
     * restarted afterward — every in-memory structure that already loaded from these files at
     * process start (identity, prekeys, [AppState]) has no way to notice they changed underneath
     * it, by design (the same reason [SecureStore] itself has no change-notification mechanism).
     *
     * @throws javax.crypto.AEADBadTagException if [phrase] is wrong or [archive] is corrupt/tampered.
     */
    fun restoreBackup(archive: ByteArray, phrase: List<String>) {
        val data = BackupArchive.decrypt(archive, phrase)
        for ((archiveName, fileName) in sections) {
            data[archiveName]?.let { SecureStore(context, fileName).writeBytes(it) }
        }
    }
}
