package messenger.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import messenger.android.data.VoronLog

private const val TAG = "VoronCall"

/**
 * Foreground service backing the ringing/in-call UI, mirroring [VoronConnectionService]'s role
 * for the relay connection: keeps the process alive for the call's duration and, while ringing,
 * wakes the screen via a full-screen intent like a real incoming call. Owns no call state itself
 * — [messenger.android.data.CallManager] starts/updates/stops it as a side effect of the call
 * state machine.
 */
class CallForegroundService : Service() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isRinging = intent?.getBooleanExtra(EXTRA_RINGING, false) ?: false
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME) ?: "Voron"
        VoronLog.d(TAG, "CallForegroundService onStartCommand: isRinging=$isRinging peerName=$peerName")
        val notification = if (isRinging) buildRingingNotification(peerName) else buildInCallNotification(peerName)
        try {
            // NOT FOREGROUND_SERVICE_TYPE_PHONE_CALL: that type is gated behind holding
            // MANAGE_OWN_CALLS or the system dialer role (i.e. a Telecom-integrated
            // ConnectionService, which this app deliberately doesn't have) — requesting it
            // without that throws a SecurityException on every single call, which used to get
            // caught below and silently stopSelf() the service immediately. With no real
            // foreground-priority protection for the call's duration, Android was free to
            // throttle mic access, which is what was actually causing "no audio reaches the
            // other side": the recording side never got to start at all.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            VoronLog.w(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRinging) startRingtoneAndVibration() else stopRingtoneAndVibration()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        VoronLog.d(TAG, "CallForegroundService onDestroy")
        stopRingtoneAndVibration()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        VoronLog.d(TAG, "CallForegroundService onTaskRemoved (app swiped away from recents)")
        super.onTaskRemoved(rootIntent)
    }

    private fun startRingtoneAndVibration() {
        stopRingtoneAndVibration()
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        } catch (e: Exception) {
            // Best-effort: a silent incoming call is still a call, just without a ringtone.
        }
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        } catch (e: Exception) {
            // Best-effort, same as the ringtone above — a ring with sound but no buzz is fine.
            VoronLog.w(TAG, "vibration failed", e)
        }
    }

    private fun stopRingtoneAndVibration() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Voron calls", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    private fun buildRingingNotification(callerName: String): Notification {
        ensureChannel()
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Incoming call")
            .setContentText(callerName)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .build()
    }

    private fun buildInCallNotification(peerName: String): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call in progress")
            .setContentText(peerName)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1000
        private const val CHANNEL_ID = "voron_calls"
        private const val EXTRA_RINGING = "ringing"
        private const val EXTRA_PEER_NAME = "peer_name"

        fun startRinging(context: Context, callerName: String) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_RINGING, true)
                .putExtra(EXTRA_PEER_NAME, callerName)
            context.startForegroundService(intent)
        }

        fun startInCall(context: Context, peerName: String) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_RINGING, false)
                .putExtra(EXTRA_PEER_NAME, peerName)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
