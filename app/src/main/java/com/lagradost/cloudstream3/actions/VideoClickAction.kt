package com.lagradost.cloudstream3.actions

import android.content.Context
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText

/**
 * Phase B stub — NOT the upstream VideoClickAction.
 *
 * Upstream declares abstract `name: UiText`, abstract
 * `shouldShow(context, video: ResultEpisode)` and abstract
 * `runAction(context, video, result, index)`,
 * plus helpers that call the app activity, show Toasts and read
 * resource strings — all of which are UI-layer and out of scope for the engine
 * core (external-player actions are Phase C, driven by the bridge).
 *
 * This stub is deliberately a CONCRETE open class with defaults, so a
 * plugin compiled against the upstream class stays instantiable: its
 * upstream-abstract methods become harmless overloads (different JVM
 * signatures) and are simply never called — nothing in Phase B calls
 * shouldShow/runAction. `name` keeps the upstream type (UiText), so
 * plugin overrides of it still bind.
 */
open class VideoClickAction {
    open val name: UiText = UiText.DynamicString("")

    /** if true, the app will show dialog to select source - result.links[index] */
    open val oneSource: Boolean = false

    /** if true, this action could be selected as default player (one press action) in settings */
    open val isPlayer: Boolean = false

    /** Which type of sources this action can handle. */
    open val sourceTypes: Set<ExtractorLinkType> = ExtractorLinkType.entries.toSet()

    /** Determines which plugin a given provider is from. This is the full path to the plugin. */
    var sourcePlugin: String? = null

    fun uniqueId() = "$sourcePlugin:${this::class.qualifiedName}"

    /** Phase B: never called; plugins register actions, the bridge does not run them. */
    open fun shouldShow(context: Context?, video: Any?): Boolean = false
}

object VideoClickActionHolder {
    val allVideoClickActions = atomicListOf<VideoClickAction>()
}
