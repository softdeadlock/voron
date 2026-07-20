package messenger.android.data

/** The single production relay this build talks to — see ConnectionManager for why this is hardcoded rather than user-entered. */
object RelayConfig {
    const val HOST = "voron-relay.onrender.com"
    const val HTTP_SCHEME = "https"
    const val WS_SCHEME = "wss"
}
