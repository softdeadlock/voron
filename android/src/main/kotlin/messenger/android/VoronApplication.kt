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
import messenger.android.data.DeadManSwitchStore
import messenger.android.data.FileTransferManager
import messenger.android.data.GroupManager
import messenger.android.data.MessageStore
import messenger.android.data.ProfileStore
import messenger.android.data.SecureStore
import messenger.android.data.SettingsStore
import messenger.android.work.DeadManSwitchWorker

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
    private lateinit var deadManSwitchStore: DeadManSwitchStore

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
        deadManSwitchStore = DeadManSwitchStore(SecureStore(this, "dead_man_switch.enc"))
        // Re-enqueue (KEEP policy -- see DeadManSwitchWorker.schedule) on every process start so
        // the periodic job survives an app update/reinstall or a process WorkManager itself lost
        // track of, not just the first time it was armed.
        if (deadManSwitchStore.load().enabled) DeadManSwitchWorker.schedule(this)

        // ProcessLifecycleOwner (not this Activity's own) so a system dialog briefly covering
        // MainActivity — the biometric prompt's own PIN fallback, for instance — doesn't
        // immediately re-lock the app the moment it's unlocked.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    deadManSwitchStore.recordActivity()
                }

                override fun onStop(owner: LifecycleOwner) {
                    if (appState.appLockEnabled) appState.isLocked = true
                }
            },
        )
    }

    fun loadDeadManSwitchConfig(): messenger.android.data.DeadManSwitchConfig = deadManSwitchStore.load()

    /** Persists [config] and (re)schedules or cancels [DeadManSwitchWorker] to match [messenger.android.data.DeadManSwitchConfig.enabled] — the two must never drift apart, so this is the only place either happens. */
    fun saveDeadManSwitchConfig(config: messenger.android.data.DeadManSwitchConfig) {
        deadManSwitchStore.save(config)
        if (config.enabled) DeadManSwitchWorker.schedule(this) else DeadManSwitchWorker.cancel(this)
    }
}
