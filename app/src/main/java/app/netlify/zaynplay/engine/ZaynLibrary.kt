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
 * (v1.0.14 G1) Resume positions are keyed by CONTENT, not by title:
 * progressKey(url, providerName, ep) = bookmarkId(url, providerName)
 * (+ "#ep:<n>" for episodes). Title-keyed storage leaked across
 * same-title movies on different providers (V1.0.4 23-minute leak) and
 * across episodes of a series, which all share the series title. A
 * title-keyed entry is kept only as a one-time legacy fallback: the
 * first content-keyed read migrates it (copy + delete). library()
 * reads progress by the same content key.
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
            // (v1.0.14 G1) Progress by content key (url + provider), not
            // by bare title — same-title items must never share a position.
            val prog = progress.optJSONObject(
                progressKey(entry.optString("url", ""), entry.optString("providerName", ""), ""),
            ) ?: progress.optJSONObject(title)
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

    /** (v0.2.11) C-16: set a library entry's status by bookmark id —
        the playback-end call carries the id only, no URL. */
    fun librarySetById(id: String, status: String): JSONObject {
        val clean = status.lowercase()
        if (clean !in STATUSES) return JSONObject().put("status", JSONObject.NULL)
        val entry = store.mapEntry(EngineStore.KEY_LIBRARY, id)
            ?: return JSONObject().put("status", JSONObject.NULL)
        entry.put("status", clean)
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

    /**
     * (v1.0.14 G1) Content key for a resume position: the library
     * bookmark id of the page URL + provider, with an episode suffix
     * when the play is one episode of a series (episodes share the
     * series URL/title, so without the suffix they would share one
     * position). Empty url falls back to the legacy title key.
     */
    fun progressKey(url: String, providerName: String, ep: String): String {
        if (url.isBlank()) return ""
        val base = bookmarkId(url, providerName).toString()
        return if (ep.isBlank()) base else "$base#ep:$ep"
    }

    fun progressGet(title: String, url: String = "", providerName: String = "", ep: String = ""): JSONObject {
        val key = progressKey(url, providerName, ep)
        if (key.isNotBlank()) {
            var prog = store.mapEntry(EngineStore.KEY_PROGRESS, key)
            if (prog == null && title.isNotBlank()) {
                // One-time legacy migration: a title-keyed entry is
                // ambiguous, but it is the only saved position this
                // content has — adopt it under the content key once,
                // then delete the title key so it can never leak again.
                val legacy = store.mapEntry(EngineStore.KEY_PROGRESS, title)
                if (legacy != null) {
                    store.putMapEntry(EngineStore.KEY_PROGRESS, key, legacy)
                    store.removeMapEntry(EngineStore.KEY_PROGRESS, title)
                    prog = legacy
                }
            }
            return JSONObject()
                .put("posSec", prog?.optInt("posSec", 0) ?: 0)
                .put("durSec", prog?.optInt("durSec", 0) ?: 0)
        }
        val prog = store.mapEntry(EngineStore.KEY_PROGRESS, title)
        return JSONObject()
            .put("posSec", prog?.optInt("posSec", 0) ?: 0)
            .put("durSec", prog?.optInt("durSec", 0) ?: 0)
    }

    fun progressSet(
        title: String,
        posSec: Int,
        durSec: Int,
        url: String = "",
        providerName: String = "",
        ep: String = "",
    ): JSONObject {
        if (title.isBlank()) return JSONObject().put("saved", false)
        // (v1.0.14 G1) Store under the content key when the caller sent
        // an identity; title-only callers keep the legacy title key.
        val key = progressKey(url, providerName, ep).ifBlank { title }
        // Same rule as the UI's saveProgress: drop positions under 10 s
        // or within 30 s of the end.
        if (posSec <= 10 || (durSec > 0 && posSec >= durSec - 30)) {
            store.removeMapEntry(EngineStore.KEY_PROGRESS, key)
            return JSONObject().put("saved", true)
        }
        store.putMapEntry(
            EngineStore.KEY_PROGRESS,
            key,
            JSONObject()
                .put("posSec", posSec).put("durSec", durSec)
                .put("title", title).put("url", url)
                .put("providerName", providerName).put("ep", ep),
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

    fun clearProgress(title: String, url: String = "", providerName: String = "", ep: String = "") {
        // (v1.0.14 G1) Clear the content key AND the legacy title key, so
        // no stale entry can resurrect the position through the fallback.
        val key = progressKey(url, providerName, ep)
        if (key.isNotBlank()) store.removeMapEntry(EngineStore.KEY_PROGRESS, key)
        if (title.isNotBlank()) store.removeMapEntry(EngineStore.KEY_PROGRESS, title)
    }
}
