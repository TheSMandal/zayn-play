package app.netlify.zaynplay

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Zayn Play — UI-only build (v0.1.0).
 *
 * The whole app UI is the approved preview (assets/zaynplay-app.html),
 * shown in a single WebView. There is no engine here: no providers, no
 * real player, no downloads — sample data only, exactly like the preview.
 * The engine phase comes later, after the user
 * approves this UI.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        // Follow the device theme; the WebView's prefers-color-scheme then
        // matches the system, and the in-app theme setting (System/Dark/
        // Light in the HTML) switches the UI live.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)
        // TV / tablet open in landscape by default, phone stays portrait.
        // The page gets the same form factor (?form=...) so it can switch
        // to the tablet / TV layout.
        val form = formFactor()
        requestedOrientation = if (form == "phone") ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        webView.setBackgroundColor(currentBackgroundColor())
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.webViewClient = object : WebViewClient() {
            // Sample links inside the UI (provider sites, repo pages) open
            // in the browser; the app UI itself stays in the WebView.
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url ?: return false
                if (url.scheme == "http" || url.scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
                    return true
                }
                return false
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                message?.let { Log.d("ZaynPlay", "${it.sourceId()}:${it.lineNumber()} ${it.message()}") }
                return true
            }
        }

        // True fullscreen for the player: the page's CSS-only fullscreen
        // rotates the stage but cannot hide the Android status/nav bars,
        // so the player calls this from setFS() when it enters/leaves
        // fullscreen. (Named ZaynAppNative — the page already defines its
        // own window.ZaynApp object for back navigation.)
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun setFullscreen(on: Boolean) {
                runOnUiThread { setImmersiveMode(on) }
            }
        }, "ZaynAppNative")

        // The native splash already showed (?bootsplash=0 skips the
        // in-WebView splash so the user is not shown it twice).
        webView.loadUrl("file:///android_asset/zaynplay-app.html?bootsplash=0&form=$form")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Let the UI handle Back first (close a sheet, or go Home);
                // only leave the app when the UI is already on Home.
                webView.evaluateJavascript("window.ZaynApp ? ZaynApp.goBack() : false") { result ->
                    if (result != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Night mode changed (system theme switch): repaint the WebView
        // background; the page itself re-themes live via matchMedia.
        webView.setBackgroundColor(currentBackgroundColor())
    }

    /**
     * True immersive fullscreen for the player: hide the status +
     * navigation bars and go edge-to-edge so the video covers the whole
     * screen. Restored when leaving the player; the normal UI (status bar,
     * gesture bar, dock) is untouched outside fullscreen.
     */
    private fun setImmersiveMode(on: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !on)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun currentBackgroundColor(): Int {
        val night = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.parseColor("#0A0A0F") else Color.parseColor("#F2F3F7")
    }

    /**
     * Form factor for orientation + the page layout: "tv" for Android TV
     * (UiModeManager type television), "tablet" for smallest width >= 600dp,
     * "phone" otherwise.
     */
    private fun formFactor(): String {
        val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return "tv"
        return if (resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
    }
}
