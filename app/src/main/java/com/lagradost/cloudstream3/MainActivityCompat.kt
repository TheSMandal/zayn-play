package com.lagradost.cloudstream3

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.utils.Event

/**
 * (v0.2.6) Minimal host-compatibility shim for upstream's MainActivity
 * CLASS (not its UI). Lives in MainActivityCompat.kt because this
 * package already has a MainActivity.kt file holding the `app`
 * networking helper — the class name and the file name are unrelated
 * in Kotlin.
 *
 * Why it exists: StremioX / StreamPlay / Ultima / WatchParty reference
 * MainActivity and MainActivity.Companion events (proven member
 * surface from their dexes: Companion.getAfterPluginsLoadedEvent(),
 * getBookmarksUpdatedEvent(), getReloadLibraryEvent() — all
 * Event<Boolean>) in their load closure, so without this class their
 * plugin classes fail verification with load_class_missing.
 *
 * What it is NOT: it is never launched (not in the manifest) and it
 * carries none of upstream MainActivity's UI — no fragments, no
 * navigation, no player, no library. It only hosts the companion
 * events so plugins can subscribe / fire them. Zayn Play's own
 * activity is app.netlify.zaynplay.MainActivity (different package);
 * plugin code that casts the current activity to THIS class will
 * fail at runtime with a ClassCastException — that is a feature-time
 * failure, surfaced by the v0.2.6 forensic detail, not a load failure.
 *
 * The host never fires these events (the vendored PluginManager
 * does not reference them); a plugin that only SUBSCRIBES to them
 * simply never gets the callback, and a plugin that fires one
 * notifies only other plugins listening on the same event.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        const val TAG = "MAINACT"

        /** Fires every time a new batch of plugins have loaded. */
        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
    }
}
