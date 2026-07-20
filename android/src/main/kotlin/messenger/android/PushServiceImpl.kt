package messenger.android

import messenger.android.data.VoronLog
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

private const val TAG = "VoronPush"

/**
 * Bound by the UnifiedPush library (see the `PUSH_EVENT` service declaration in
 * AndroidManifest.xml) whenever the distributor has something for this app — a fresh endpoint to
 * register with the relay, a wakeup message, or an unregistration. The push message itself carries
 * no content (the relay's wakeup POST body is empty; see [messenger.android.data.VoronLog] and
 * [messenger.server.routing.PushNotifier] on the relay side) — it only needs to get this process
 * running again so [messenger.android.data.ConnectionManager] can reconnect and drain whatever the
 * relay mailboxed while this device was offline.
 */
class PushServiceImpl : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        VoronLog.d(TAG, "new push endpoint registered")
        (application as VoronApplication).connectionManager.onPushEndpointChanged(endpoint.url)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        VoronLog.d(TAG, "push wakeup received")
        (application as VoronApplication).connectionManager.connect()
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        VoronLog.w(TAG, "push registration failed: $reason")
    }

    override fun onUnregistered(instance: String) {
        VoronLog.d(TAG, "push unregistered by distributor")
        (application as VoronApplication).connectionManager.onPushEndpointChanged(null)
    }
}
