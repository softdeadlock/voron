package messenger.android.work

import android.app.ActivityManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import messenger.android.VoronApplication
import messenger.android.data.DeadManSwitchAction
import messenger.android.data.DeadManSwitchConfig
import messenger.android.data.DeadManSwitchStore
import messenger.android.data.SecureStore
import messenger.android.data.VoronLog

private const val TAG = "VoronDeadManSwitch"
private const val WORK_NAME = "dead_man_switch_check"
// WorkManager periodic work has a 15-minute floor and, under Doze, can slip by hours -- fine here
// since the configured interval is measured in days, not minutes. A daily check is frequent enough
// to fire within a day of the real deadline without running so often it's a battery complaint.
private const val CHECK_INTERVAL_HOURS = 24L

/**
 * Runs roughly once a day (see [CHECK_INTERVAL_HOURS]) and, if the configured
 * [DeadManSwitchConfig.intervalDays] has elapsed since [DeadManSwitchConfig.lastActivityMillis]
 * (last app foreground — see `VoronApplication`'s lifecycle observer), fires the configured
 * action exactly once (disarming the switch immediately after, so it can't refire on the next
 * check if the device stays offline/unopened past that point).
 */
class DeadManSwitchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? VoronApplication ?: return Result.success()
        val store = DeadManSwitchStore(SecureStore(applicationContext, "dead_man_switch.enc"))
        val config = store.load()
        if (!config.enabled) return Result.success()

        val elapsedMillis = System.currentTimeMillis() - config.lastActivityMillis
        val thresholdMillis = config.intervalDays.toLong() * 24 * 60 * 60 * 1000
        if (elapsedMillis < thresholdMillis) return Result.success()

        VoronLog.w(TAG, "dead man's switch firing: ${config.action} (idle ${elapsedMillis / 86_400_000}d, threshold ${config.intervalDays}d)")

        when (config.action) {
            DeadManSwitchAction.SEND_MESSAGE -> {
                val target = config.targetPeerKeyHex
                if (target == null || config.messageText.isBlank()) {
                    store.save(config.copy(enabled = false))
                    return Result.success()
                }
                // BUG (audit finding, 2026-07-21): this worker can cold-start the process (a
                // periodic WorkManager job, unlike MainActivity/PushServiceImpl, never itself
                // calls connectionManager.connect()) -- exactly the scenario a truly-abandoned
                // device is likely to be in. Firing straight into
                // ConnectionManager.sendMessage's `appState.client ?: return` with no live client
                // used to silently no-op, and the switch disarmed itself regardless, so it could
                // never fire on any later check either. Now it drives its own connection attempt
                // and does NOT disarm unless a client actually came up to attempt the send with.
                app.connectionManager.connect()
                val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
                    while (app.appState.client == null) delay(500)
                    true
                }
                if (connected == null) {
                    VoronLog.w(TAG, "dead man's switch: couldn't connect in time, leaving armed for the next check")
                    return Result.success()
                }
                // BUG (audit finding, 2026-07-21): the connect wait above can take up to
                // CONNECT_TIMEOUT_MILLIS (30s) -- widening what used to be a near-instant window
                // where the user could open the app *during* this check and reset
                // lastActivityMillis, but this worker would still act on the stale `config`
                // snapshot read at the top of doWork(). Re-reading right before committing means a
                // fresh app-open during the wait actually aborts the fire, same as it would have if
                // it happened a moment earlier and failed the elapsed-time check above outright.
                if (!stillDue(store, config.intervalDays)) {
                    VoronLog.w(TAG, "dead man's switch: activity detected during connect wait, aborting")
                    return Result.success()
                }
                store.save(config.copy(enabled = false))
                runCatching { app.connectionManager.sendMessage(target, config.messageText) }
            }
            DeadManSwitchAction.WIPE_APP_DATA -> {
                // Disarm before acting, not after -- if the action itself throws partway through, a
                // stuck-armed switch retrying the wipe on every future check is harmless (wiping an
                // already-wiped app), unlike SEND_MESSAGE where a silent no-op above needs to retry
                // instead of disarming. Re-checked fresh for the same reason as SEND_MESSAGE above,
                // even though this path doesn't wait on a connection -- cheap, and correct beats
                // "fast enough that the race probably won't matter."
                if (!stillDue(store, config.intervalDays)) {
                    VoronLog.w(TAG, "dead man's switch: activity detected, aborting wipe")
                    return Result.success()
                }
                store.save(config.copy(enabled = false))
                // Wipes every app file/prefs/database as if freshly installed -- the only reliable
                // way to get *everything* (identity keys, contacts, message history, this very
                // config) in one call, rather than hand-tracking every SecureStore/prefs file this
                // app has ever created and this list silently going stale as new ones are added.
                (applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
            }
        }
        return Result.success()
    }

    /** Re-reads the config fresh and confirms it's still enabled and still past its deadline -- see the SEND_MESSAGE/WIPE_APP_DATA call sites above for why a stale in-memory snapshot isn't good enough right before actually firing. */
    private fun stillDue(store: DeadManSwitchStore, intervalDays: Int): Boolean {
        val fresh = store.load()
        if (!fresh.enabled) return false
        val elapsedMillis = System.currentTimeMillis() - fresh.lastActivityMillis
        val thresholdMillis = intervalDays.toLong() * 24 * 60 * 60 * 1000
        return elapsedMillis >= thresholdMillis
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 30_000L
        /** (Re)schedules the periodic check — call whenever the config is saved with [DeadManSwitchConfig.enabled] true, and once at app startup so a periodic job survives a process restart. Enqueuing with [ExistingPeriodicWorkPolicy.KEEP] is deliberate: this only needs to exist, not restart its 24h cycle, every time the app launches. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DeadManSwitchWorker>(CHECK_INTERVAL_HOURS, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Cancels the periodic check — call whenever the config is saved with [DeadManSwitchConfig.enabled] false. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
