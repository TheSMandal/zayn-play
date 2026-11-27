package app.netlify.zaynplay

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * App theme choice (Settings > Layout > App theme: System / Dark / Light).
 *
 * Single source of truth for the NATIVE side: the WebView owns the setting
 * (localStorage `zayn-theme-pref`) and pushes every change through the
 * ZaynAppNative bridge (MainActivity.setThemePref), which stores it here.
 * SplashActivity reads it before super.onCreate so the splash resolves
 * the right night/light resources; MainActivity does the same, falling
 * back to FOLLOW_SYSTEM when nothing is stored yet.
 *
 * Boundary, stated plainly: the system-drawn starting window (the first
 * frame(s) before any app code runs) still resolves against the SYSTEM
 * theme — no app code can change that. What this fixes is the 3-second
 * splash itself and everything after it.
 */
object ThemePrefs {
    private const val PREFS_NAME = "zaynplay"
    private const val KEY_THEME_PREF = "theme_pref"

    fun modeFor(pref: String?): Int = when (pref) {
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Apply the stored choice (FOLLOW_SYSTEM when unset). Call before super.onCreate. */
    fun applyStoredNightMode(context: Context) {
        val pref = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_PREF, null)
        AppCompatDelegate.setDefaultNightMode(modeFor(pref))
    }

    fun store(context: Context, pref: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME_PREF, pref).apply()
    }
}
