package app.netlify.zaynplay

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
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
import app.netlify.zaynplay.engine.EngineLogger
import app.netlify.zaynplay.engine.EngineStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONObject

/**
 * Zayn Play — UI-only build (v0.2.0-phaseA).
 *
 * The whole app UI is the approved preview (assets/zaynplay-app.html),
 * shown in a single WebView. Phase A of the engine phase adds only the
 * bridge skeleton: a second JavascriptInterface ("ZaynEngineNative") whose
 * dispatcher answers engineStatus / settingsGet / settingsSet and replies
 * engine_not_ready for everything else. No providers, no real player, no
 * downloads yet — the demo video and demo repo state are gone, and the UI
 * fails visibly (toast) whenever it calls an unimplemented engine method.
 * The engine port itself comes in a later phase.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Engine bridge (phase A): the store behind settingsGet/settingsSet,
    // a small IO pool for bridge work, and the in-flight calls by id.
    // No new Gradle dependency is used: plain threads, not coroutines.
    private lateinit var engineStore: EngineStore
    private val engineExecutor = Executors.newCachedThreadPool()
    private val engineJobs = ConcurrentHashMap<String, Future<*>>()

    // True while the player is fullscreen: the immersive hide must be
    // re-applied after every rotation / focus change / resume, because the
    // window re-layout on configuration change drops the hidden bars.
    private var isPlayerFullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // FIX 5: follow the STORED app theme choice (System/Dark/Light);
        // FOLLOW_SYSTEM only when nothing is stored yet. The WebView's
        // prefers-color-scheme then matches the app theme, and the in-app
        // theme setting switches the UI live.
        ThemePrefs.applyStoredNightMode(this)
        super.onCreate(savedInstanceState)
        // Edge-to-edge for the whole app (v0.1.5): the page lays out behind
        // the status bar on every screen — content starts at the very top
        // edge and the status icons float over it. The theme already makes
        // the status bar transparent and the nav bar app_bg; this only stops
        // the system from pushing the WebView below the status bar. (The
        // player still hides the bars entirely via setImmersiveMode.)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        // Fullscreen video must reach every edge, including the punch-hole
        // camera cutout (v0.1.6): Android's default cutout mode insets the
        // window on the cutout edge in landscape — a black strip on the
        // left where the camera sits — even though the page itself lays out
        // full-bleed (the WebView is match_parent and the player stage was
        // measured at 0,0,2400,1080 in headless Chrome). SHORT_EDGES lets
        // the window draw into the cutout on the short edges, so the video
        // covers the whole screen; the camera dot itself simply covers a
        // few pixels of video, as in every fullscreen video app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        applyStatusBarIconAppearance()
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

        // True fullscreen for the player: the page rotates the activity to
        // real landscape via setOrientationLandscape and hides the Android
        // status/nav bars through setFullscreen here, so the video covers
        // the whole screen. (Named ZaynAppNative — the page already defines
        // its own window.ZaynApp object for back navigation.)
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun setFullscreen(on: Boolean) {
                runOnUiThread {
                    isPlayerFullscreen = on
                    setImmersiveMode(on)
                }
            }

            @JavascriptInterface
            fun setOrientationLandscape(on: Boolean) {
                runOnUiThread {
                    // Exiting fullscreen restores the form-appropriate
                    // orientation (v0.1.5): portrait on a phone, landscape
                    // on a tablet / TV — the old unconditional PORTRAIT
                    // forced the whole app portrait on tablet/TV. Uses
                    // the same formFactor() detection as onCreate, so
                    // phone behaviour is unchanged.
                    requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else if (formFactor() == "phone") ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }

            @JavascriptInterface
            fun setThemePref(pref: String) {
                // FIX 5: the WebView pushes every theme change here
                // (System/Dark/Light) so the next cold start's splash
                // follows the app's theme, not just the system setting.
                // Stored for SplashActivity/MainActivity to read; applied
                // live too so the running activity's native chrome
                // (nav bar, WebView background) follows at once — no
                // recreation: MainActivity handles uiMode config changes.
                if (pref == "system" || pref == "dark" || pref == "light") {
                    ThemePrefs.store(this@MainActivity, pref)
                    runOnUiThread {
                        AppCompatDelegate.setDefaultNightMode(ThemePrefs.modeFor(pref))
                    }
                }
            }

            @JavascriptInterface
            fun setStatusBarDarkIcons(dark: Boolean) {
                runOnUiThread {
                    WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = dark
                }
            }
        }, "ZaynAppNative")

        // Engine bridge skeleton (phase A, engine-design.md §4.2): a second
        // interface, "ZaynEngineNative", with one async entry point. call()
        // arrives on a WebView thread and returns at once — the work runs on
        // the IO pool and the reply is posted to the main thread, which is
        // the only thread allowed to call evaluateJavascript. The existing
        // "ZaynAppNative" 4 methods above are untouched. Until the engine
        // port lands, only engineStatus / settingsGet / settingsSet are
        // implemented; every other method name gets engine_not_ready.
        engineStore = EngineStore(this)
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun call(id: String, method: String, json: String) {
                val task = Runnable {
                    val params = try {
                        JSONObject(json)
                    } catch (e: Exception) {
                        engineReply(id, engineError("invalid_input", "Bad request"))
                        return@Runnable
                    }
                    EngineLogger.log("bridge", "$method id=$id")
                    val response = try {
                        dispatchEngineCall(method, params)
                    } catch (e: Exception) {
                        EngineLogger.log("bridge", "$method id=$id threw ${e.message}")
                        engineError("unknown", "Engine error")
                    }
                    engineJobs.remove(id)
                    engineReply(id, response)
                }
                engineJobs[id] = engineExecutor.submit(task)
            }

            @JavascriptInterface
            fun cancel(id: String) {
                engineJobs.remove(id)?.cancel(true)
            }
        }, "ZaynEngineNative")

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
        // The rotation re-lays the window and drops the immersive hide:
        // re-hide the bars while the player is fullscreen (posted so it
        // runs after the new layout is applied).
        if (isPlayerFullscreen) {
            setImmersiveMode(true)
            window.decorView.post { if (isPlayerFullscreen) setImmersiveMode(true) }
        }
    }

    override fun onPause() {
        super.onPause()
        // FIX 6: pause the WebView with the activity — its JS timers and
        // rendering stop while the user is in another app, so nothing
        // keeps running (or playing) in the background.
        webView.onPause()
        webView.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        // FIX 6: resume the WebView's rendering as soon as the activity
        // returns, so the last screen repaints at once instead of leaving
        // a black frame (window/WebView background) while it catches up.
        // The activity was never pausing the WebView before, so a warm
        // return from Recents showed black until the WebView redrew.
        webView.onResume()
        webView.resumeTimers()
        // Returning to the activity (e.g. after the transient bars were
        // swiped in, or after another app) must not leave the status bar
        // visible over fullscreen video.
        if (isPlayerFullscreen) setImmersiveMode(true)
    }

    override fun onDestroy() {
        // Engine bridge: drop in-flight calls and stop the IO pool.
        engineJobs.values.forEach { it.cancel(true) }
        engineJobs.clear()
        engineExecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Engine bridge dispatcher (phase A). Only three methods are real;
     * everything else answers engine_not_ready until the engine port lands.
     * Runs on the IO pool — never on the WebView thread, never on main.
     */
    private fun dispatchEngineCall(method: String, params: JSONObject): JSONObject {
        return when (method) {
            "engineStatus" -> engineOk(
                JSONObject()
                    .put("version", "0.2.0")
                    .put("engineReady", false)
                    // Phase A: no plugin system yet, so all counts are 0.
                    .put("pluginsLoaded", 0)
                    .put("reposInstalled", 0)
                    .put("providersOn", 0)
            )
            "settingsGet" -> engineOk(
                JSONObject().put("settings", engineStore.settingsSnapshot())
            )
            "settingsSet" -> {
                val key = params.optString("key", "")
                if (!params.has("value")) {
                    return engineError("invalid_input", "Missing value")
                }
                val value = params.opt("value")
                when (val err = engineStore.setSetting(key, value)) {
                    null -> engineOk(JSONObject().put("key", key).put("value", value))
                    "unknown_setting" -> engineError("unknown_setting", "Unknown setting: $key")
                    "invalid_value" -> engineError("invalid_value", "Invalid value for $key")
                    else -> engineError("unknown", "Could not save $key")
                }
            }
            else -> {
                EngineLogger.log("bridge", "$method -> engine_not_ready")
                engineError(
                    "engine_not_ready",
                    "Engine not ready yet — this feature comes in the next update"
                )
            }
        }
    }

    private fun engineOk(data: Any?): JSONObject =
        JSONObject().put("ok", true).put("data", data ?: JSONObject.NULL)

    private fun engineError(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("code", code).put("message", message)

    /** Post a bridge reply to the main thread, then into the page. */
    private fun engineReply(id: String, response: JSONObject) {
        val idJson = JSONObject.quote(id)
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.__zaynReply($idJson, $response)", null)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Focus changes (notification shade, dialog, transient bars) clear
        // the hide; re-apply it while the player is fullscreen.
        if (hasFocus && isPlayerFullscreen) setImmersiveMode(true)
    }

    /**
     * True immersive fullscreen for the player: hide the status +
     * navigation bars and go edge-to-edge so the video covers the whole
     * screen. Restored when leaving the player; the normal UI (status bar,
     * gesture bar, dock) is untouched outside fullscreen.
     */
    /**
     * Status-bar icon colour for normal (non-player) screens: dark icons
     * when the system is in light mode, white icons when dark. The page
     * overrides this live through setStatusBarDarkIcons() whenever its own
     * theme (System / Dark / Light) resolves differently from the system.
     */
    private fun applyStatusBarIconAppearance() {
        val night = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !night
    }

    private fun setImmersiveMode(on: Boolean) {
        // Edge-to-edge is permanent now (set once in onCreate): the page
        // always lays out behind the status bar, so this only hides /
        // shows the bars — it must NOT re-enable fitsSystemWindows here.
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
