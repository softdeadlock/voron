package messenger.android.data

import android.util.Log
import messenger.android.BuildConfig

/**
 * Diagnostic-only logging (connection state, ICE candidates/SDP, call signaling) added while
 * debugging WebRTC/relay reliability. SDP strings carry IP/port candidate info, so these must
 * not fire in release builds. No-ops unless [BuildConfig.DEBUG] — gate anything genuinely
 * user-actionable through UI state instead of logcat.
 */
object VoronLog {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, message, throwable)
    }
}
