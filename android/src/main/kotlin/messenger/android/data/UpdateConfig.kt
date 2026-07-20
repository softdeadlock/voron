package messenger.android.data

/** Where in-app updates are checked against — a public repo holding nothing but release APKs, independent of the relay's own uptime. */
object UpdateConfig {
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/lauro4kaaaa-eng/voron-releases/releases/latest"
}
