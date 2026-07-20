package messenger.android

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import messenger.android.data.AppLockStore
import messenger.android.data.AppState
import messenger.android.data.AvatarStore
import messenger.android.data.CallManager
import messenger.android.data.ConnectionManager
import messenger.android.data.ContactStore
import messenger.android.data.FileTransferManager
import messenger.android.data.GroupManager
import messenger.android.data.MessageStore
import messenger.android.data.ProfileStore
import messenger.android.data.SecureStore
import messenger.android.data.SettingsStore

/**
 * Owns [AppState] and [ConnectionManager] for the process lifetime, not just one Activity's —
 * an Activity recreation (rotation, task switch) must not tear down a live relay connection.
 */
class VoronApplication : Application() {
    lateinit var appState: AppState
        private set
    lateinit var callManager: CallManager
        private set
    lateinit var fileTransferManager: FileTransferManager
        private set
    lateinit var groupManager: GroupManager
        private set
    lateinit var connectionManager: ConnectionManager
        private set

    override fun onCreate() {
        super.onCreate()
        appState = AppState(
            ContactStore(SecureStore(this, "contacts.tsv")),
            MessageStore(SecureStore(this, "messages.tsv")),
            ProfileStore(SecureStore(this, "profile.txt")),
            SettingsStore(this),
            AvatarStore(SecureStore(this, "avatar_icon.txt")),
            AppLockStore(SecureStore(this, "app_lock.txt")),
        )
        callManager = CallManager(this, appState)
        fileTransferManager = FileTransferManager(this, appState)
        groupManager = GroupManager(this, appState)
        connectionManager = ConnectionManager(this, appState, callManager, fileTransferManager, groupManager)

        // ProcessLifecycleOwner (not this Activity's own) so a system dialog briefly covering
        // MainActivity — the biometric prompt's own PIN fallback, for instance — doesn't
        // immediately re-lock the app the moment it's unlocked.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    if (appState.appLockEnabled) appState.isLocked = true
                }
            },
        )
    }
}
