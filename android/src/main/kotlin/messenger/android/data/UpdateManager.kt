package messenger.android.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-app self-update: checks GitHub's release feed, downloads a newer APK into the cache dir,
 * and hands it to the system installer. Split out of [ConnectionManager], which only triggers
 * [checkAndDownload] after each successful connect — nothing here touches the relay, so updates
 * keep working even while it's asleep.
 */
class UpdateManager(
    private val appContext: Context,
    private val appState: AppState,
    private val httpClient: HttpClient,
) {
    /** Checks GitHub's release feed and, if a newer build exists, starts downloading immediately. Best-effort: silent no-op if GitHub is unreachable or the feed is empty. */
    suspend fun checkAndDownload() {
        try {
            val currentVersionCode = appContext.packageManager
                .getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
                .longVersionCode.toInt()
            val body = httpClient.get(UpdateConfig.LATEST_RELEASE_API_URL) {
                header("User-Agent", "Voron-Android")
            }.bodyAsText()
            val versionCode = Regex("""versionCode:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return
            val versionName = Regex(""""tag_name":\s*"v?([^"]+)"""").find(body)?.groupValues?.get(1) ?: return
            val apkUrl = Regex(""""browser_download_url":\s*"([^"]+\.apk)"""").find(body)?.groupValues?.get(1) ?: return
            if (versionCode > currentVersionCode) {
                downloadUpdate(UpdateInfo(versionCode, versionName), apkUrl)
            }
        } catch (e: Exception) {
            // Best-effort: no update banner if GitHub is unreachable or the release feed is empty.
        }
    }

    private suspend fun downloadUpdate(info: UpdateInfo, apkUrl: String) {
        withContext(Dispatchers.Main) {
            appState.availableUpdate = info
            appState.updateDownloading = true
        }
        try {
            val apkBytes = httpClient.get(apkUrl) { header("User-Agent", "Voron-Android") }.bodyAsBytes()
            val apkFile = File(appContext.cacheDir, "updates/voron-update.apk").apply { parentFile?.mkdirs() }
            apkFile.writeBytes(apkBytes)
            withContext(Dispatchers.Main) {
                appState.updateDownloading = false
                appState.updateReadyApkPath = apkFile.path
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { appState.updateDownloading = false }
        }
    }

    /** Fires the system installer for a build [checkAndDownload] already fetched — no network call here. */
    fun installDownloadedUpdate() {
        val apkPath = appState.updateReadyApkPath ?: return
        val apkFile = File(apkPath)
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appState.availableUpdate = null
        appState.updateReadyApkPath = null
        appContext.startActivity(intent)
    }
}
