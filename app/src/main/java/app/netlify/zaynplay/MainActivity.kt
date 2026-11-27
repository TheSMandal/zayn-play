@file:OptIn(InternalAPI::class)
package app.netlify.zaynplay
import com.lagradost.cloudstream3.InternalAPI

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
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.netlify.zaynplay.engine.EngineApp
import app.netlify.zaynplay.engine.EngineLogger
import app.netlify.zaynplay.engine.EngineStore
import app.netlify.zaynplay.engine.ZaynDownloads
import app.netlify.zaynplay.engine.ZaynEngine
import app.netlify.zaynplay.engine.ZaynPlayer
import app.netlify.zaynplay.engine.ZaynMpvPlayer
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.plugins.PluginManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Zayn Play (v1.0.3).
 *
 * The app shows the approved preview (assets/zaynplay-app.html),
 * shown in a single WebView. Phase B of the engine phase ports the
 * CloudStream engine core (vendored, decoupled — see
 * app/netlify/zaynplay/engine/ZaynEngine.kt): the "ZaynEngineNative"
 * bridge now implements E1–E11 + E26–E28 (repos, providers, home feed,
 * search, details, settings, update check). Player / downloads /
 * accounts / updater are still engine_not_ready (Phase C). No
 * CloudStream UI anywhere — the page is the approved preview only.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Engine bridge (phase B): the store behind settingsGet/settingsSet,
    // the real dispatcher (ZaynEngine), an IO coroutine scope for bridge
    // work, and the in-flight calls by id.
    private lateinit var engineStore: EngineStore
    private lateinit var zaynEngine: ZaynEngine
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineJobs = ConcurrentHashMap<String, Job>()

    // Phase C player (P0 overlay, engine-design.md §5.5/§7.2): a
    // chrome-less TextureView ExoPlayer renders into, positioned over the
    // WebView's player stage from the rect the page reports. No native
    // controls — every control stays HTML.
    //
    // TextureView (not SurfaceView) is deliberate: a SurfaceView lives on
    // its own layer — behind the window it would be hidden by the
    // WebView, above it (ZOrderOnTop/MediaOverlay) it would cover the
    // HTML controls inside the stage. A TextureView composites as a
    // normal View BELOW the WebView, so the HTML controls draw above the
    // video and stay tappable; the page punches a transparent hole in
    // the stage (body / #scr-player / .stage-video backgrounds off,
    // poster hidden) while the surface is visible, and the WebView
    // background is set transparent for the same window.
    private lateinit var playerSurface: TextureView
    private lateinit var zaynPlayer: ZaynPlayer
    // (v1.0.10) The libmpv player: same TextureView, same event callback,
    // same bridge. Only the engine the user picked ever plays.
    private lateinit var zaynMpvPlayer: ZaynMpvPlayer
    // (v1.0.10 L1) Frame-arrival probe state: see startFrameProbe().
    private var frameProbeRunning = false
    private var lastFrameTs = 0L
    private var frameCount = 0
    private var probedTexId = 0
    // (v1.0.12 §9) Consecutive playing polls with zero frames (250 ms
    // each; 20 = 5 s) + whether the UNHEALTHY line already fired.
    private var probePolls = 0
    private var probeHealthLogged = false
    // (v1.0.14 G4) Stall detector: lastFrameTs baseline + consecutive
    // playing polls with NO new frame. frameCount is cumulative, so it
    // can never catch a mid-play freeze — this can.
    private var lastHealthFrameTs = 0L
    private var healthStallPolls = 0
    // (v1.0.14 G2) Whether playback was playing when the activity paused,
    // so onResume can surface the paused state (tap-to-resume; never
    // auto-resume — product decision pending).
    private var wasPlayingAtPause = false
    private lateinit var zaynDownloads: ZaynDownloads
    private var lastStageRect: JSONObject? = null
    // (v1.0.5 A1/A4) Last KNOWN-GOOD stage rect (visible=true, non-degenerate):
    // the native side re-applies this on config change / playerState instead
    // of trusting one possibly-false page report for the rest of the session.
    private var lastGoodStageRect: JSONObject? = null
    // (v1.0.5 A1) The WebView background colour actually in effect (there is
    // no getter) — logged with every playerStage report.
    private var lastWebViewBgColor: Int = Color.TRANSPARENT

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
        setWebViewBackground(currentBackgroundColor())
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
            // The zaynplay://auth redirect is the OAuth capture path
            // (design §5.8 Accounts): it stays in-app and is handed to
            // the bridge as an authRedirect event.
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url ?: return false
                if (url.scheme == "zaynplay" && url.host == "auth") {
                    val provider = url.getQueryParameter("provider") ?: ""
                    val code = url.getQueryParameter("code") ?: ""
                    pushEngineEvent(
                        "authRedirect",
                        JSONObject()
                            .put("provider", provider)
                            .put("redirectUrl", url.toString())
                            .put("code", code),
                    )
                    EngineLogger.log("accounts", "auth redirect captured for $provider")
                    return true
                }
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
                    // (v1.0.5 A1/A5) Log what was requested AND what the
                    // configuration actually is — V1.0.4 proved the
                    // landscape request did not take effect on Samim's
                    // vivo (portrait screenshot with .fs applied) and the
                    // log could not say why. If the ROM ignores the
                    // request, fall back to sensor landscape after 600 ms
                    // (the sensor variant follows the device's natural
                    // rotation) and log the outcome either way.
                    EngineLogger.log(
                        "player",
                        "orientation landscape=$on requestedOrientation=$requestedOrientation " +
                            "configOrientation=${resources.configuration.orientation}",
                    )
                    if (on) {
                        window.decorView.postDelayed({
                            if (isPlayerFullscreen &&
                                resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
                            ) {
                                EngineLogger.log(
                                    "player",
                                    "orientation fallback: still portrait 600ms after LANDSCAPE request, " +
                                        "trying SENSOR_LANDSCAPE",
                                )
                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                EngineLogger.log(
                                    "player",
                                    "orientation check: configOrientation=${resources.configuration.orientation} " +
                                        "after LANDSCAPE request",
                                )
                            }
                        }, 600)
                    }
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

            @JavascriptInterface
            fun setWindowBrightness(value: Float) {
                // Phase C: the brightness gesture drives the activity
                // window brightness while the player is open (a native
                // video surface has no per-view filter). -1 restores the
                // system brightness. The page calls this only while the
                // player screen is open; exitPlayerCleanup restores -1.
                runOnUiThread {
                    val attrs = window.attributes
                    attrs.screenBrightness = if (value < 0) {
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    } else {
                        value.coerceIn(0.01f, 1.0f)
                    }
                    window.attributes = attrs
                }
            }
        }, "ZaynAppNative")

        // Engine bridge (phase B, engine-design.md §4.2): a second
        // interface, "ZaynEngineNative", with one async entry point. call()
        // arrives on a WebView thread and returns at once — the work runs on
        // the IO coroutine scope and the reply is posted to the main thread,
        // which is the only thread allowed to call evaluateJavascript. The
        // existing "ZaynAppNative" 4 methods above are untouched.
        // Engine init: the DataStore-backed EngineApp shim, the NiceHttp
        // client (Conscrypt + 50 MB cache + DNS from settings), the
        // api-level context for WebViewResolver, then the initial plugin
        // load (online + local .cs3) — engineReady means that load was
        // attempted, even if it failed.
        engineStore = EngineStore(this)
        EngineApp.init(this, engineStore)
        // (v0.2.6) Host-compat: plugins read the live activity via
        // CommonActivity.INSTANCE.getActivity() (e.g. Cloudflare
        // dialogs, donation prompts). Point it at this activity.
        CommonActivity.setActivityInstance(this)
        app.initClient(this)
        setContext(applicationContext)
        // Phase C: the headless player + downloads, owned here. Their
        // events flow back into the page via pushEngineEvent.
        playerSurface = findViewById(R.id.player_surface)
        // (v1.0.9) NO app-owned SurfaceTextureListener: Media3's
        // componentListener (registered via setVideoTextureView in
        // ZaynPlayer.ensurePlayer) is the observer now. The V1.0.8
        // listener is deleted because setVideoTextureView replaces
        // whatever listener is set, and the raw-Surface attach it drove
        // could only ever report size (-1,-1) to the sink.
        // (v1.0.5 A3) Re-assert the video hole on every playerState event
        // while the player is open, so one visible=false report — or a
        // missed report — cannot leave the surface GONE for the session.
        // (v1.0.10 L3) playerFirstFrame is NOT a page event: it tells the
        // activity to re-report the stage rect NOW, so the page-hole state
        // is captured AT the first frame. It is intercepted here and never
        // pushed into the page's __zaynEvent handler.
        val playerEventCallback: (String, JSONObject) -> Unit = { type, data ->
            if (type == "playerFirstFrame") {
                runOnUiThread {
                    if (::webView.isInitialized) {
                        webView.evaluateJavascript(
                            "window.__zaynReportStage && window.__zaynReportStage()",
                            null,
                        )
                    }
                }
            } else {
                if (type == "playerState") reassertVideoHole()
                pushEngineEvent(type, data)
            }
        }
        zaynPlayer = ZaynPlayer(this, playerEventCallback)
        zaynPlayer.setTextureView(playerSurface)
        zaynMpvPlayer = ZaynMpvPlayer(this, playerEventCallback)
        zaynMpvPlayer.setTextureView(playerSurface)
        zaynDownloads = ZaynDownloads(this, engineStore) { type, data -> pushEngineEvent(type, data) }
        zaynEngine = ZaynEngine(this, engineStore, zaynPlayer, zaynMpvPlayer, zaynDownloads, engineScope, { type, data -> pushEngineEvent(type, data) }) { rect ->
            onPlayerStageRect(rect)
        }
        engineScope.launch {
            // (v1.0.14 P5-9) Track whether the initial plugin load threw:
            // before this, markReady() + pluginsChanged{ready:true} fired
            // unconditionally after swallowed exceptions, so the page
            // believed plugins were ready when they were not.
            var pluginLoadError: String? = null
            try {
                PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(this@MainActivity)
                PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(this@MainActivity, false)
            } catch (e: Exception) {
                pluginLoadError = "${e.javaClass.simpleName}: ${e.message}"
                EngineLogger.log("engine", "initial plugin load threw ${e.message}")
            }
            // (v0.2.1-g) APIHolder.initAll() was never called: apply
            // MainAPI.init() / clone-site overrides to every loaded API.
            // (loadPlugin also calls it per plugin; this covers the bulk
            // load path end-to-end.)
            try {
                com.lagradost.cloudstream3.APIHolder.initAll()
            } catch (e: Exception) {
                if (pluginLoadError == null) pluginLoadError = "${e.javaClass.simpleName}: ${e.message}"
                EngineLogger.log("engine", "APIHolder.initAll threw ${e.message}")
            }
            if (pluginLoadError != null) {
                pushEngineEvent("pluginsChanged", JSONObject().put("ready", false).put("error", pluginLoadError))
            } else {
                zaynEngine.markReady()
                pushEngineEvent("pluginsChanged", JSONObject().put("ready", true))
            }
            // (v0.2.11) C-11: NO boot auto-update check any more — it
            // fetched every repo manifest and downloaded updates over the
            // same slow link while the first home feed was loading, and
            // could race the user's first toggle/test. Updates now run
            // only when the user opens Settings -> Extensions (which
            // already triggers a check) or taps update manually.
        }
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun call(id: String, method: String, json: String) {
                val job = engineScope.launch {
                    val params = try {
                        JSONObject(json)
                    } catch (e: Exception) {
                        engineReply(id, engineError("invalid_input", "Bad request"))
                        return@launch
                    }
                    // (v1.0.14 P4/F4) Feed-lifecycle logging: name the
                    // provider for homeFeed so the log shows WHICH feed is
                    // loading. Only the providerName field is read — never
                    // the full params JSON (it can carry tokens/URLs).
                    val _prov = if (method == "homeFeed") " provider=${params.optString("providerName", "?")}" else ""
                    EngineLogger.log("bridge", "$method id=$id$_prov")
                    // (v1.0.2) The native side must ALWAYS reply. A native
                    // timeout used to cancel this coroutine (homeFeed
                    // rethrows CancellationException) and skip engineReply,
                    // so JS heard nothing until its own 150 s timer — the
                    // Home skeleton spun with no error for up to 150 s.
                    // A cancelled call now replies "timeout"; a JS side
                    // that already timed out ignores the late reply
                    // (__zaynReply is a no-op once pending is deleted).
                    val response = try {
                        // (v1.0.12 §7) Thread the bridge id through so
                        // play() can log it and dedup a repeated id.
                        zaynEngine.call(method, params, id)
                    } catch (e: CancellationException) {
                        engineError("timeout", "Engine timed out — try again")
                    }
                    engineJobs.remove(id)
                    engineReply(id, response)
                }
                engineJobs[id] = job
            }

            @JavascriptInterface
            fun cancel(id: String) {
                engineJobs.remove(id)?.cancel()
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
        // (v1.0.12 §4) Config-change detection, logged: the rotation churn
        // (1082x2396 → 2398x5308 → 2392x1080) is expected layout churn, and
        // the surface state machine ignores the transient sizes.
        EngineLogger.log(
            "player",
            "config changed orientation=${newConfig.orientation} fullscreen=$isPlayerFullscreen",
        )
        // Night mode changed (system theme switch): repaint the WebView
        // background; the page itself re-themes live via matchMedia.
        // (v1.0.5 A2) NEVER repaint opaque while the player is fullscreen:
        // the old unconditional reset hid the video on every rotation /
        // theme change and recovery depended entirely on a page re-report.
        // While fullscreen, re-apply the hole from the last known-good
        // rect at once instead of waiting for the page.
        if (isPlayerFullscreen) {
            reassertVideoHole()
        } else {
            setWebViewBackground(currentBackgroundColor())
        }
        // The rotation re-lays the window and drops the immersive hide:
        // re-hide the bars while the player is fullscreen (posted so it
        // runs after the new layout is applied).
        if (isPlayerFullscreen) {
            setImmersiveMode(true)
            window.decorView.post { if (isPlayerFullscreen) setImmersiveMode(true) }
            // The stage moved: ask the page to re-report its rect so the
            // native video surface follows the new layout.
            webView.evaluateJavascript("window.__zaynReportStage && window.__zaynReportStage()", null)
            // (v1.0.14 G3) Rotation can lose the SurfaceTexture while the
            // hole looks fine — verify it 200 ms after layout and recover
            // if it is gone (postSurfaceCheck does both, either engine).
            postSurfaceCheck()
        }
    }

    override fun onPause() {
        super.onPause()
        // FIX 6: pause the WebView with the activity — its JS timers and
        // rendering stop while the user is in another app, so nothing
        // keeps running (or playing) in the background.
        webView.onPause()
        webView.pauseTimers()
        // (v1.0.14 G2) Remember whether playback was playing BEFORE
        // pausing, so onResume can surface the paused state.
        wasPlayingAtPause = (::zaynPlayer.isInitialized && zaynPlayer.isPlaying()) ||
            (::zaynMpvPlayer.isInitialized && zaynMpvPlayer.isPlaying())
        // Phase C: pause playback too (audio must not keep playing when
        // the user leaves the app).
        if (::zaynPlayer.isInitialized) zaynPlayer.pauseForBackground()
        // (v1.0.10) Pause the mpv engine too when it is the active one;
        // its pauseForBackground is a no-op before its instance exists.
        if (::zaynMpvPlayer.isInitialized) zaynMpvPlayer.pauseForBackground()
        // (v1.0.12 §8) Activity state for the diagnostics lines.
        if (::zaynPlayer.isInitialized) zaynPlayer.activityState = "paused"
        if (::zaynMpvPlayer.isInitialized) zaynMpvPlayer.setActivityState("paused")
    }

    override fun onResume() {
        super.onResume()
        // (v0.2.6) Re-point CommonActivity at this activity on every
        // resume, so plugins always see the live one.
        CommonActivity.setActivityInstance(this)
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
        // (v1.0.12 §8) Activity state for the diagnostics lines.
        if (::zaynPlayer.isInitialized) zaynPlayer.activityState = "resumed"
        if (::zaynMpvPlayer.isInitialized) zaynMpvPlayer.setActivityState("resumed")
        // (v1.0.12 §9) Activity-resume recovery: returning from another app
        // must not leave the hole closed — re-assert it and re-report the
        // stage rect while fullscreen.
        if (isPlayerFullscreen) {
            reassertVideoHole()
            webView.evaluateJavascript("window.__zaynReportStage && window.__zaynReportStage()", null)
            // (v1.0.14 G2) Verify the surface itself, not just the hole: a
            // texture lost while backgrounded is never detected otherwise.
            // (v1.0.14 G3 shape, reused here.)
            postSurfaceCheck()
            if (wasPlayingAtPause) {
                // Tap-to-resume (never auto-resume): push the paused state
                // so the player UI shows the paused toggle instead of a
                // silently frozen frame. The user taps play to resume.
                val snapshot = if (::zaynEngine.isInitialized && zaynEngine.isMpvActive() && ::zaynMpvPlayer.isInitialized) {
                    zaynMpvPlayer.stateSnapshot()
                } else if (::zaynPlayer.isInitialized) {
                    zaynPlayer.stateSnapshot()
                } else null
                if (snapshot != null) {
                    EngineLogger.log("player", "activity resume — was playing at pause, surfacing paused state (tap to resume)")
                    pushEngineEvent("playerState", snapshot)
                }
            }
        }
        wasPlayingAtPause = false
    }

    override fun onDestroy() {
        // Engine bridge: drop in-flight calls and stop the IO scope.
        engineJobs.values.forEach { it.cancel() }
        engineJobs.clear()
        engineScope.cancel()
        // Phase C: release the player + stop downloads.
        if (::zaynPlayer.isInitialized) zaynPlayer.release()
        // (v1.0.10) Release the mpv instance too (destroy + surface detach).
        if (::zaynMpvPlayer.isInitialized) zaynMpvPlayer.release()
        if (::zaynDownloads.isInitialized) zaynDownloads.shutdown()
        super.onDestroy()
    }

    /**
     * Engine bridge dispatcher (phase B): all methods live in ZaynEngine
     * (E1–E11 + E26–E28 real; the rest engine_not_ready until Phase C).
     * Runs on the IO coroutine scope — never on the WebView thread, never
     * on main.
     */
    private fun engineError(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("code", code).put("message", message)

    /** Push a one-way engine event into the page (window.__zaynEvent). */
    private fun pushEngineEvent(name: String, data: JSONObject) {
        // (v1.0.14 P4 rank 5) Stamp the bridge version on every event so
        // the page can ignore events from a mismatched bridge.
        if (!data.has("v")) data.put("v", 1)
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "window.__zaynEvent && window.__zaynEvent(${JSONObject.quote(name)}, $data)",
                    null
                )
            }
        }
    }

    /** Post a bridge reply to the main thread, then into the page. */
    private fun engineReply(id: String, response: JSONObject) {
        // (v1.0.14 P4 rank 9) A reply into a finishing/destroyed activity
        // is a WebView call into a dead page — skip it (the JS side has
        // already timed out or gone away with the activity).
        if (isFinishing || isDestroyed) return
        val idJson = JSONObject.quote(id)
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.__zaynReply($idJson, $response)", null)
            }
        }
    }

    /**
     * P0 overlay positioning (engine-design.md §5.5/§7.2).
     *
     * The page reports the player stage rect in CSS px plus the viewport
     * size (window.innerWidth/innerHeight). The scale from CSS px to
     * physical px is webView.width / viewportWidth — exact for any
     * viewport setup, no density guess. The surface is visible ONLY
     * while the page says the player is open and fullscreen; otherwise
     * it is GONE (never a black rectangle over the UI). The last rect is
     * kept so a rotation can re-apply it once the page re-reports.
     */
    private fun onPlayerStageRect(rect: JSONObject) {
        lastStageRect = rect
        runOnUiThread {
            if (!::playerSurface.isInitialized || !::webView.isInitialized) return@runOnUiThread
            val visible = rect.optBoolean("visible", false)
            val x = rect.optDouble("x", 0.0)
            val y = rect.optDouble("y", 0.0)
            val w = rect.optDouble("w", 0.0)
            val h = rect.optDouble("h", 0.0)
            val vw = rect.optDouble("vw", 0.0)
            val vh = rect.optDouble("vh", 0.0)
            if (!visible) {
                playerSurface.visibility = View.GONE
                // (v1.0.12 §6 step 2) Stage-close detach: if mpv was the
                // active engine, release its surface hold now — otherwise
                // it keeps the BufferQueue connected while the stage is
                // closed. ExoPlayer's hold is released by its own
                // switch-time detach in playActive; nothing to do here.
                if (::zaynMpvPlayer.isInitialized && ::zaynEngine.isInitialized && zaynEngine.isMpvActive()) {
                    zaynMpvPlayer.detachSurfaceForSwitch()
                }
                // The page repaints its own backgrounds; restore the
                // WebView background so a transparent hole never shows the
                // window behind the UI.
                if (::webView.isInitialized) setWebViewBackground(currentBackgroundColor())
                // (v1.0.5 A1) Log the FULL payload on every call — V1.0.4
                // logged method + id only, so whether the last on-phone
                // report was visible=true or false was unknowable.
                EngineLogger.log(
                    "playerStage",
                    "visible=false x=$x y=$y w=$w h=$h vw=$vw vh=$vh " +
                        "fsOn=${rect.optBoolean("fsOn", false)} stg=${rect.optBoolean("stg", false)} " +
                        "scrId=${rect.optString("scrId", "")} nativeVideo=${rect.optBoolean("nativeVideo", false)} " +
                        "surface=GONE webViewBg=#%08X".format(lastWebViewBgColor),
                )
                return@runOnUiThread
            }
            // (v1.0.12 §10) Skip-if-unchanged: an identical rect with the
            // visibility already correct and the probe already running is a
            // no-op — return after logging nothing. (The D4 probe-start log
            // below fires only when the probe actually restarts, so it is
            // not lost: a reopened stage has the surface GONE, which fails
            // the visibility condition and never skips.)
            val lastGood = lastGoodStageRect
            if (lastGood != null && frameProbeRunning &&
                Math.abs(x - lastGood.optDouble("x", 0.0)) <= 1 &&
                Math.abs(y - lastGood.optDouble("y", 0.0)) <= 1 &&
                Math.abs(w - lastGood.optDouble("w", 0.0)) <= 1 &&
                Math.abs(h - lastGood.optDouble("h", 0.0)) <= 1 &&
                Math.abs(vw - lastGood.optDouble("vw", 0.0)) <= 1 &&
                Math.abs(vh - lastGood.optDouble("vh", 0.0)) <= 1 &&
                playerSurface.visibility == View.VISIBLE &&
                lastWebViewBgColor == Color.TRANSPARENT
            ) {
                return@runOnUiThread
            }
            // Transparent WebView background while the surface shows, so
            // the page's transparent stage hole reveals the video below.
            if (::webView.isInitialized) setWebViewBackground(Color.TRANSPARENT)
            // (v1.0.9) No raw-Surface attach here: the view is attached
            // via setVideoTextureView in ZaynPlayer.ensurePlayer; a
            // setVideoSurface call would remove Media3's listener.
            // (v1.0.4) The surface must be VISIBLE whenever the page says
            // the player is open, even if this report's rect is degenerate
            // (a 0-size rect used to leave it GONE while the page showed
            // the player as open). Positioning follows on the next report.
            playerSurface.visibility = View.VISIBLE
            // (v1.0.11 D4) Log probe start on EVERY stage-open, even when
            // the probe is already running from an earlier play: the old
            // code logged only on a texId change, so a running probe left
            // no line in the log and the L1 negative rested on inference.
            EngineLogger.log(
                "player",
                "player texture frame probe started alreadyRunning=$frameProbeRunning",
            )
            // (v1.0.10 L1) Start the frame-arrival probe now that the stage
            // is open (it stops itself when the surface goes GONE).
            startFrameProbe()
            // (v1.0.11 F2, gated v1.0.12 §6 step 2) Re-check the mpv surface
            // attach on stage-open ONLY when mpv is the active engine:
            // attaching mpv to a surface ExoPlayer holds would re-create
            // the producer conflict. Silent no-op when already attached.
            if (::zaynMpvPlayer.isInitialized && ::zaynEngine.isInitialized && zaynEngine.isMpvActive()) zaynMpvPlayer.attachSurfaceIfReady()
            // (v1.0.8) Post-stage-open surface-state check (see postSurfaceCheck).
            postSurfaceCheck()
            // (v1.0.5 A4) A degenerate rect must NOT leave the surface at
            // its 1dp×1dp XML size: apply the last known-good rect, or the
            // full WebView size when no good rect exists yet.
            val degenerate = vw <= 0 || vh <= 0 || webView.width <= 0 || w <= 0 || h <= 0
            if (degenerate) {
                val good = lastGoodStageRect
                if (good != null && webView.width > 0) {
                    applyStageRect(good)
                } else if (webView.width > 0 && webView.height > 0) {
                    applyStageRectPx(0, 0, webView.width, webView.height)
                }
                EngineLogger.log(
                    "playerStage",
                    "visible=true DEGENERATE x=$x y=$y w=$w h=$h vw=$vw vh=$vh " +
                        "fsOn=${rect.optBoolean("fsOn", false)} stg=${rect.optBoolean("stg", false)} " +
                        "scrId=${rect.optString("scrId", "")} nativeVideo=${rect.optBoolean("nativeVideo", false)} " +
                        "fell back to lastGoodRect=${good != null} " +
                        // (v1.0.11 D4) Hardware acceleration at stage-open:
                        // a TextureView draws nothing in a software window.
                        "hwAccel window=${window.decorView.isHardwareAccelerated} webView=${webView.isHardwareAccelerated} " +
                        "surface=${surfaceState()} webViewBg=#%08X".format(lastWebViewBgColor),
                )
                return@runOnUiThread
            }
            lastGoodStageRect = rect
            applyStageRect(rect)
            EngineLogger.log(
                "playerStage",
                "visible=true x=$x y=$y w=$w h=$h vw=$vw vh=$vh " +
                    "fsOn=${rect.optBoolean("fsOn", false)} stg=${rect.optBoolean("stg", false)} " +
                    "scrId=${rect.optString("scrId", "")} nativeVideo=${rect.optBoolean("nativeVideo", false)} " +
                    // (v1.0.11 D4) Hardware acceleration at stage-open:
                    // a TextureView draws nothing in a software window.
                    "hwAccel window=${window.decorView.isHardwareAccelerated} webView=${webView.isHardwareAccelerated} " +
                    "surface=${surfaceState()} webViewBg=#%08X".format(lastWebViewBgColor),
            )
        }
    }

    /** Position/size the surface from a stage rect (CSS px -> physical px). */
    private fun applyStageRect(rect: JSONObject) {
        val vw = rect.optDouble("vw", 0.0)
        if (vw <= 0 || webView.width <= 0) return
        val scale = webView.width / vw
        applyStageRectPx(
            (rect.optDouble("x", 0.0) * scale).toInt(),
            (rect.optDouble("y", 0.0) * scale).toInt(),
            (rect.optDouble("w", 0.0) * scale).toInt().coerceAtLeast(1),
            (rect.optDouble("h", 0.0) * scale).toInt().coerceAtLeast(1),
        )
    }

    private fun applyStageRectPx(x: Int, y: Int, w: Int, h: Int) {
        val params = playerSurface.layoutParams as FrameLayout.LayoutParams
        params.width = w
        params.height = h
        params.leftMargin = x
        params.topMargin = y
        playerSurface.layoutParams = params
    }

    /** (v1.0.5 A1) One-line TextureView state for the stage log. */
    private fun surfaceState(): String {
        val vis = when (playerSurface.visibility) {
            View.VISIBLE -> "VISIBLE"
            View.GONE -> "GONE"
            else -> "INVISIBLE"
        }
        val lp = playerSurface.layoutParams
        return "$vis ${playerSurface.width}x${playerSurface.height} layout=${lp?.width}x${lp?.height}"
    }

    /** (v1.0.8) Delayed surface-state check after the stage opens (layout
     *  + first draw, ~200 ms): logs whether the framework created a
     *  SurfaceTexture. This is the line that decides the next step if the
     *  screen is still black — surfaceTexture=false means the framework
     *  never created one; true + still black means renderer/content. */
    private fun postSurfaceCheck() {
        playerSurface.postDelayed({
            val vis = when (playerSurface.visibility) {
                View.VISIBLE -> "VISIBLE"
                View.GONE -> "GONE"
                else -> "INVISIBLE"
            }
            EngineLogger.log(
                "playerStage",
                "surface check surfaceTexture=${playerSurface.surfaceTexture != null} " +
                    "texId=${System.identityHashCode(playerSurface.surfaceTexture)} " +
                    "surfaceSize=${if (::zaynPlayer.isInitialized) zaynPlayer.surfaceSizeText() else "n/a"} " +
                    "textureView=${playerSurface.width}x${playerSurface.height} visibility=$vis " +
                    // (v1.0.10, faint-picture evidence 2026-11-27 ~23:09) Log the
                    // TextureView's alpha too: a faint picture could be the view
                    // drawing with low alpha, or a semi-opaque cover above it.
                    // Code check: the layout sets NO alpha (default 1.0), no
                    // setAlpha call exists anywhere, and the only persistent
                    // stage dim is .vshade (top .36 / bottom .46 gradient,
                    // middle transparent) — it dims, it cannot black out.
                    "alpha=${playerSurface.alpha}",
            )
            // (v1.0.12 §9, v1.0.14 G5) Surface recovery trigger: still
            // no texture 200 ms after stage-open while fullscreen — one
            // bounded detach+re-attach (max 1 per play, enforced in the
            // player). A slow texture is normal (session 1 took ~1 s), so
            // this only re-registers the listener; it never blocks. The
            // old mpv exclusion is lifted: libmpv now has recoverSurface()
            // too, and a null texture on mpv got no repair at all.
            if (playerSurface.surfaceTexture == null && isPlayerFullscreen &&
                ::zaynEngine.isInitialized
            ) {
                if (zaynEngine.isMpvActive() && ::zaynMpvPlayer.isInitialized) {
                    EngineLogger.log("playerStage", "surface check texture null while fullscreen (mpv) — recovering surface (bounded 1/play)")
                    zaynMpvPlayer.recoverSurface()
                } else if (::zaynPlayer.isInitialized) {
                    EngineLogger.log("playerStage", "surface check texture null while fullscreen — recovering surface (bounded 1/play)")
                    zaynPlayer.recoverSurface()
                }
            }
        }, 200)
    }

    /**
     * (v1.0.10 L1) Frame-arrival probe — POLLING, not a listener, on
     * purpose. The brief's literal OnFrameAvailableListener would REPLACE
     * TextureView's own internal mUpdateListener (AOSP TextureView.java:
     * mSurface.setOnFrameAvailableListener(mUpdateListener); that listener
     * is what calls updateLayer()+invalidate() on every frame), so the view
     * would stop drawing and the probe itself would CAUSE a black screen.
     * Polling SurfaceTexture.getTimestamp() instead: the timestamp changes
     * only when a new frame is queued to the texture, which is exactly the
     * proof L1 needs, with zero behaviour change. First 5 frames, then
     * every 100th (same throttle as briefed). Runs only while the surface
     * is VISIBLE; stops itself when it goes GONE.
     */
    private fun startFrameProbe() {
        if (frameProbeRunning) return
        frameProbeRunning = true
        probePolls = 0
        probeHealthLogged = false
        val probe = object : Runnable {
            override fun run() {
                if (!frameProbeRunning) return
                if (!::playerSurface.isInitialized ||
                    playerSurface.visibility != View.VISIBLE
                ) {
                    frameProbeRunning = false
                    return
                }
                val st = playerSurface.surfaceTexture
                if (st != null) {
                    val texId = System.identityHashCode(st)
                    if (texId != probedTexId) {
                        probedTexId = texId
                        frameCount = 0
                        lastFrameTs = 0L
                        probePolls = 0
                        probeHealthLogged = false
                        lastHealthFrameTs = 0L
                        healthStallPolls = 0
                        EngineLogger.log(
                            "player",
                            "player texture frame probe attached tex=$texId",
                        )
                    }
                    val ts = st.timestamp
                    if (ts != 0L && ts != lastFrameTs) {
                        lastFrameTs = ts
                        frameCount++
                        if (frameCount == 1) {
                            // (v1.0.12 §8) L9 First Frame Submitted
                            // (observed by proxy: the timestamp advancing
                            // proves a frame was queued to the texture) and
                            // L10 First Frame Displayed (UNVERIFIED-by-code
                            // — only an on-device sighting proves display).
                            val sess = if (::zaynEngine.isInitialized) zaynEngine.currentSession() else 0
                            val eng = if (::zaynEngine.isInitialized && zaynEngine.isMpvActive()) "mpv" else "exo"
                            val vs = if (eng == "mpv" && ::zaynMpvPlayer.isInitialized) zaynMpvPlayer.videoSizeText()
                                else if (::zaynPlayer.isInitialized) zaynPlayer.videoSizeText() else "0x0"
                            val act = if (::zaynPlayer.isInitialized) zaynPlayer.activityState else "unknown"
                            val nowMs = System.currentTimeMillis()
                            EngineLogger.log("player", "diag L9 First Frame Submitted session=$sess engine=$eng ts=$nowMs texId=$texId surfaceSize=${playerSurface.width}x${playerSurface.height} videoSize=$vs state=playing activity=$act observed-by-proxy — timestamp advance proves queueing, not display")
                            EngineLogger.log("player", "diag L10 First Frame Displayed session=$sess engine=$eng ts=$nowMs texId=$texId surfaceSize=${playerSurface.width}x${playerSurface.height} videoSize=$vs state=playing activity=$act UNVERIFIED-by-code — display proven only by on-device sighting")
                        }
                        if (frameCount <= 5 || frameCount % 100 == 0) {
                            EngineLogger.log(
                                "player",
                                "texture frame available #$frameCount tex=$texId " +
                                    "${playerSurface.width}x${playerSurface.height}",
                            )
                        }
                        if (frameCount == 6) {
                            // (v1.0.12 §8) Log the L1 probe cap explicitly.
                            EngineLogger.log("player", "texture frame probe cap — first 5 logged, then every 100th")
                        }
                    }
                }
                // (v1.0.12 §9, v1.0.14 G4) Audio-without-video is
                // UNHEALTHY, never healthy: 5 s (20 consecutive playing
                // polls) with NO NEW FRAME while an engine reports playing.
                // The old check (frameCount == 0) only fired when zero
                // frames EVER arrived — a mid-play freeze at frame N never
                // tripped it, because frameCount is cumulative. The stall
                // rule subsumes it: no frame ever = stalled since play.
                val playingNow = (::zaynPlayer.isInitialized && zaynPlayer.isPlaying()) ||
                    (::zaynMpvPlayer.isInitialized && zaynMpvPlayer.isPlaying())
                if (playingNow) {
                    probePolls++
                    if (lastFrameTs == lastHealthFrameTs) healthStallPolls++ else {
                        healthStallPolls = 0
                        lastHealthFrameTs = lastFrameTs
                    }
                } else {
                    probePolls = 0
                    healthStallPolls = 0
                    lastHealthFrameTs = lastFrameTs
                }
                if (healthStallPolls > 20 && !probeHealthLogged) {
                    probeHealthLogged = true
                    EngineLogger.log("player", "health UNHEALTHY — audio playing but no video frame in 5s (frames stopped at #$frameCount)")
                }
                playerSurface.postDelayed(this, 250)
            }
        }
        playerSurface.post(probe)
    }

    /**
     * (v1.0.5 A3) Re-assert the video hole from the last known-good rect,
     * independently of what the page last reported. Called on every
     * playerState event while the player is open and on config change
     * while fullscreen, so one visible=false report — or a missed report
     * — cannot hide the video for the rest of the session.
     */
    private fun reassertVideoHole() {
        runOnUiThread {
            if (!isPlayerFullscreen) return@runOnUiThread
            if (!::playerSurface.isInitialized || !::webView.isInitialized) return@runOnUiThread
            val good = lastGoodStageRect ?: return@runOnUiThread
            // Log ONLY when the re-assert actually repairs something: this
            // runs on every playerState event (~1/s while playing), and an
            // unconditional log line would flood EngineLogger's 200-line
            // ring buffer and drown the play / error / orientation lines.
            val needsRepair = playerSurface.visibility != View.VISIBLE ||
                lastWebViewBgColor != Color.TRANSPARENT
            // (v1.0.12 §10) When nothing needs repair, do nothing: the old
            // unconditional re-apply re-laid the TextureView on every
            // playerState (~1/s) for no reason.
            if (!needsRepair) return@runOnUiThread
            setWebViewBackground(Color.TRANSPARENT)
            playerSurface.visibility = View.VISIBLE
            applyStageRect(good)
            if (needsRepair) {
                EngineLogger.log(
                    "playerStage",
                    "re-assert hole from lastGoodRect surface=${surfaceState()} " +
                        "webViewBg=#%08X".format(lastWebViewBgColor),
                )
                // (v1.0.8) A repair is a re-open: re-check the surface state after layout.
                postSurfaceCheck()
            }
        }
    }

    /** (v1.0.5 A1) Single place that changes the WebView background, so the
     * colour in effect is always known (and loggable — there is no getter). */
    private fun setWebViewBackground(color: Int) {
        lastWebViewBgColor = color
        if (::webView.isInitialized) webView.setBackgroundColor(color)
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
