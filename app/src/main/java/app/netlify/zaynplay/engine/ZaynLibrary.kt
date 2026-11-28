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
            // (v1.0.16 P11-06) Progress for a series lives under per-episode
            // keys "<base>#ep:<n>", not the series-level key — attach the
            // FURTHEST episode's position (largest posSec; ties break to
            // the higher episode number) plus its episode label, so the
            // Library card and Continue Watching show real series progress.
            // (v1.0.16 P11-08) No bare-title fallback read: the V1.0.14
            // resume leak was removed from progressGet, and library() must
            // not reintroduce it through its own lookup.
            val baseKey = progressKey(entry.optString("url", ""), entry.optString("providerName", ""), "")
            var best: JSONObject? = progress.optJSONObject(baseKey)
            var bestEp = ""
            if (baseKey.isNotBlank()) {
                val pk = progress.keys()
                while (pk.hasNext()) {
                    val k = pk.next()
                    if (!k.startsWith("$baseKey#ep:")) continue
                    val cand = progress.optJSONObject(k) ?: continue
                    val candEp = k.removePrefix("$baseKey#ep:")
                    val candPos = cand.optInt("posSec", 0)
                    val bestPos = best?.optInt("posSec", 0) ?: -1
                    val candNum = candEp.removePrefix("E").toIntOrNull() ?: 0
                    val bestNum = bestEp.removePrefix("E").toIntOrNull() ?: 0
                    if (candPos > bestPos || (candPos == bestPos && candNum > bestNum)) {
                        best = cand
                        bestEp = candEp
                    }
                }
            }
            val prog = best
            if (prog != null) {
                entry.put("progressSec", prog.optInt("posSec", 0))
                entry.put("durationSec", prog.optInt("durSec", 0))
                if (bestEp.isNotBlank()) entry.put("progressEp", bestEp)
            }
            arr.put(entry)
        }
        return JSONObject().put("items", arr)
    }

    /**
     * Set (or remove with status "remove") a library entry. The UI calls
     * this ONLY from an explicit bookmark action (details / player /
     * search Library button) and from the playback-end path (completed,
     * by id). Playing a title never adds it (P11-01).
     */
    fun librarySet(
        url: String,
        providerName: String,
        status: String,
        title: String,
        type: String,
        poster: String,
        year: String = "",
    ): JSONObject {
        if (url.isBlank()) return JSONObject().put("status", JSONObject.NULL)
        var id = bookmarkId(url, providerName).toString()
        if (status == "remove") {
            store.removeMapEntry(EngineStore.KEY_LIBRARY, id)
            return JSONObject().put("status", JSONObject.NULL)
        }
        val clean = status.lowercase()
        if (clean !in STATUSES) return JSONObject().put("status", JSONObject.NULL)
        // (v1.0.16 P11-04) A 32-bit bookmark id can collide: if a DIFFERENT
        // title already lives under this id, never silently merge/overwrite
        // it — keep both under a suffixed id and log the collision.
        val existing = store.mapEntry(EngineStore.KEY_LIBRARY, id)
        if (existing != null) {
            val existingTitle = existing.optString("title", "")
            if (existingTitle.isNotBlank() && title.isNotBlank() && existingTitle != title) {
                EngineLogger.log("library", "bookmarkId collision id=$id existing='$existingTitle' new='$title' — keeping both")
                id = "$id-${(title.hashCode() and 0x7FFFFFFF)}"
            }
        }
        val now = System.currentTimeMillis()
        val kept = store.mapEntry(EngineStore.KEY_LIBRARY, id)
        val entry = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("type", type)
            .put("poster", poster)
            .put("year", year)
            .put("status", clean)
            .put("providerName", providerName)
            .put("url", url)
            .put("addedAt", kept?.optLong("addedAt", now) ?: now)
            .put("updatedAt", now)
        store.putMapEntry(EngineStore.KEY_LIBRARY, id, entry)
        return JSONObject().put("status", clean)
    }

    /** (v0.2.11) C-16: set a library entry's status by bookmark id —
        the playback-end call carries the id only, no URL. */
    fun librarySetById(id: String, status: String): JSONObject {
        if (status.lowercase() == "remove") {
            store.removeMapEntry(EngineStore.KEY_LIBRARY, id)
            return JSONObject().put("status", JSONObject.NULL)
        }
        val clean = status.lowercase()
        if (clean !in STATUSES) return JSONObject().put("status", JSONObject.NULL)
        val entry = store.mapEntry(EngineStore.KEY_LIBRARY, id)
            ?: return JSONObject().put("status", JSONObject.NULL)
        entry.put("status", clean)
        entry.put("updatedAt", System.currentTimeMillis())
        store.putMapEntry(EngineStore.KEY_LIBRARY, id, entry)
        return JSONObject().put("status", clean)
    }

    /** Mark completed when playback ends near the end. Only marks an
        entry the user explicitly bookmarked — never creates one (P11-01). */
    fun markCompleted(url: String, providerName: String) {
        if (url.isBlank()) return
        val id = bookmarkId(url, providerName).toString()
        val entry = store.mapEntry(EngineStore.KEY_LIBRARY, id) ?: return
        entry.put("status", "completed")
        entry.put("updatedAt", System.currentTimeMillis())
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
        // (v1.0.16 P11-07) addedAt/updatedAt so Watch History can list
        // plays with dates without depending on library membership.
        val now = System.currentTimeMillis()
        val kept = store.mapEntry(EngineStore.KEY_PROGRESS, key)
        store.putMapEntry(
            EngineStore.KEY_PROGRESS,
            key,
            JSONObject()
                .put("posSec", posSec).put("durSec", durSec)
                .put("title", title).put("url", url)
                .put("providerName", providerName).put("ep", ep)
                .put("addedAt", kept?.optLong("addedAt", now) ?: now)
                .put("updatedAt", now),
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
