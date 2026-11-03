package id.termdeck.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AndroidUpdater(val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var state: String = "idle"

    @Volatile
    private var progressPercent: Int = 0

    @Volatile
    private var downloadedBytes: Long = 0L

    @Volatile
    private var totalBytes: Long = 0L

    @Volatile
    private var message: String = ""

    @Volatile
    private var cachedApkFile: File? = null

    companion object {
        const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/kotajakarta/termdeck/releases/latest"

        fun compareSemVer(v1: String, v2: String): Int {
            fun parseParts(v: String): List<Int> {
                val clean = v.trim().removePrefix("v").substringBefore("-")
                return clean.split(".").map { it.toIntOrNull() ?: 0 }
            }

            val p1 = parseParts(v1)
            val p2 = parseParts(v2)
            val maxLen = maxOf(p1.size, p2.size)

            for (i in 0 until maxLen) {
                val num1 = p1.getOrElse(i) { 0 }
                val num2 = p2.getOrElse(i) { 0 }
                if (num1 != num2) {
                    return num1.compareTo(num2)
                }
            }
            return 0
        }

        fun findApkAsset(assetsJson: JsonArray): Pair<String, String>? {
            for (element in assetsJson) {
                if (element !is JsonObject) continue
                val name = element["name"]?.jsonPrimitive?.content ?: ""
                val downloadUrl = element["browser_download_url"]?.jsonPrimitive?.content ?: ""
                if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotBlank()) {
                    return Pair(downloadUrl, name)
                }
            }
            return null
        }
    }

    suspend fun checkUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val currentVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")

        try {
            val url = URL(GITHUB_LATEST_RELEASE_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "TermDeck-Android")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 8000
                readTimeout = 8000
            }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = Json.parseToJsonElement(body).jsonObject
                val tagName = json["tag_name"]?.jsonPrimitive?.content ?: ""
                val releaseTitle = json["name"]?.jsonPrimitive?.content ?: tagName
                val releaseNotes = json["body"]?.jsonPrimitive?.content ?: ""
                val assets = json["assets"]?.jsonArray ?: JsonArray(emptyList())
                val apkAsset = findApkAsset(assets)

                val hasUpdate = compareSemVer(tagName, currentVersion) > 0 && apkAsset != null

                UpdateCheckResult(
                    platform = "android",
                    current_version = currentVersion,
                    latest_version = tagName.ifBlank { currentVersion },
                    has_update = hasUpdate,
                    release_title = releaseTitle,
                    release_notes = releaseNotes,
                    download_url = apkAsset?.first
                )
            } else if (responseCode == 403) {
                UpdateCheckResult(
                    platform = "android",
                    current_version = currentVersion,
                    latest_version = currentVersion,
                    has_update = false,
                    release_title = "Batas Kuota GitHub Tercapai",
                    release_notes = "Batas kuota publik GitHub API tercapai (HTTP 403). Silakan coba lagi beberapa saat lagi.",
                    download_url = null
                )
            } else {
                UpdateCheckResult(
                    platform = "android",
                    current_version = currentVersion,
                    latest_version = currentVersion,
                    has_update = false,
                    release_title = "Belum ada rilis resmi",
                    release_notes = "Tidak ada rilis APK publik terbaru di GitHub (status HTTP $responseCode).",
                    download_url = null
                )
            }
        } catch (e: Exception) {
            UpdateCheckResult(
                platform = "android",
                current_version = currentVersion,
                latest_version = currentVersion,
                has_update = false,
                release_title = "Gagal memeriksa pembaruan",
                release_notes = "Koneksi ke GitHub gagal: ${e.localizedMessage}",
                download_url = null
            )
        }
    }

    fun startDownload(apkUrl: String) {
        val updatesDir = File(context.cacheDir, "updates")
        val targetFile = File(updatesDir, "termdeck-update.apk")
        if (targetFile.exists() && targetFile.length() > 0) {
            cachedApkFile = targetFile
            progressPercent = 100
            state = "ready"
            message = "File APK pembaruan siap dipasang. Membuka installer..."
            installApk()
            return
        }

        if (state == "downloading") return

        state = "downloading"
        progressPercent = 0
        downloadedBytes = 0L
        totalBytes = 0L
        message = "Memulai pengunduhan APK..."

        scope.launch {
            try {
                if (!updatesDir.exists()) updatesDir.mkdirs()
                if (targetFile.exists()) targetFile.delete()

                var currentUrl = apkUrl
                var connection: HttpURLConnection
                var redirectCount = 0

                // Follow HTTP 301 / 302 / 307 redirects for GitHub download URLs
                while (true) {
                    val url = URL(currentUrl)
                    connection = url.openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "TermDeck-Android")
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000

                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == 307 || code == 308
                    ) {
                        redirectCount++
                        if (redirectCount > 5) throw IllegalStateException("Too many redirects")
                        currentUrl = connection.getHeaderField("Location") ?: throw IllegalStateException("No location header")
                        connection.disconnect()
                        continue
                    }
                    break
                }

                totalBytes = connection.contentLengthLong
                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        progressPercent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 99)
                        message = "Mengunduh pembaruan ($progressPercent%)..."
                    } else {
                        message = "Mengunduh pembaruan (${downloadedBytes / (1024 * 1024)} MB)..."
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                cachedApkFile = targetFile
                progressPercent = 100
                state = "ready"
                message = "Pengunduhan selesai. Membuka installer..."

                // Attempt to launch installer immediately
                installApk()
            } catch (e: Exception) {
                state = "error"
                message = "Gagal mengunduh APK: ${e.localizedMessage}"
            }
        }
    }

    fun installApk(): Boolean {
        val apkFile = cachedApkFile ?: File(context.cacheDir, "updates/termdeck-update.apk")
        if (!apkFile.exists()) {
            state = "error"
            message = "File APK pembaruan tidak ditemukan."
            return false
        }

        // Check Unknown Sources permission on Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = context.packageManager
            if (!pm.canRequestPackageInstalls()) {
                state = "ready"
                message = "Izin pasang aplikasi tidak dikenal diperlukan. Mohon aktifkan di Pengaturan, lalu klik tombol 'Pasang APK'."
                val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(manageIntent)
                return false
            }
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            state = "completed"
            message = "Installer sistem Android berhasil diluncurkan."
            true
        } catch (e: Exception) {
            state = "error"
            message = "Gagal meluncurkan installer: ${e.localizedMessage}"
            false
        }
    }

    fun getProgress(): UpdateProgressResult {
        return UpdateProgressResult(
            state = state,
            progress_percent = progressPercent,
            downloaded_bytes = downloadedBytes,
            total_bytes = totalBytes,
            message = message
        )
    }
}
