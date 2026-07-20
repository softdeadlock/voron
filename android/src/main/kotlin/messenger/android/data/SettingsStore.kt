package messenger.android.data

import android.content.Context

/** Persists app-appearance preferences — plain SharedPreferences, since none of this is sensitive. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("voron_settings", Context.MODE_PRIVATE)

    fun loadThemeMode(): ThemeMode = ThemeMode.fromStorageKey(prefs.getString(KEY_THEME_MODE, null))

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun loadFontScale(): Float = prefs.getFloat(KEY_FONT_SCALE, 1.0f)

    fun saveFontScale(scale: Float) {
        prefs.edit().putFloat(KEY_FONT_SCALE, scale).apply()
    }

    fun loadOnionRoutingEnabled(): Boolean = prefs.getBoolean(KEY_ONION_ROUTING, false)

    fun saveOnionRoutingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONION_ROUTING, enabled).apply()
    }

    fun loadOnionWifiOnly(): Boolean = prefs.getBoolean(KEY_ONION_WIFI_ONLY, false)

    fun saveOnionWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ONION_WIFI_ONLY, enabled).apply()
    }

    fun loadNotificationsEnabled(): Boolean = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)

    fun saveNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    fun loadHideNotificationSender(): Boolean = prefs.getBoolean(KEY_HIDE_NOTIFICATION_SENDER, false)

    fun saveHideNotificationSender(hide: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_NOTIFICATION_SENDER, hide).apply()
    }

    fun loadHideNotificationContent(): Boolean = prefs.getBoolean(KEY_HIDE_NOTIFICATION_CONTENT, false)

    fun saveHideNotificationContent(hide: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_NOTIFICATION_CONTENT, hide).apply()
    }

    fun loadPushEnabled(): Boolean = prefs.getBoolean(KEY_PUSH_ENABLED, false)

    fun savePushEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PUSH_ENABLED, enabled).apply()
    }

    /** The UnifiedPush endpoint URL last handed to us by the distributor, if any — re-sent to the relay on every fresh connect (relay-side registration is in-memory only, lost on restart). */
    fun loadPushEndpoint(): String? = prefs.getString(KEY_PUSH_ENDPOINT, null)

    fun savePushEndpoint(endpointUrl: String?) {
        prefs.edit().putString(KEY_PUSH_ENDPOINT, endpointUrl).apply()
    }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_ONION_ROUTING = "onion_routing_enabled"
        const val KEY_ONION_WIFI_ONLY = "onion_wifi_only"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_HIDE_NOTIFICATION_SENDER = "hide_notification_sender"
        const val KEY_HIDE_NOTIFICATION_CONTENT = "hide_notification_content"
        const val KEY_PUSH_ENABLED = "push_enabled"
        const val KEY_PUSH_ENDPOINT = "push_endpoint"
    }
}
