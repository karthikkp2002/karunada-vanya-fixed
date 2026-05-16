package com.example.karunadavanyaa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingBar: ProgressBar
    private val mainHandler = Handler(Looper.getMainLooper())
    private val LOCATION_PERMISSION_CODE = 1001
    private val CAMERA_PERMISSION_CODE   = 1002
    private val TAG = "KarunadaVanyaa"

    // File chooser callback — required for <input type="file"> on Android 5+
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // Track whether the main page has finished loading (to avoid multi-firing spinner hide)
    private var mainPageLoaded = false

    // Launcher for the system file/media picker
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val result = uris.map { it }.toTypedArray()
            fileChooserCallback?.onReceiveValue(if (result.isEmpty()) null else result)
            fileChooserCallback = null
        }

    // Launcher for camera capture
    private var cameraUri: Uri? = null
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
            if (captured && cameraUri != null) {
                fileChooserCallback?.onReceiveValue(arrayOf(cameraUri!!))
            } else {
                fileChooserCallback?.onReceiveValue(null)
            }
            fileChooserCallback = null
            cameraUri = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView   = findViewById(R.id.webView)
        loadingBar = findViewById(R.id.loadingBar)

        setupWebView()
        requestLocationPermission()

        // Restore WebView state on rotation; only do full load on first create
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("file:///android_asset/index.html")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings

        // ── Core JS & storage ───────────────────────────────────────────
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        // ── File access (needed for file:///android_asset/ base URL) ────
        settings.allowFileAccess           = true
        settings.allowContentAccess        = true

        // ── ES module dynamic imports from file:// → https:// CDN ───────
        // Without these two flags, `import("https://...")` inside a file://
        // context throws a cross-origin security error in Android WebView.
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs      = true

        // ── Mixed content ────────────────────────────────────────────────
        // All CDN + Firebase resources are HTTPS; COMPATIBILITY_MODE is the
        // safest non-deprecated-unsafe option and permits HTTPS sub-resources.
        @Suppress("DEPRECATION")
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // ── Viewport ─────────────────────────────────────────────────────
        settings.useWideViewPort      = true
        settings.loadWithOverviewMode = true

        // ── Zoom (app manages its own layout) ────────────────────────────
        settings.setSupportZoom(false)
        settings.displayZoomControls  = false
        settings.builtInZoomControls  = false

        // ── Geolocation ───────────────────────────────────────────────────
        settings.setGeolocationEnabled(true)

        // ── Cache — prefer cached content, fall back to network ───────────
        // LOAD_CACHE_ELSE_NETWORK means the app stays usable offline for
        // already-cached assets (fonts, React, Firebase SDK).
        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK

        // ── Hardware acceleration for smooth CSS animations ───────────────
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // ── WebViewClient ─────────────────────────────────────────────────
        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Only show the native spinner for the main asset load
                if (url?.startsWith("file://") == true) {
                    mainPageLoaded = false
                    loadingBar.visibility = View.VISIBLE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Only hide the spinner once — when the main asset page finishes
                if (url?.startsWith("file://") == true && !mainPageLoaded) {
                    mainPageLoaded = true
                    // 600 ms gives React time to mount and replace the inline splash
                    // before the native spinner disappears, preventing a blank flash.
                    mainHandler.postDelayed({
                        loadingBar.visibility = View.GONE
                        view?.evaluateJavascript(
                            "(function(){" +
                            "  var s=document.getElementById('kv-splash');" +
                            "  if(s && s.parentNode===document.getElementById('app-root'))" +
                            "    s.remove();" +
                            "})();",
                            null
                        )
                    }, 600)
                }
            }

            // API ≤ 22 error handler (main-frame only)
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                @Suppress("DEPRECATION")
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (failingUrl?.startsWith("file://") == true) {
                    loadingBar.visibility = View.GONE
                    showNativeError(view, errorCode, description)
                }
            }

            // API ≥ 23 error handler
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Only surface errors for the main document; sub-resource failures
                // (CDN fonts, analytics) are handled gracefully in JS.
                if (request?.isForMainFrame == true) {
                    loadingBar.visibility = View.GONE
                    val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                        error?.errorCode else -1
                    val desc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                        error?.description?.toString() else "Load failed"
                    showNativeError(view, code ?: -1, desc)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // Schemes that should always open in system apps
                if (url.startsWith("tel:") || url.startsWith("mailto:") ||
                    url.startsWith("whatsapp:") || url.startsWith("sms:")) {
                    openExternalUrl(url)
                    return true
                }

                // Google Maps links → open in Maps/browser, not WebView
                if (url.contains("maps.google.com") || url.contains("maps.app.goo.gl")) {
                    openExternalUrl(url)
                    return true
                }

                // Google OAuth / accounts pages must NOT load inside WebView;
                // Firebase signInWithRedirect navigates here and needs a real browser.
                if (url.contains("accounts.google.com") ||
                    url.contains("firebaseapp.com/__/auth")) {
                    openExternalUrl(url)
                    return true
                }

                // Firebase / CDN / local file resources → load inside WebView
                val internalPrefixes = listOf(
                    "file://",
                    "googleapis.com",
                    "gstatic.com",
                    "firebaseio.com",
                    "firebaseapp.com",
                    "cdnjs.cloudflare.com",
                    "fonts.googleapis.com",
                    "fonts.gstatic.com",
                )
                if (internalPrefixes.any { url.contains(it) }) return false

                // Everything else (external websites) → open in system browser
                openExternalUrl(url)
                return true
            }
        }

        // ── WebChromeClient ───────────────────────────────────────────────
        webView.webChromeClient = object : WebChromeClient() {

            // Geolocation prompt — only grant if Android permission already held
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                callback?.invoke(origin, granted, false)
                if (!granted) requestLocationPermission()
            }

            // ── File chooser — required for <input type="file"> on Android 5+ ──
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel any previous pending callback to avoid leaking it
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes?.joinToString(",") ?: "*/*"
                val mimeType    = when {
                    acceptTypes.contains("image") -> "image/*"
                    acceptTypes.contains("video") -> "video/*"
                    else -> "*/*"
                }

                // Request camera permission if needed before showing chooser
                if (mimeType == "image/*" && ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.CAMERA),
                        CAMERA_PERMISSION_CODE
                    )
                }

                try {
                    filePickerLauncher.launch(mimeType)
                } catch (e: Exception) {
                    Log.e(TAG, "File picker failed: ${e.message}")
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    return false
                }
                return true
            }

            // Camera/mic permission requests from the web page
            override fun onPermissionRequest(request: PermissionRequest?) {
                val allowed = mutableListOf<String>()
                request?.resources?.forEach { res ->
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                            if (ContextCompat.checkSelfPermission(
                                    this@MainActivity, Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                            ) allowed.add(res)
                        }
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                            // Not used by this app — deny silently
                        }
                    }
                }
                if (allowed.isNotEmpty()) request?.grant(allowed.toTypedArray())
                else request?.deny()
            }

            // Forward WebView console messages to Logcat for debugging
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val level = consoleMessage?.messageLevel()
                val msg   = consoleMessage?.message() ?: return false
                val line  = consoleMessage.lineNumber()
                val src   = consoleMessage.sourceId() ?: ""
                when (level) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, "JS [$src:$line] $msg")
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "JS [$src:$line] $msg")
                    else -> Log.d(TAG, "JS [$src:$line] $msg")
                }
                return true
            }
        }
    }

    /** Inject a user-friendly error page into the WebView. */
    private fun showNativeError(view: WebView?, errorCode: Int?, description: String?) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8"/>
              <meta name="viewport" content="width=device-width,initial-scale=1"/>
              <style>
                *{box-sizing:border-box;margin:0;padding:0;}
                body{display:flex;flex-direction:column;align-items:center;justify-content:center;
                     min-height:100vh;background:#040f07;color:#e8f5ef;
                     font-family:sans-serif;text-align:center;padding:24px;}
                .icon{font-size:56px;margin-bottom:20px;}
                h2{font-size:20px;margin-bottom:10px;}
                p{font-size:13px;color:#9dbfad;margin-bottom:24px;line-height:1.6;max-width:280px;}
                button{padding:12px 28px;background:linear-gradient(135deg,#22c55e,#16a34a);
                       color:#fff;border:none;border-radius:12px;font-size:15px;
                       font-weight:600;cursor:pointer;}
              </style>
            </head>
            <body>
              <div class="icon">🌿</div>
              <h2>Could not load the app</h2>
              <p>Error $errorCode: $description<br/>Please check your connection and try again.</p>
              <button onclick="window.location.reload()">Try Again</button>
            </body>
            </html>
        """.trimIndent()
        view?.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    /** Open a URL in the system browser / app. Swallows ActivityNotFoundException. */
    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.w(TAG, "No app to handle URL: $url")
        }
    }

    private fun requestLocationPermission() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, permissionsNeeded.toTypedArray(), LOCATION_PERMISSION_CODE
            )
        }
    }

    // Back navigation — go back in WebView history if available
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // Pause / resume WebView with the Activity lifecycle
    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        // Cancel any pending handler callbacks to prevent leaks
        mainHandler.removeCallbacksAndMessages(null)
        // Cancel any pending file chooser callback
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        // Detach and destroy WebView cleanly
        webView.stopLoading()
        webView.webViewClient   = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.destroy()
        super.onDestroy()
    }
}
