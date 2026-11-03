package id.termdeck.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import id.termdeck.app.data.DEFAULT_INITIAL_PIN
import id.termdeck.app.data.Vault
import id.termdeck.app.data.AndroidUpdater
import id.termdeck.app.server.TermDeckServer
import java.io.File
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var downloadOverlay: LinearLayout
    private lateinit var downloadTitleView: TextView
    private lateinit var downloadStatusView: TextView
    private lateinit var downloadProgressBar: ProgressBar
    private lateinit var downloadOpenButton: TextView
    private lateinit var downloadCloseButton: TextView
    private lateinit var downloadIconView: ImageView
    private var currentDownloadId: Int = -1
    private var isOverlayDismissedByUser: Boolean = false
    private var autoDismissRunnable: Runnable? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Status izin notifikasi tercatat
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

    private fun setupDownloadOverlay(): View {
        downloadOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            val padH = dpToPx(14f)
            val padV = dpToPx(12f)
            setPadding(padH, padV, padH, padV)

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1b2523"))
                cornerRadius = dpToPx(12f).toFloat()
                setStroke(dpToPx(1f), Color.parseColor("#2d3f3b"))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dpToPx(8f).toFloat()
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        downloadIconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(20f), dpToPx(20f)).apply {
                marginEnd = dpToPx(8f)
            }
            setImageResource(android.R.drawable.stat_sys_download)
            setColorFilter(Color.parseColor("#10b981"))
        }

        downloadTitleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.parseColor("#f1f5f9"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            text = "Unduhan"
        }

        downloadCloseButton = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "✕"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 14f
            setPadding(dpToPx(6f), dpToPx(2f), dpToPx(6f), dpToPx(2f))
            setOnClickListener {
                isOverlayDismissedByUser = true
                autoDismissRunnable?.let { uiHandler.removeCallbacks(it) }
                downloadOverlay.visibility = View.GONE
            }
        }

        topRow.addView(downloadIconView)
        topRow.addView(downloadTitleView)
        topRow.addView(downloadCloseButton)
        downloadOverlay.addView(topRow)

        downloadProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(5f)
            ).apply {
                topMargin = dpToPx(8f)
                bottomMargin = dpToPx(6f)
            }
            isIndeterminate = true
            max = 100
            val emeraldColor = ColorStateList.valueOf(Color.parseColor("#10b981"))
            progressTintList = emeraldColor
            indeterminateTintList = emeraldColor
        }
        downloadOverlay.addView(downloadProgressBar)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        downloadStatusView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 11.5f
            text = "Menyiapkan..."
        }

        downloadOpenButton = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "Buka File"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            setPadding(dpToPx(10f), dpToPx(4f), dpToPx(10f), dpToPx(4f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#059669"))
                cornerRadius = dpToPx(6f).toFloat()
            }
        }

        bottomRow.addView(downloadStatusView)
        bottomRow.addView(downloadOpenButton)
        downloadOverlay.addView(bottomRow)

        return downloadOverlay
    }

    private fun showInAppDownloadStarted(downloadId: Int, fileName: String) {
        currentDownloadId = downloadId
        isOverlayDismissedByUser = false
        autoDismissRunnable?.let { uiHandler.removeCallbacks(it) }

        downloadTitleView.text = fileName
        downloadIconView.setImageResource(android.R.drawable.stat_sys_download)
        downloadIconView.setColorFilter(Color.parseColor("#10b981"))
        downloadProgressBar.visibility = View.VISIBLE
        downloadProgressBar.isIndeterminate = true
        downloadProgressBar.progress = 0
        downloadStatusView.setTextColor(Color.parseColor("#94a3b8"))
        downloadStatusView.text = "Memulai unduhan..."
        downloadOpenButton.visibility = View.GONE

        downloadOverlay.visibility = View.VISIBLE
    }

    private fun showInAppDownloadProgress(downloadId: Int, fileName: String, downloaded: Long, total: Long) {
        if (currentDownloadId != downloadId || isOverlayDismissedByUser) return
        downloadOverlay.visibility = View.VISIBLE
        downloadTitleView.text = fileName
        downloadOpenButton.visibility = View.GONE

        if (total > 0L) {
            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            downloadProgressBar.isIndeterminate = false
            downloadProgressBar.progress = percent
            downloadStatusView.text = "${formatFileSize(downloaded)} / ${formatFileSize(total)} ($percent%)"
        } else {
            downloadProgressBar.isIndeterminate = true
            downloadStatusView.text = "Mengunduh ${formatFileSize(downloaded)}..."
        }
    }

    private fun showInAppDownloadSuccess(downloadId: Int, dl: DownloadResult) {
        if (currentDownloadId != downloadId) return
        isOverlayDismissedByUser = false
        downloadOverlay.visibility = View.VISIBLE
        downloadTitleView.text = dl.fileName
        downloadIconView.setImageResource(android.R.drawable.stat_sys_download_done)
        downloadIconView.setColorFilter(Color.parseColor("#10b981"))
        downloadProgressBar.isIndeterminate = false
        downloadProgressBar.progress = 100
        downloadProgressBar.visibility = View.VISIBLE

        downloadStatusView.setTextColor(Color.parseColor("#10b981"))
        downloadStatusView.text = "✓ Selesai (${formatFileSize(dl.sizeBytes)})"

        downloadOpenButton.visibility = View.VISIBLE
        downloadOpenButton.setOnClickListener {
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dl.uri, dl.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                startActivity(openIntent)
            }.onFailure {
                Toast.makeText(applicationContext, "Tidak ada aplikasi untuk membuka tipe file ini", Toast.LENGTH_SHORT).show()
            }
        }

        autoDismissRunnable?.let { uiHandler.removeCallbacks(it) }
        autoDismissRunnable = Runnable {
            if (currentDownloadId == downloadId) {
                downloadOverlay.visibility = View.GONE
            }
        }
        uiHandler.postDelayed(autoDismissRunnable!!, 10000L)
    }

    private fun showInAppDownloadError(downloadId: Int, fileName: String, errorMsg: String) {
        if (currentDownloadId != downloadId) return
        isOverlayDismissedByUser = false
        downloadOverlay.visibility = View.VISIBLE
        downloadTitleView.text = fileName
        downloadIconView.setImageResource(android.R.drawable.stat_notify_error)
        downloadIconView.setColorFilter(Color.parseColor("#ef4444"))
        downloadProgressBar.visibility = View.GONE

        downloadStatusView.setTextColor(Color.parseColor("#ef4444"))
        downloadStatusView.text = "Gagal: $errorMsg"
        downloadOpenButton.visibility = View.GONE

        autoDismissRunnable?.let { uiHandler.removeCallbacks(it) }
        autoDismissRunnable = Runnable {
            if (currentDownloadId == downloadId) {
                downloadOverlay.visibility = View.GONE
            }
        }
        uiHandler.postDelayed(autoDismissRunnable!!, 7000L)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun saveBlob(base64Data: String, fileName: String, mimeType: String) {
            saveBase64ToDownloads(base64Data, fileName, mimeType)
        }
    }

    fun saveBase64ToDownloads(base64Data: String, fileName: String, mimeType: String) {
        requestNotificationPermissionIfNeeded()
        val initialName = if (fileName.isNotBlank()) {
            sanitizeFileName(fileName)
        } else {
            resolveDownloadFileName(null, null, mimeType)
        }
        val notifId = (System.currentTimeMillis() % 100000).toInt() + 2000

        uiHandler.post {
            Toast.makeText(applicationContext, "Menyimpan $initialName...", Toast.LENGTH_SHORT).show()
            showInAppDownloadStarted(notifId, initialName)
        }

        Thread {
            val result = runCatching {
                val bytes = decodeBase64Bytes(base64Data)
                saveBytesToDownloads(
                    context = applicationContext,
                    bytes = bytes,
                    fileName = initialName,
                    mimeType = mimeType,
                    onProgress = { downloaded, total ->
                        uiHandler.post {
                            showInAppDownloadProgress(notifId, initialName, downloaded, total)
                        }
                    }
                )
            }

            uiHandler.post {
                result.onSuccess { dl ->
                    Toast.makeText(
                        applicationContext,
                        "Selesai mengunduh ${dl.fileName}",
                        Toast.LENGTH_LONG
                    ).show()
                    showDownloadNotification(applicationContext, notifId, dl)
                    showInAppDownloadSuccess(notifId, dl)
                }.onFailure { err ->
                    val msg = err.message ?: "Gagal menyimpan berkas"
                    Toast.makeText(
                        applicationContext,
                        "Gagal menyimpan $initialName: $msg",
                        Toast.LENGTH_LONG
                    ).show()
                    showDownloadErrorNotification(applicationContext, notifId, initialName, msg)
                    showInAppDownloadError(notifId, initialName, msg)
                }
            }
        }.start()
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback
        fileUploadCallback = null
        if (callback == null) return@registerForActivityResult
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        callback.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        System.loadLibrary("sqlcipher")

        requestNotificationPermissionIfNeeded()

        val server = ensureServerStarted()

        webView = object : WebView(this) {
            override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
                if (type == ActionMode.TYPE_FLOATING) {
                    return null
                }
                return super.startActionMode(callback, type)
            }

            override fun startActionModeForChild(
                child: View?,
                callback: ActionMode.Callback?,
                type: Int
            ): ActionMode? {
                if (type == ActionMode.TYPE_FLOATING) {
                    return null
                }
                return super.startActionModeForChild(child, callback, type)
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#18211f"))
            addJavascriptInterface(WebAppInterface(), "TermDeckApp")
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url ?: return false
                    val host = url.host?.lowercase()
                    if (host == "127.0.0.1" || host == "localhost") {
                        return false
                    }
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    }
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    val intent = try {
                        fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    } catch (_: Exception) {
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    }
                    return try {
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (_: Exception) {
                        fileUploadCallback = null
                        false
                    }
                }
            }

            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                requestNotificationPermissionIfNeeded()
                val initialName = resolveDownloadFileName(url, contentDisposition, mimeType)

                // 1. Tangani URI skema data: (data:...)
                if (url.startsWith("data:", ignoreCase = true)) {
                    val parsed = parseDataUri(url)
                    if (parsed != null) {
                        val b64 = java.util.Base64.getEncoder().encodeToString(parsed.first)
                        saveBase64ToDownloads(b64, initialName, parsed.second)
                    } else {
                        val notifId = (System.currentTimeMillis() % 100000).toInt() + 2000
                        showInAppDownloadError(notifId, initialName, "Format Data URI tidak valid")
                    }
                    return@setDownloadListener
                }

                // 2. Tangani URI skema blob: (blob:...) lewat evaluasi JavaScript
                if (url.startsWith("blob:", ignoreCase = true)) {
                    val js = """
                        (function() {
                            fetch('$url')
                                .then(function(r) { return r.blob(); })
                                .then(function(blob) {
                                    var reader = new FileReader();
                                    reader.onloadend = function() {
                                        var res = reader.result;
                                        if (res && window.TermDeckApp && window.TermDeckApp.saveBlob) {
                                            var comma = res.indexOf(',');
                                            var b64 = comma !== -1 ? res.substring(comma + 1) : res;
                                            window.TermDeckApp.saveBlob(b64, '$initialName', blob.type || '$mimeType');
                                        }
                                    };
                                    reader.readAsDataURL(blob);
                                })
                                .catch(function(e) {
                                    console.error('Blob download fetch error: ' + e);
                                });
                        })();
                    """.trimIndent()
                    uiHandler.post {
                        webView.evaluateJavascript(js, null)
                    }
                    return@setDownloadListener
                }

                val notifId = (System.currentTimeMillis() % 100000).toInt() + 2000
                Toast.makeText(applicationContext, "Mengunduh $initialName...", Toast.LENGTH_SHORT).show()
                uiHandler.post {
                    showInAppDownloadStarted(notifId, initialName)
                }

                Thread {
                    val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val wakeLock = powerManager?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "termdeck:download"
                    )?.apply {
                        setReferenceCounted(false)
                        runCatching { acquire(10 * 60 * 1000L) }
                    }

                    var lastUpdateMs = 0L
                    showDownloadProgressNotification(applicationContext, notifId, initialName, 0L, -1L)

                    val result = runCatching {
                        downloadFileDirectly(
                            context = applicationContext,
                            url = url,
                            userAgent = userAgent,
                            contentDisposition = contentDisposition,
                            mimeType = mimeType,
                            onProgress = { downloaded, total ->
                                val now = System.currentTimeMillis()
                                if (now - lastUpdateMs >= 300 || (total > 0L && downloaded >= total)) {
                                    lastUpdateMs = now
                                    showDownloadProgressNotification(applicationContext, notifId, initialName, downloaded, total)
                                    uiHandler.post {
                                        showInAppDownloadProgress(notifId, initialName, downloaded, total)
                                    }
                                }
                            }
                        )
                    }

                    runCatching {
                        if (wakeLock?.isHeld == true) {
                            wakeLock.release()
                        }
                    }

                    uiHandler.post {
                        result.onSuccess { dl ->
                            Toast.makeText(
                                applicationContext,
                                "Selesai mengunduh ${dl.fileName}",
                                Toast.LENGTH_LONG
                            ).show()
                            showDownloadNotification(applicationContext, notifId, dl)
                            showInAppDownloadSuccess(notifId, dl)
                        }.onFailure { err ->
                            val msg = err.message ?: "Terjadi kesalahan saat mengunduh"
                            Toast.makeText(
                                applicationContext,
                                "Gagal mengunduh $initialName: $msg",
                                Toast.LENGTH_LONG
                            ).show()
                            showDownloadErrorNotification(applicationContext, notifId, initialName, msg)
                            showInAppDownloadError(notifId, initialName, msg)
                        }
                    }
                }.start()
            }
        }

        activeWebView = WeakReference(webView)
        val overlayView = setupDownloadOverlay()
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                overlayView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    val marginHorizontal = dpToPx(16f)
                    val marginBottom = dpToPx(24f)
                    setMargins(marginHorizontal, 0, marginHorizontal, marginBottom)
                }
            )
        }
        setContentView(rootLayout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::downloadOverlay.isInitialized && downloadOverlay.visibility == View.VISIBLE && downloadOpenButton.visibility == View.VISIBLE) {
                    downloadOverlay.visibility = View.GONE
                    return
                }
                webView.evaluateJavascript(
                    """
                    (function() {
                      var modal = document.querySelector('.modal-backdrop, .fm-modal-backdrop');
                      if (modal) {
                        var btn = modal.querySelector('.fm-icon-btn, .icon-btn, button[aria-label="Tutup"], button[aria-label="Close"]');
                        if (btn) { btn.click(); return 'closed'; }
                        modal.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
                        return 'closed';
                      }
                      var backdrop = document.querySelector('.backdrop, .sidebar-backdrop');
                      if (backdrop) { backdrop.click(); return 'closed'; }
                      var pathCancel = document.querySelector('.fm-path-cancel');
                      if (pathCancel) { pathCancel.click(); return 'closed'; }
                      return 'none';
                    })()
                    """.trimIndent()
                ) { result ->
                    if (result != "\"closed\"") {
                        if (webView.canGoBack()) {
                            webView.goBack()
                        } else {
                            moveTaskToBack(true)
                        }
                    }
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(server.baseUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) {
            webView.saveState(outState)
        }
    }

    private fun ensureServerStarted(): TermDeckServer {
        synchronized(serverLock) {
            val existing = sharedServer
            if (existing != null) {
                existing.start()
                return existing
            }
            val appContext = applicationContext
            val dataFolder = File(appContext.filesDir, "termdeck-data")
            val vault = Vault(folder = dataFolder, initialPin = DEFAULT_INITIAL_PIN)
            val created = TermDeckServer(
                vault = vault,
                assetLoader = { relPath ->
                    try {
                        val assetPath = if (relPath == "files_agent.py") "files_agent.py" else "webapp/$relPath"
                        appContext.assets.open(assetPath).use { it.readBytes() }
                    } catch (_: Exception) {
                        null
                    }
                },
                onTerminalCountChanged = { count ->
                    val serviceIntent = Intent(appContext, TermDeckService::class.java).apply {
                        putExtra(TermDeckService.EXTRA_TERMINAL_COUNT, count)
                    }
                    runCatching {
                        if (count > 0) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                appContext.startForegroundService(serviceIntent)
                            } else {
                                appContext.startService(serviceIntent)
                            }
                        } else {
                            appContext.stopService(serviceIntent)
                        }
                    }
                },
                updater = AndroidUpdater(appContext)
            )
            created.start()
            sharedServer = created
            return created
        }
    }

    private fun ensureDownloadNotificationChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Unduhan TermDeck",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi status dan progres unduhan file dari remote server"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun showDownloadProgressNotification(
        context: Context,
        notifId: Int,
        fileName: String,
        downloaded: Long,
        total: Long
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureDownloadNotificationChannel(manager)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, DOWNLOAD_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        if (total > 0L) {
            val progressPercent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            builder.setProgress(100, progressPercent, false)
            builder.setContentText("${formatFileSize(downloaded)} / ${formatFileSize(total)} ($progressPercent%)")
        } else {
            builder.setProgress(0, 0, true)
            builder.setContentText("Mengunduh ${formatFileSize(downloaded)}...")
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(fileName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        manager.notify(notifId, notification)
    }

    private fun showDownloadNotification(context: Context, notifId: Int, dl: DownloadResult) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureDownloadNotificationChannel(manager)

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dl.uri, dl.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, DOWNLOAD_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(dl.fileName)
            .setContentText("Unduhan selesai (${formatFileSize(dl.sizeBytes)})")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(notifId, notification)
    }

    private fun showDownloadErrorNotification(
        context: Context,
        notifId: Int,
        fileName: String,
        errorMsg: String
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureDownloadNotificationChannel(manager)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, DOWNLOAD_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Gagal mengunduh $fileName")
            .setContentText(errorMsg)
            .setAutoCancel(true)
            .build()

        manager.notify(notifId, notification)
    }

    companion object {
        private const val DOWNLOAD_CHANNEL_ID = "termdeck_downloads_channel"
        private val serverLock = Any()
        private var sharedServer: TermDeckServer? = null
        private var activeWebView: WeakReference<WebView>? = null

        fun lockFromNotification() {
            Thread {
                val srv = synchronized(serverLock) { sharedServer }
                runCatching { srv?.lockVault() }
                Handler(Looper.getMainLooper()).post {
                    activeWebView?.get()?.reload()
                }
            }.start()
        }
    }
}
