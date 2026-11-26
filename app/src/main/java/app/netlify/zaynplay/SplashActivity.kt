package app.netlify.zaynplay

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * Splash #13 "Bokeh Nights" (native). Shows on cold start, then opens the
 * app. Tap to skip. The in-WebView splash is skipped after this one
 * (MainActivity loads the page with ?bootsplash=0).
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var opened = false

    private val openApp = Runnable {
        if (opened) return@Runnable
        opened = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same form-factor rule as MainActivity: TV / tablet open in
        // landscape by default, phone stays portrait.
        val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType
        val isLarge = uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (isLarge) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_splash)
        findViewById<android.view.View>(R.id.splash_root).setOnClickListener { openApp.run() }
        handler.postDelayed(openApp, 1400)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openApp)
        super.onDestroy()
    }
}
