package messenger.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Exists purely to keep the process foreground-priority while a relay connection is live, so
 * Android doesn't suspend the socket the moment [MainActivity] is backgrounded. Owns no
 * connection state itself — [messenger.android.data.ConnectionManager] starts/stops it as a
 * side effect of connecting/disconnecting.
 */
class VoronConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } catch (e: Exception) {
            // Belt-and-suspenders: startForeground() can still fail for reasons unrelated to type
            // (e.g. the app somehow isn't in a state Android allows a background start from).
            // Nothing to recover here — ConnectionManager's socket keeps running as a plain
            // background coroutine without foreground priority (so Android may throttle/kill it
            // sooner) instead; crashing the whole process would be strictly worse than that.
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Voron connection", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voron")
            .setContentText("Connected — staying online to receive messages")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "voron_connection"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoronConnectionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoronConnectionService::class.java))
        }
    }
}
