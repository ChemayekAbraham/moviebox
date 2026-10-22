package com.moviebox.webview

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.moviebox.webview.databinding.ActivityMainBinding
import com.moviebox.webview.databinding.ItemDownloadBinding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var chromeClient: VideoWebChromeClient

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastBackPressTime: Long = 0
    private var lastBlockToast: Long = 0

    private val mirrorUrls = listOf(
        "https://officialmoviebox.com",
        "https://themoviebox.org",
        "https://movieboxonline.net"
    )
    private var currentMirrorIndex = 0

    /** Background pool used to look up file sizes (Content-Length) before a download starts. */
    private val sizeProbeExecutor = Executors.newFixedThreadPool(3)
    @Volatile private var appUserAgent: String? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val downloadsRefresher = object : Runnable {
        override fun run() {
            renderDownloads()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback != null) {
            val results: Array<Uri>? = if (result.resultCode == RESULT_OK) {
                result.data?.let { data ->
                    val uri = data.data
                    val clipData = data.clipData
                    when {
                        uri != null -> arrayOf(uri)
                        clipData != null -> Array(clipData.itemCount) { i ->
                            clipData.getItemAt(i).uri
                        }
                        else -> null
                    }
                }
            } else {
                null
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupListeners()
        setupBottomNav()
        setupBackNavigation()
        warnIfWebViewOutdated()
        if (isNetworkAvailable()) UpdateChecker.check(this)

        // Remove any profile data that previously leaked onto betting/ad sites,
        // then inject the VIP profile ONLY on MovieBox domains and start loading.
        purgeLeakedSiteData {
            HostPolicy.MOVIEBOX_HOSTS.forEach { VIPAccountInjector.injectCookies(it) }
            if (isNetworkAvailable()) {
                loadUrl(mirrorUrls[currentMirrorIndex])
            } else {
                // No internet: go straight to the Me tab so downloads can be watched offline
                binding.bottomNav.selectedItemId = R.id.nav_me
                loadUrl(mirrorUrls[currentMirrorIndex])
            }
        }
    }

    /**
     * Older builds injected the DJ SHIRLEY BEATS profile into every page, including
     * betting sites that hijacked the WebView. This wipes storage for every non-MovieBox
     * origin, and (once) clears all cookies so nothing remains on those sites.
     */
    private fun purgeLeakedSiteData(onDone: () -> Unit) {
        WebStorage.getInstance().getOrigins { origins ->
            origins?.keys?.forEach { key ->
                val origin = key?.toString() ?: return@forEach
                if (!HostPolicy.isMovieBoxHost(origin)) {
                    WebStorage.getInstance().deleteOrigin(origin)
                }
            }
        }

        val prefs = getSharedPreferences("app_state", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("leak_cleanup_v2", false)) {
            binding.webView.clearCache(true)
            CookieManager.getInstance().removeAllCookies {
                prefs.edit().putBoolean("leak_cleanup_v2", true).apply()
                onDone()
            }
        } else {
            onDone()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        // Enable JavaScript and modern web features
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        // Smooth media playback
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Cache strategy
        settings.cacheMode = if (isNetworkAvailable()) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        // Enable Cookies (needed by embedded MovieBox players)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Custom User Agent for maximum compatibility
        val defaultUserAgent = settings.userAgentString
        webViewMajorVersion = Regex("Chrome/(\\d+)").find(defaultUserAgent ?: "")
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        settings.userAgentString = "$defaultUserAgent MovieboxApp/1.0"
        appUserAgent = settings.userAgentString

        // Popups are handled by onCreateWindow (blocked); JS may not open windows on its own
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = false

        // Enable remote debugging
        WebView.setWebContentsDebuggingEnabled(true)

        // Setup WebChromeClient with fullscreen video support
        chromeClient = VideoWebChromeClient(
            activity = this,
            mainContainer = binding.swipeRefreshLayout,
            bottomNav = binding.bottomNav,
            fullscreenContainer = binding.fullscreenContainer,
            progressBar = binding.progressBar,
            onFileChooser = { callback, intent ->
                filePathCallback = callback
                if (intent != null) {
                    fileChooserLauncher.launch(intent)
                }
            }
        )
        webView.webChromeClient = chromeClient

        // Setup WebViewClient with ad interception and VIP injection
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (AdBlocker.isAdOrUpdate(url) || isGamblingUrl(url)) {
                    return AdBlocker.createEmptyResponse()
                }
                // Scripts / iframes from unknown ad networks (e.g. fake "not a robot" pop-ups)
                if (!request.isForMainFrame && AdBlocker.isUntrustedThirdParty(request)) {
                    android.util.Log.d("AdBlock", "Blocked third-party: $url")
                    return AdBlocker.createEmptyResponse()
                }
                // Main page may only ever be a trusted site
                if (request.isForMainFrame && HostPolicy.isWebUrl(url) &&
                    !HostPolicy.isNavigationAllowed(url)) {
                    return AdBlocker.createEmptyResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (isBlockedPage(url)) {
                    bounceBack(view)
                    return
                }
                binding.layoutError.visibility = View.GONE
                injectScripts(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefreshLayout.isRefreshing = false
                if (!isBlockedPage(url)) injectScripts(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (isBlockedPage(url)) {
                    bounceBack(view)
                } else {
                    injectScripts(view, url)
                    syncNavWithPage(url)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleNavigation(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleNavigation(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    if (!isNetworkAvailable()) {
                        showErrorScreen()
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 500) {
                    showErrorScreen()
                }
            }
        }

        // Handle downloads (movies, videos, attachments) - strictly block APK updates
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            // Block all external APK updates and installer packages
            if (url.endsWith(".apk", ignoreCase = true) ||
                mimeType?.equals("application/vnd.android.package-archive", ignoreCase = true) == true ||
                AdBlocker.isAdOrUpdate(url)) {
                Toast.makeText(
                    this,
                    "External app updates are disabled. Updates are managed only via Antigravity or Android Studio.",
                    Toast.LENGTH_LONG
                ).show()
                return@setDownloadListener
            }

            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            startDownload(url, fileName, mimeType, userAgent, binding.webView.url)
        }

        // Bridge used by the player's "Download This Video" button
        webView.addJavascriptInterface(DownloadBridge(), VideoDownloadInjector.BRIDGE_NAME)
    }

    private inner class DownloadBridge {
        @JavascriptInterface
        fun download(src: String, title: String?, quality: String?, pageUrl: String?) =
            enqueueFromPage(src, title, quality, pageUrl, quiet = false)

        /** Used for batch episode downloads: no toast per item (the page shows one summary). */
        @JavascriptInterface
        fun downloadQuiet(src: String, title: String?, quality: String?, pageUrl: String?) =
            enqueueFromPage(src, title, quality, pageUrl, quiet = true)

        private fun enqueueFromPage(src: String, title: String?, quality: String?, pageUrl: String?, quiet: Boolean) {
            runOnUiThread {
                if (!HostPolicy.isMovieBoxHost(binding.webView.url)) return@runOnUiThread
                val base = (title?.takeIf { it.isNotBlank() } ?: "MovieBox video")
                    .replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
                val q = quality?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
                val ext = if (src.substringBefore('?').endsWith(".mkv", true)) "mkv" else "mp4"
                startDownload(
                    url = src,
                    fileName = "$base$q.$ext",
                    mimeType = if (ext == "mkv") "video/x-matroska" else "video/mp4",
                    userAgent = binding.webView.settings.userAgentString,
                    referer = pageUrl,
                    quiet = quiet
                )
            }
        }

        @JavascriptInterface
        fun notify(message: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }

        /**
         * Looks up the size of [url] in the background and reports it back to the page via
         * `window.__mbSizeCb(token, bytes)` (bytes = -1 when the server doesn't say).
         */
        @JavascriptInterface
        fun probeSize(token: String, url: String, referer: String?) {
            sizeProbeExecutor.execute {
                val size = fetchContentLength(url, appUserAgent, referer)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    binding.webView.evaluateJavascript(
                        "window.__mbSizeCb && window.__mbSizeCb(${JSONObject.quote(token)}, $size)", null
                    )
                }
            }
        }

        /** Free bytes on the storage volume downloads are saved to. */
        @JavascriptInterface
        fun freeSpace(): Long = try {
            (getExternalFilesDir(null) ?: filesDir).usableSpace
        } catch (e: Exception) {
            0L
        }
    }

    /** Returns the file size in bytes reported by the server, or -1 if unknown. */
    private fun fetchContentLength(url: String, userAgent: String?, referer: String?): Long {
        fun open(method: String, rangeProbe: Boolean): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                userAgent?.let { setRequestProperty("User-Agent", it) }
                CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
                referer?.let {
                    setRequestProperty("Referer", it)
                    Uri.parse(it).let { u -> setRequestProperty("Origin", "${u.scheme}://${u.host}") }
                }
                if (rangeProbe) setRequestProperty("Range", "bytes=0-0")
            }

        // 1) HEAD request – cheapest, no body transferred
        try {
            val c = open("HEAD", rangeProbe = false)
            try {
                if (c.responseCode in 200..299 && c.contentLengthLong > 0) return c.contentLengthLong
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) { }

        // 2) Some CDNs reject HEAD: ask for a single byte and read the total from Content-Range
        try {
            val c = open("GET", rangeProbe = true)
            try {
                val total = c.getHeaderField("Content-Range")
                    ?.substringAfter('/', "")?.trim()?.toLongOrNull()
                if (total != null && total > 0) return total
                if (c.responseCode == 200 && c.contentLengthLong > 0) return c.contentLengthLong
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) { }

        return -1
    }

    private fun startDownload(
        url: String,
        fileName: String,
        mimeType: String?,
        userAgent: String?,
        referer: String?,
        quiet: Boolean = false
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                userAgent?.let { addRequestHeader("User-Agent", it) }
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                referer?.let {
                    addRequestHeader("Referer", it)
                    addRequestHeader("Origin", Uri.parse(it).let { u -> "${u.scheme}://${u.host}" })
                }
                setTitle(fileName)
                setDescription(getString(R.string.download_started))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MovieBox/$fileName")
            }

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = downloadManager.enqueue(request)
            DownloadTracker.track(this, id, fileName)
            if (!quiet) {
                Toast.makeText(this, "⬇ Downloading \"$fileName\" — track it in the Me tab", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to download: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** VIP profile is injected ONLY on MovieBox pages; ad cleaning runs on every trusted page. */
    private fun injectScripts(view: WebView?, url: String?) {
        if (HostPolicy.isMovieBoxHost(url)) {
            view?.evaluateJavascript(VIPAccountInjector.INJECTION_SCRIPT, null)
            view?.evaluateJavascript(VideoDownloadInjector.SCRIPT, null)
            view?.evaluateJavascript(PlayerAutoHideFix.SCRIPT, null)
            // Self-gated: only acts if the site's own JavaScript failed to start (old WebView)
            view?.evaluateJavascript(LegacyWebViewFix.SCRIPT, null)
        }
        view?.evaluateJavascript(AdBlocker.AD_CLEANING_JS, null)
    }

    private fun isBlockedPage(url: String?): Boolean {
        if (url == null) return false
        return isGamblingUrl(url) || AdBlocker.isAdOrUpdate(url) ||
                (HostPolicy.isWebUrl(url) && !HostPolicy.isNavigationAllowed(url))
    }

    private fun bounceBack(view: WebView?) {
        view?.stopLoading()
        if (view?.canGoBack() == true) view.goBack() else view?.loadUrl(mirrorUrls[currentMirrorIndex])
        notifyBlocked()
    }

    private fun notifyBlocked() {
        val now = System.currentTimeMillis()
        if (now - lastBlockToast > 4000) {
            lastBlockToast = now
            Toast.makeText(this, "🛡️ Ad redirect blocked", Toast.LENGTH_SHORT).show()
        }
    }

    /** Returns true when the navigation was handled (blocked or sent to another app). */
    private fun handleNavigation(url: String): Boolean {
        if (AdBlocker.isAdOrUpdate(url) || isGamblingUrl(url)) {
            notifyBlocked()
            return true
        }
        val host = HostPolicy.hostOf(url)
        // Allow opening Netflix or Plex in external app if available
        if (host.contains("netflix.com") || host.contains("plex.tv")) {
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            } catch (e: Exception) {
                false // Let WebView load it if no external app installed
            }
        }
        if (HostPolicy.isWebUrl(url)) {
            if (!HostPolicy.isNavigationAllowed(url)) {
                // Betting / ad / unknown site: never load it in the app
                notifyBlocked()
                return true
            }
            return false // Trusted site: let WebView load it
        }
        return handleCustomScheme(url)
    }

    private fun isGamblingUrl(url: String?): Boolean {
        if (url == null) return false
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: ""
        val path = uri.path?.lowercase(java.util.Locale.ROOT) ?: ""
        return host.contains("1xbet") ||
               host.contains("1x-bet") ||
               host.contains("1x00") ||
               host.contains("waje.bet") ||
               host.contains("sportsnow.top") ||
               host.contains("show-sb.com") ||
               host.endsWith(".bet") ||
               host.contains("casino") ||
               path.startsWith("/games") ||
               path.startsWith("/live/list")
    }

    private fun handleCustomScheme(url: String): Boolean {
        // intent:// links are how most ads try to launch the Play Store / betting apps
        if (url.startsWith("intent:") || url.startsWith("market:")) {
            notifyBlocked()
            return true
        }
        // Other app links (e.g., whatsapp:, telegram:, tel:, mailto:)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // Ignored if app is not installed
        }
        return true
    }

    private fun setupListeners() {
        binding.swipeRefreshLayout.setColorSchemeResources(R.color.primary)
        binding.swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.surface_card)
        binding.swipeRefreshLayout.setOnRefreshListener {
            binding.webView.reload()
        }

        binding.btnRetry.setOnClickListener {
            binding.layoutError.visibility = View.GONE
            binding.webView.reload()
        }

        binding.btnSwitchMirror.setOnClickListener {
            currentMirrorIndex = (currentMirrorIndex + 1) % mirrorUrls.size
            val newUrl = mirrorUrls[currentMirrorIndex]
            Toast.makeText(this, "Switching to: $newUrl", Toast.LENGTH_SHORT).show()
            loadUrl(newUrl)
        }

        binding.btnOfflineDownloads.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_me
        }
    }

    // ---------------------------------------------------------------------------------------
    // Bottom navigation + Me tab
    // ---------------------------------------------------------------------------------------

    private fun setupBottomNav() {
        binding.meScreen.tvProfileName.text = VIPAccountInjector.USER_NAME
        binding.meScreen.tvProfileId.text = "ID: Enjoy the App  •  Premium • Unlimited"
        binding.meScreen.tvProfileExpiry.text = "Expires: 30th JAN 3000"

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == R.id.nav_me) {
                showMeTab(true)
            } else {
                val cameFromMe = isMeTabVisible
                showMeTab(false)
                val alreadyThere = sectionFor(binding.webView.url) == item.itemId
                if (!syncingNav && !(cameFromMe && alreadyThere)) {
                    openSection(item.itemId)
                }
            }
            true
        }
        binding.bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId != R.id.nav_me) openSection(item.itemId)
        }
    }

    private var syncingNav = false

    private fun sectionPath(itemId: Int) = when (itemId) {
        R.id.nav_movie -> "/newWeb/movie"
        R.id.nav_tv -> "/newWeb/tv-series"
        else -> "/"
    }

    private fun sectionFor(url: String?): Int {
        val path = try { Uri.parse(url ?: "").path ?: "" } catch (e: Exception) { "" }
        return when {
            path.startsWith("/newWeb/movie") -> R.id.nav_movie
            path.startsWith("/newWeb/tv-series") -> R.id.nav_tv
            else -> R.id.nav_home
        }
    }

    private fun openSection(itemId: Int) {
        val current = binding.webView.url
        val base = if (HostPolicy.isMovieBoxHost(current)) {
            Uri.parse(current).let { "${it.scheme}://${it.host}" }
        } else {
            mirrorUrls[currentMirrorIndex]
        }
        loadUrl(base + sectionPath(itemId))
    }

    /** Highlight the tab that matches the page currently shown, without navigating. */
    private fun syncNavWithPage(url: String?) {
        if (isMeTabVisible) return
        val target = sectionFor(url)
        if (binding.bottomNav.selectedItemId != target) {
            syncingNav = true
            binding.bottomNav.selectedItemId = target
            syncingNav = false
        }
    }

    private val isMeTabVisible get() = binding.meScreen.root.visibility == View.VISIBLE

    private fun showMeTab(show: Boolean) {
        binding.meScreen.root.visibility = if (show) View.VISIBLE else View.GONE
        binding.swipeRefreshLayout.visibility = if (show) View.GONE else View.VISIBLE
        uiHandler.removeCallbacks(downloadsRefresher)
        if (show) {
            binding.layoutError.visibility = View.GONE
            uiHandler.post(downloadsRefresher)
        } else if (!isNetworkAvailable()) {
            showErrorScreen()
        }
    }

    private fun renderDownloads() {
        val items = DownloadTracker.list(this)
        val me = binding.meScreen
        val container = me.downloadsContainer

        me.tvStatDownloading.text = items.count { it.isRunning }.toString()
        me.tvStatOffline.text = items.count { it.isComplete }.toString()
        me.tvStatStorage.text = Formatter.formatShortFileSize(
            this, items.filter { it.isComplete }.sumOf { it.totalBytes.coerceAtLeast(0) }
        )
        me.layoutDownloadsEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        // Reuse rows when the list shape is unchanged so progress updates don't flicker
        if (container.childCount != items.size ||
            items.withIndex().any { (i, it) -> container.getChildAt(i).tag != it.id }) {
            container.removeAllViews()
            items.forEach { item ->
                val row = ItemDownloadBinding.inflate(layoutInflater, container, false)
                row.root.tag = item.id
                container.addView(row.root)
            }
        }

        items.forEachIndexed { i, item ->
            val row = ItemDownloadBinding.bind(container.getChildAt(i))
            row.tvTitle.text = item.title
            row.pbDownload.progress = if (item.isComplete) 100 else item.progress
            row.pbDownload.visibility = if (item.isComplete) View.GONE else View.VISIBLE
            val size = Formatter.formatShortFileSize(this, item.totalBytes.coerceAtLeast(0))
            val done = Formatter.formatShortFileSize(this, item.downloadedBytes.coerceAtLeast(0))
            row.tvStatus.text = when {
                item.isComplete -> "✅ Ready to watch offline • $size"
                item.isFailed -> "❌ Download failed"
                item.status == DownloadManager.STATUS_PAUSED -> "⏸ Paused (waiting for network) • $done"
                item.status == DownloadManager.STATUS_PENDING -> "⏳ Queued…"
                else -> "⬇ ${item.progress}% • $done / $size"
            }
            row.ivState.alpha = if (item.isComplete) 1f else 0.35f
            row.root.setOnClickListener {
                if (item.isComplete) playOffline(item)
                else Toast.makeText(this, "Still downloading (${item.progress}%)", Toast.LENGTH_SHORT).show()
            }
            row.btnDelete.setOnClickListener { confirmDelete(item) }
        }
    }

    private fun playOffline(item: DownloadTracker.Item) {
        val uri = DownloadTracker.playableUri(this, item.id)
        if (uri == null) {
            Toast.makeText(this, "File not found — it may have been deleted", Toast.LENGTH_SHORT).show()
            return
        }
        val isVideo = item.mimeType?.startsWith("video") == true ||
                item.title.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".webm") || it.endsWith(".3gp") }
        if (isVideo) {
            startActivity(
                Intent(this, OfflinePlayerActivity::class.java)
                    .setData(uri)
                    .putExtra(OfflinePlayerActivity.EXTRA_TITLE, item.title)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } else {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, item.mimeType ?: "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (e: Exception) {
                Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete(item: DownloadTracker.Item) {
        AlertDialog.Builder(this)
            .setTitle(if (item.isComplete) "Delete download?" else "Cancel download?")
            .setMessage(item.title)
            .setPositiveButton("Delete") { _, _ ->
                DownloadTracker.delete(this, item.id)
                renderDownloads()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    chromeClient.isFullscreen -> {
                        chromeClient.onHideCustomView()
                    }
                    isMeTabVisible -> {
                        // Return to the page you were on, without reloading it
                        syncingNav = true
                        binding.bottomNav.selectedItemId = sectionFor(binding.webView.url)
                        syncingNav = false
                    }
                    binding.webView.canGoBack() -> {
                        binding.webView.goBack()
                    }
                    else -> {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastBackPressTime < 2000) {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        } else {
                            lastBackPressTime = currentTime
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.press_again_to_exit),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
    }

    /** Chrome major version of the device's WebView (0 if unknown). */
    private var webViewMajorVersion = 0

    /**
     * The MovieBox site needs a modern WebView. On older ones its JavaScript never starts, so
     * thumbnails stay blank, the banner freezes on its first movie and the player won't work.
     */
    private fun warnIfWebViewOutdated() {
        val version = webViewMajorVersion
        if (version == 0 || version >= LegacyWebViewFix.MIN_WEBVIEW_VERSION) return

        val webViewPackage = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()?.packageName
        } else null) ?: "com.google.android.webview"

        AlertDialog.Builder(this)
            .setTitle("Update required")
            .setMessage(
                "Your phone's Android System WebView is outdated (version $version). " +
                "Movie thumbnails and playback won't work properly until it is updated.\n\n" +
                "Tap Update, install the update from the Play Store, then reopen MovieBox."
            )
            .setPositiveButton("Update") { _, _ -> openStorePage(webViewPackage) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun openStorePage(packageName: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
            } catch (e: Exception) {
                Toast.makeText(this, "Please update \"Android System WebView\" from the Play Store", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadUrl(url: String) {
        binding.layoutError.visibility = View.GONE
        binding.webView.loadUrl(url)
    }

    private fun showErrorScreen() {
        binding.swipeRefreshLayout.isRefreshing = false
        if (!isMeTabVisible) {
            binding.layoutError.visibility = View.VISIBLE
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        UpdateChecker.onResume(this)
        if (isMeTabVisible) {
            uiHandler.removeCallbacks(downloadsRefresher)
            uiHandler.post(downloadsRefresher)
        }
    }

    override fun onPause() {
        uiHandler.removeCallbacks(downloadsRefresher)
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(downloadsRefresher)
        sizeProbeExecutor.shutdownNow()
        binding.webView.destroy()
        super.onDestroy()
    }
}
