package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import kotlin.Throws

abstract class Plugin : BasePlugin() {
    /**
     * (v0.2.9) Fix A: the `$stable` field Kotlin 2.x emits in every class.
     * Plugin subclasses compiled against upstream (Kotlin 2.x) read this
     * field in their <clinit> (AnichiPlugin does
     * `sget Plugin->$stable`); our host is Kotlin 1.9.24, which emits no
     * `$stable` anywhere, so those plugins failed to load. A companion
     * @JvmField lands as a PUBLIC STATIC field in THIS class (verified in
     * the v0.2.8 dex: EngineStore's companion const vals are static fields
     * of EngineStore, never of EngineStore$Companion). Value 8 is the
     * upstream "stable" marker; nothing in this app reads the value at
     * runtime (no Compose runtime) — only the field's existence matters.
     */
    companion object {
        @JvmField
        val `$stable`: Int = 8
    }

    /**
     * Called when your Plugin is loaded
     * @param context Context
     */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        // If not overridden by an extension then try the cross-platform load()
        load()
    }

    /**
     * Used to register VideoClickAction instances
     * @param element VideoClickAction you want to register
     */
    fun registerVideoClickAction(element: VideoClickAction) {
        Log.i(PLUGIN_TAG, "Adding ${element.name} VideoClickAction")
        element.sourcePlugin = this.filename
        VideoClickActionHolder.allVideoClickActions.add(element)
    }

    /**
     * This will contain your resources if you specified requiresResources in gradle
     */
    var resources: Resources? = null

    /**
     * This will add a button in the settings allowing you to add custom settings
     */
    var openSettings: ((context: Context) -> Unit)? = null
}
