package com.lagradost.cloudstream3

import android.app.Activity
import android.content.res.Resources
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.lagradost.cloudstream3.utils.UiText
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

/**
 * (v0.2.6) Host-compatibility shim for upstream's CommonActivity
 * object. See CloudStreamApp.kt for the proven cause: this class was
 * missing from the host dex while all 88 Phisher plugins reference
 * it (INSTANCE field, getActivity(), showToast), so plugin-class
 * verification threw NoClassDefFoundError -> load_class_missing.
 *
 * Only the surface plugins actually reference is restored (proven
 * from the 88 dexes): INSTANCE, getActivity(), showToast(String?,
 * Int?) / showToast(Activity?, String?, Int?) (+ the $default
 * synthetic Kotlin generates for the default arg), and the metrics
 * helpers. The rest of upstream's CommonActivity (theme handling,
 * locale, focus navigation, torrent cleanup, Cast, notification
 * permission) is CloudStream-UI work and is NOT restored; nothing
 * here touches R, MainActivity, Torrent or NewPipe.
 *
 * Wiring: Zayn Play's own MainActivity calls setActivityInstance(this)
 * in onCreate/onResume, so getActivity() returns the live activity
 * (or null when none is resumed — callers already null-check, as
 * upstream's showToast does). Toasts are plain platform toasts.
 */
object CommonActivity {

    private var _activity: WeakReference<Activity>? = null
    var activity
        get() = _activity?.get()
        private set(value) {
            _activity = WeakReference(value)
        }

    @MainThread
    fun setActivityInstance(newActivity: Activity?) {
        activity = newActivity
    }

    val displayMetrics: DisplayMetrics = Resources.getSystem().displayMetrics

    val screenWidth: Int
        get() {
            return max(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    val screenHeight: Int
        get() {
            return min(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }
    val screenWidthWithOrientation: Int
        get() {
            return displayMetrics.widthPixels
        }
    val screenHeightWithOrientation: Int
        get() {
            return displayMetrics.heightPixels
        }

    const val TAG = "COMPACT"

    fun showToast(@StringRes message: Int, duration: Int? = null) {
        val act = activity ?: return
        act.runOnUiThread {
            showToast(act, act.getString(message), duration)
        }
    }

    fun showToast(message: String?, duration: Int? = null) {
        val act = activity ?: return
        act.runOnUiThread {
            showToast(act, message, duration)
        }
    }

    fun showToast(message: UiText?, duration: Int? = null) {
        val act = activity ?: return
        if (message == null) return
        act.runOnUiThread {
            showToast(act, message.asString(act), duration)
        }
    }

    @MainThread
    fun showToast(act: Activity?, text: UiText, duration: Int) {
        if (act == null) return
        text.asStringNull(act)?.let {
            showToast(act, it, duration)
        }
    }

    /** duration is Toast.LENGTH_SHORT if null */
    @MainThread
    fun showToast(act: Activity?, @StringRes message: Int, duration: Int? = null) {
        if (act == null) return
        showToast(act, act.getString(message), duration)
    }

    /** duration is Toast.LENGTH_SHORT if null */
    @MainThread
    fun showToast(act: Activity?, message: String?, duration: Int? = null) {
        if (act == null || message == null) {
            Log.w(TAG, "invalid showToast act = $act message = $message")
            return
        }
        try {
            Toast.makeText(act, message.trim(), duration ?: Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }
}
