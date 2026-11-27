package com.lagradost.cloudstream3.ui.settings

import android.content.res.Configuration
import android.content.res.Resources

/**
 * (v0.2.10) Minimal restore of upstream's Globals (ui/settings).
 *
 * Live plugins call only isLayout(Int) — the Cloudflare WebView
 * solvers (CineVood, MovieLinkBD, MLSBD, Nepu, AnimeWorldIndia,
 * Multimovies) use it to pick the phone vs TV solving path.
 * Upstream's layoutId is set by updateTv(), which needs the Compose
 * DeviceLayout helper and a settings key this host does not have, so
 * it is NOT restored; layoutId stays PHONE, which is what this app
 * runs on. isLandscape() is upstream's verbatim implementation.
 */
object Globals {
    var beneneCount = 0

    const val PHONE: Int = 0b00001
    const val TV: Int = 0b00010
    const val EMULATOR: Int = 0b00100
    private var layoutId = PHONE

    /** Returns true if the current orientation is landscape. */
    fun isLandscape(): Boolean =
        isLayout(TV or EMULATOR) ||
            Resources.getSystem().configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Returns true if the layout is any of the flags. Valid flags
     * are: PHONE, TV, EMULATOR. */
    fun isLayout(flags: Int): Boolean {
        return (layoutId and flags) != 0
    }
}
