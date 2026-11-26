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
        // FIX 5: splash follows the APP's theme (System/Dark/Light), not
        // just the system setting — apply the stored choice before
        // super.onCreate so the splash theme/layout resolve the right
        // night/light resources (FOLLOW_SYSTEM when nothing is stored).
        ThemePrefs.applyStoredNightMode(this)
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
        handler.postDelayed(openApp, 3000)
    }

    /**
     * Loading dots: a clear left-to-right wave — each dot grows AND
     * brightens in turn (scale 1 -> 1.6 -> 1, alpha floor -> peak -> floor
     * over 1.05s, AccelerateDecelerate, infinite, staggered 0 / 160 /
     * 320ms, the HTML dotpulse delays .16s / .32s). The old scale-only
     * 1 -> 1.45 pulse on 7dp dots was a faint shimmer: rendered frame by
     * frame, it is only a 3px size change with brightness never changing.
     * 9dp dots with the brightness wave read at a glance, dark and light.
     * Dots stay red (@color/splash_play), 6dp apart, 26dp below the tagline.
     */
    private fun startDotPulse() {
        // Dot, stagger delay, and (alpha floor, alpha peak): the peak is the
        // dot's XML alpha (.45 / .75 / 1), the floor is ~45% of it, so the
        // brightness wave keeps the dots' light-to-dark order.
        val dots = listOf(
            Triple(findViewById<View>(R.id.splash_dot1), 0L, 0.20f to 0.45f),
            Triple(findViewById<View>(R.id.splash_dot2), 160L, 0.34f to 0.75f),
            Triple(findViewById<View>(R.id.splash_dot3), 320L, 0.45f to 1.0f),
        )
        for ((dot, delay, alpha) in dots) {
            val (floor, peak) = alpha
            for (anim in listOf(
                ObjectAnimator.ofFloat(dot, View.SCALE_X, 1f, 1.6f, 1f),
                ObjectAnimator.ofFloat(dot, View.SCALE_Y, 1f, 1.6f, 1f),
                ObjectAnimator.ofFloat(dot, View.ALPHA, floor, peak, floor),
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
