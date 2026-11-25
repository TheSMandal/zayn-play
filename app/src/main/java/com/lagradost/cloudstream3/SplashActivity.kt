package com.lagradost.cloudstream3

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.loadThemes
import com.lagradost.cloudstream3.ui.account.AccountSelectActivity2

/**
 * Zayn Play splash #13 "Bokeh Nights" (preview spec). Shows briefly on cold
 * start, then hands off to the account selector. Never shown when the app is
 * re-launched into an existing task.
 */
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Zayn Play: apply the user's theme (System/Dark/Light) BEFORE
        // super.onCreate, exactly like MainActivity — otherwise the splash
        // (window background + layout) always renders under the dark
        // AppTheme, even in light mode.
        loadThemes(this)
        super.onCreate(savedInstanceState)
        if (!isTaskRoot) {
            finish()
            return
        }
        setContentView(R.layout.activity_splash)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, AccountSelectActivity2::class.java))
                finish()
            }
        }, 1400)
    }
}
