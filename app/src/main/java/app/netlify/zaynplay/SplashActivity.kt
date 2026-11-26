package app.netlify.zaynplay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity

/**
 * Splash #13 "Bokeh Nights" (native). Shows on cold start, then opens the
 * app. Tap to skip. The in-WebView splash is skipped after this one
 * (MainActivity loads the page with ?bootsplash=0).
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var opened = false
    private val dotAnimators = mutableListOf<ObjectAnimator>()

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
        startDotPulse()
        findViewById<android.view.View>(R.id.splash_root).setOnClickListener { openApp.run() }
        handler.postDelayed(openApp, 1400)
    }

    /**
     * Loading dots, matching the preview's dotpulse exactly: scale 1 -> 1.45
     * -> 1 over 1.05s, ease-in-out, infinite, staggered 0 / 160 / 320ms
     * (the HTML animation-delay .16s / .32s). The equal period keeps the
     * stagger forever, just like the CSS animation.
     */
    private fun startDotPulse() {
        val dots = listOf(
            findViewById<View>(R.id.splash_dot1) to 0L,
            findViewById<View>(R.id.splash_dot2) to 160L,
            findViewById<View>(R.id.splash_dot3) to 320L,
        )
        for ((dot, delay) in dots) {
            for (anim in listOf(
                ObjectAnimator.ofFloat(dot, View.SCALE_X, 1f, 1.45f, 1f),
                ObjectAnimator.ofFloat(dot, View.SCALE_Y, 1f, 1.45f, 1f),
            )) {
                anim.duration = 1050
                anim.interpolator = AccelerateDecelerateInterpolator()
                anim.repeatCount = ValueAnimator.INFINITE
                anim.startDelay = delay
                anim.start()
                dotAnimators.add(anim)
            }
        }
    }

    override fun onDestroy() {
        dotAnimators.forEach { it.cancel() }
        dotAnimators.clear()
        handler.removeCallbacks(openApp)
        super.onDestroy()
    }
}
