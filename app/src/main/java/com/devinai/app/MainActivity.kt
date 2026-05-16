package com.devinai.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.devinai.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var childWebView: WebView? = null

    private var isPageLoaded = false
    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null

    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private var isNetworkCallbackRegistered = false

    companion object {
        private const val DEVIN_URL = "https://app.devin.ai/"
        private const val WEB_VIEW_STATE_KEY = "webview_state"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !isPageLoaded }

        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerActivityResults()
        setupWebView()
        setupBackNavigation()
        setupRetryButton()
        setupNetworkMonitor()

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                webView.onResume()
            }

            override fun onPause(owner: LifecycleOwner) {
                webView.onPause()
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            if (isNetworkAvailable()) {
                webView.loadUrl(DEVIN_URL)
            } else {
                showNoInternetView()
            }
        }
    }

    private fun registerActivityResults() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            val results = if (result.resultCode == RESULT_OK && data != null) {
                val clipData = data.clipData
                if (clipData != null) {
                    Array(clipData.itemCount) { clipData.getItemAt(it).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            } else {
                null
            }
            fileUploadCallback?.onReceiveValue(results ?: emptyArray())
            fileUploadCallback = null
        }

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            pendingPermissionCallback?.invoke(allGranted)
            pendingPermissionCallback = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = binding.webView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            userAgentString = webView.settings.userAgentString.replace("; wv", "")

            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = DevinWebViewClient()
        webView.webChromeClient = DevinWebChromeClient()

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    private fun injectInputFixes() {
        val js = """
            (function() {
                function fixInput(el) {
                    el.setAttribute('spellcheck', 'false');
                    el.setAttribute('autocorrect', 'off');
                    el.setAttribute('autocomplete', 'off');
                    el.setAttribute('autocapitalize', 'off');
                }
                document.addEventListener('focusin', function(e) {
                    var el = e.target;
                    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable || el.contentEditable === 'true') {
                        fixInput(el);
                    }
                }, true);
                var observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(m) {
                        m.addedNodes.forEach(function(node) {
                            if (node.nodeType === 1) {
                                if (node.tagName === 'INPUT' || node.tagName === 'TEXTAREA' || node.isContentEditable) {
                                    fixInput(node);
                                }
                                node.querySelectorAll && node.querySelectorAll('input, textarea, [contenteditable=true]').forEach(fixInput);
                            }
                        });
                    });
                });
                observer.observe(document.body, { childList: true, subtree: true });
                document.querySelectorAll('input, textarea, [contenteditable=true]').forEach(fixInput);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectSwipeToCloseSidebar() {
        val js = """
            (function() {
                var startX = 0, startY = 0;
                document.addEventListener('touchstart', function(e) {
                    startX = e.touches[0].clientX;
                    startY = e.touches[0].clientY;
                }, { passive: true });
                document.addEventListener('touchend', function(e) {
                    var endX = e.changedTouches[0].clientX;
                    var endY = e.changedTouches[0].clientY;
                    var dx = endX - startX;
                    var dy = Math.abs(endY - startY);
                    if (dx < -80 && dy < 100) {
                        var sidebar = document.querySelector('nav') || document.querySelector('[class*="sidebar"]') || document.querySelector('[class*="Sidebar"]') || document.querySelector('[class*="drawer"]');
                        if (sidebar) {
                            var closeBtn = sidebar.querySelector('button[aria-label*="close"]') || sidebar.querySelector('button[aria-label*="Close"]') || sidebar.querySelector('[class*="close"]');
                            if (closeBtn) closeBtn.click();
                        }
                    }
                }, { passive: true });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    childWebView != null -> destroyChildWebView()
                    webView.canGoBack() -> webView.goBack()
                    else -> showExitConfirmation()
                }
            }
        })
    }

    private fun showExitConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.exit_confirmation))
            .setNegativeButton(getString(R.string.no)) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(getString(R.string.yes)) { _, _ -> finish() }
            .show()
    }

    private fun loadOrReload() {
        if (webView.url.isNullOrBlank()) {
            webView.loadUrl(DEVIN_URL)
        } else {
            webView.reload()
        }
    }

    private fun setupRetryButton() {
        binding.btnRetry.setOnClickListener {
            if (isNetworkAvailable()) {
                hideNoInternetView()
                loadOrReload()
            } else {
                Toast.makeText(this, R.string.still_offline, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupNetworkMonitor() {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (binding.noInternetView.visibility == View.VISIBLE) {
                        hideNoInternetView()
                        loadOrReload()
                    }
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    if (!isNetworkAvailable()) {
                        showNoInternetView()
                    }
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        isNetworkCallbackRegistered = true
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun showNoInternetView() {
        binding.noInternetView.visibility = View.VISIBLE
        binding.webViewContainer.visibility = View.GONE
    }

    private fun hideNoInternetView() {
        binding.noInternetView.visibility = View.GONE
        binding.webViewContainer.visibility = View.VISIBLE
    }

    private fun isAllowedUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: return false
        return host == "app.devin.ai" || host == "devin.ai" || host.endsWith(".devin.ai")
    }

    private fun openExternalBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                setDescription(getString(R.string.downloading))
                setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                )
            }

            val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPermissions(
        permissions: Array<String>,
        callback: (Boolean) -> Unit
    ) {
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            callback(true)
            return
        }
        pendingPermissionCallback = callback
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        binding.fullscreenContainer.addView(view)
        binding.fullscreenContainer.visibility = View.VISIBLE
        binding.webViewContainer.visibility = View.GONE

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hideCustomView() {
        customView ?: return
        binding.fullscreenContainer.removeAllViews()
        binding.fullscreenContainer.visibility = View.GONE
        binding.webViewContainer.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun destroyChildWebView() {
        childWebView?.let { child ->
            binding.webViewContainer.removeView(child)
            child.destroy()
        }
        childWebView = null
        webView.visibility = View.VISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        if (isNetworkCallbackRegistered) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
            isNetworkCallbackRegistered = false
        }
        destroyChildWebView()
        webView.destroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        webView.freeMemory()
    }

    // ── Custom WebViewClient ──────────────────────────────────────────────

    private inner class DevinWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url.toString()

            if (isAllowedUrl(url)) return false
            if (isAuthUrl(url)) return false

            openExternalBrowser(url)
            return true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            isPageLoaded = true
            CookieManager.getInstance().flush()
            injectInputFixes()
            injectSwipeToCloseSidebar()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame && !isNetworkAvailable()) {
                showNoInternetView()
            }
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: android.webkit.SslErrorHandler,
            error: android.net.http.SslError
        ) {
            handler.cancel()
        }

        private fun isAuthUrl(url: String): Boolean {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            return host.contains("accounts.google.com") ||
                    host.contains("login.microsoftonline.com") ||
                    host.contains("auth0.com") ||
                    host.contains("cognition")
        }
    }

    // ── Custom WebChromeClient ────────────────────────────────────────────

    private inner class DevinWebChromeClient : WebChromeClient() {

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = filePathCallback

            try {
                val intent = fileChooserParams.createIntent()
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                fileChooserLauncher.launch(intent)
            } catch (e: Exception) {
                fileUploadCallback?.onReceiveValue(emptyArray())
                fileUploadCallback = null
                return false
            }
            return true
        }

        override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
            val androidPermissions = mutableListOf<String>()
            request.resources.forEach { resource ->
                when (resource) {
                    android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        androidPermissions.add(Manifest.permission.CAMERA)
                    android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        androidPermissions.add(Manifest.permission.RECORD_AUDIO)
                }
            }

            if (androidPermissions.isEmpty()) {
                request.grant(request.resources)
                return
            }

            requestPermissions(androidPermissions.toTypedArray()) { granted ->
                if (granted) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            showCustomView(view, callback)
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            val newWebView = WebView(this@MainActivity).apply {
                @SuppressLint("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportMultipleWindows(true)
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.userAgentString = webView.settings.userAgentString

                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val url = request.url.toString()
                        if (isAllowedUrl(url)) {
                            destroyChildWebView()
                            this@MainActivity.webView.loadUrl(url)
                            return true
                        }
                        if (isAuthUrl(url)) return false
                        openExternalBrowser(url)
                        destroyChildWebView()
                        return true
                    }

                    private fun isAuthUrl(url: String): Boolean {
                        val host = Uri.parse(url).host?.lowercase() ?: return false
                        return host.contains("accounts.google.com") ||
                                host.contains("login.microsoftonline.com") ||
                                host.contains("auth0.com") ||
                                host.contains("cognition")
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        CookieManager.getInstance().flush()
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) {
                        destroyChildWebView()
                    }
                }
            }

            childWebView = newWebView
            webView.visibility = View.GONE
            binding.webViewContainer.addView(
                newWebView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = newWebView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            destroyChildWebView()
        }
    }
}
