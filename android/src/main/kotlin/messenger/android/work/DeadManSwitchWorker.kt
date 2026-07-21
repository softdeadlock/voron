package messenger.android.work

import android.app.ActivityManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
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
        // Disarm before acting, not after -- if the action itself throws partway through, a
        // stuck-armed switch retrying (and re-sending/re-wiping) on every future check is worse
        // than a fire that silently didn't complete.
        store.save(config.copy(enabled = false))

        when (config.action) {
            DeadManSwitchAction.SEND_MESSAGE -> {
                val target = config.targetPeerKeyHex ?: return Result.success()
                if (config.messageText.isBlank()) return Result.success()
                runCatching { app.connectionManager.sendMessage(target, config.messageText) }
            }
            DeadManSwitchAction.WIPE_APP_DATA -> {
                // Wipes every app file/prefs/database as if freshly installed -- the only reliable
                // way to get *everything* (identity keys, contacts, message history, this very
                // config) in one call, rather than hand-tracking every SecureStore/prefs file this
                // app has ever created and this list silently going stale as new ones are added.
                (applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
            }
        }
        return Result.success()
    }

    companion object {
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
