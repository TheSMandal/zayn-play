package app.netlify.zaynplay.engine

import com.lagradost.cloudstream3.APIHolder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase C library + resume (engine-design.md §5.6, E19–E21).
 *
 * Phase B deferred DataStoreHelper (841 lines, deeply Compose/UI-coupled:
 * @Immutable, MainActivity, R, WatchType, ResultViewModel types). It is
 * NOT vendored here. Instead this class keeps the same durable contract
 * on the same DataStore, with the same bookmark-id derivation upstream
 * uses, so entries survive across details opens:
 *
 * ResultViewModel2.kt:376-379 (reference clone, verified):
 *   url.replace(getApiFromNameNull(apiName)?.mainUrl ?: "", "")
 *      .replace("/", "").hashCode()
 * (Kotlin String.hashCode — the JVM Java hash. The same call in this
 * file therefore produces the same id for the same url + apiName.)
 *
 * Library statuses: watching | plantowatch | completed | onhold |
 * dropped (upstream WatchType names, lower-cased for the bridge).
 * Resume positions are keyed by title, matching the UI's saveProgress
 * contract (design §4.3 E21); library items carry their progress from
 * the same map.
 */
class ZaynLibrary(private val store: EngineStore) {

    companion object {
        /** Exact upstream derivation (ResultViewModel2.kt:376). */
        fun bookmarkId(url: String, apiName: String): Int {
            val mainUrl = APIHolder.getApiFromNameNull(apiName)?.mainUrl ?: ""
            return url.replace(mainUrl, "").replace("/", "").hashCode()
        }

        val STATUSES = setOf("watching", "plantowatch", "completed", "onhold", "dropped")
    }

    fun library(): JSONObject {
        val map = store.readJsonMap(EngineStore.KEY_LIBRARY)
        val progress = store.readJsonMap(EngineStore.KEY_PROGRESS)
        val arr = JSONArray()
        val keys = map.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val entry = map.optJSONObject(id) ?: continue
            val title = entry.optString("title", "")
            val prog = progress.optJSONObject(title)
            if (prog != null) {
                entry.put("progressSec", prog.optInt("posSec", 0))
                entry.put("durationSec", prog.optInt("durSec", 0))
            }
            arr.put(entry)
        }
        return JSONObject().put("items", arr)
    }

    /**
     * Set (or remove with status "remove") a library entry. The UI calls
     * this when a title starts playing (watching), when playback ends
     * (completed), and from the library/details status control.
     */
    fun librarySet(
        url: String,
        providerName: String,
        status: String,
        title: String,
        type: String,
        poster: String,
    ): JSONObject {
        if (url.isBlank()) return JSONObject().put("status", JSONObject.NULL)
        val id = bookmarkId(url, providerName).toString()
        if (status == "remove") {
            store.removeMapEntry(EngineStore.KEY_LIBRARY, id)
            return JSONObject().put("status", JSONObject.NULL)
        }
        val clean = status.lowercase()
        if (clean !in STATUSES) return JSONObject().put("status", JSONObject.NULL)
        val entry = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("type", type)
            .put("poster", poster)
            .put("status", clean)
            .put("providerName", providerName)
            .put("url", url)
        store.putMapEntry(EngineStore.KEY_LIBRARY, id, entry)
        return JSONObject().put("status", clean)
    }

    /** Mark a title as watching (called on play) if it has no entry yet. */
    fun markWatching(url: String, providerName: String, title: String, type: String, poster: String) {
        if (url.isBlank() || title.isBlank()) return
        val id = bookmarkId(url, providerName).toString()
        if (store.mapEntry(EngineStore.KEY_LIBRARY, id) != null) return
        librarySet(url, providerName, "watching", title, type, poster)
    }

    /** Mark completed when playback ends near the end. */
    fun markCompleted(url: String, providerName: String) {
        if (url.isBlank()) return
        val id = bookmarkId(url, providerName).toString()
        val entry = store.mapEntry(EngineStore.KEY_LIBRARY, id) ?: return
        entry.put("status", "completed")
        store.putMapEntry(EngineStore.KEY_LIBRARY, id, entry)
    }

    fun progressGet(title: String): JSONObject {
        val prog = store.mapEntry(EngineStore.KEY_PROGRESS, title)
        return JSONObject()
            .put("posSec", prog?.optInt("posSec", 0) ?: 0)
            .put("durSec", prog?.optInt("durSec", 0) ?: 0)
    }

    fun progressSet(title: String, posSec: Int, durSec: Int): JSONObject {
        if (title.isBlank()) return JSONObject().put("saved", false)
        // Same rule as the UI's saveProgress: drop positions under 10 s
        // or within 30 s of the end.
        if (posSec <= 10 || (durSec > 0 && posSec >= durSec - 30)) {
            store.removeMapEntry(EngineStore.KEY_PROGRESS, title)
            return JSONObject().put("saved", true)
        }
        store.putMapEntry(
            EngineStore.KEY_PROGRESS,
            title,
            JSONObject().put("posSec", posSec).put("durSec", durSec),
        )
        return JSONObject().put("saved", true)
    }

    fun progressAll(): JSONObject {
        val map = store.readJsonMap(EngineStore.KEY_PROGRESS)
        val out = JSONObject()
        val keys = map.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out.put(k, map.optJSONObject(k))
        }
        return out
    }

    fun clearProgress(title: String) {
        store.removeMapEntry(EngineStore.KEY_PROGRESS, title)
    }
}
