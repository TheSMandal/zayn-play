package app.netlify.zaynplay.engine

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * (v1.0.18 P16-07) Bounded disk poster cache.
 *
 * The V1.0.17 in-memory preload (bounded 200) is the only poster cache
 * today: repeat opens re-fetch posters whose CDN sends short/no-cache
 * headers, and nothing on disk backs them. This is a 100 MB OkHttp
 * disk cache under `<cacheDir>/posters`: `prefetch()` downloads a
 * bounded batch of poster URLs into it (fire-and-forget, from the
 * `posterPrefetch` bridge), `sizeBytes()` measures it, `clear()`
 * evicts it (called by `cacheClear`).
 *
 * Honest limit, documented not hidden: the HTML `<img>` tags are
 * loaded by the WebView, not by OkHttp, so this cache does not serve
 * the `<img>` requests themselves — the WebView's own HTTP cache does
 * that (and `cacheClear` now clears it too, P16-12). What this cache
 * does is bound the prefetch side: posters the app explicitly warms
 * live on disk under a hard 100 MB cap instead of refetching on every
 * cold start, and the cap is enforced by OkHttp's own eviction.
 * Cap: 100 MB.
 */
class ZaynPosterCache(context: Context) {

    companion object {
        /** Documented cap for the poster disk cache (P16-07). */
        const val CAP_BYTES: Long = 100L * 1024L * 1024L
        const val MAX_PREFETCH_PER_CALL = 30
    }

    private val cacheDir = File(context.cacheDir, "posters")
    private val cache = Cache(cacheDir, CAP_BYTES)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .build()

    /** Download up to MAX_PREFETCH_PER_CALL poster URLs into the disk
        cache. Failures are swallowed per URL (a dead poster must never
        break the rail render); the call never throws. */
    fun prefetch(urls: List<String>): Int {
        var fetched = 0
        for (url in urls.take(MAX_PREFETCH_PER_CALL)) {
            if (url.isBlank()) continue
            try {
                val req = Request.Builder().url(url).header("User-Agent", "ZaynPlay/1.0").build()
                client.newCall(req).execute().use { resp ->
                    // Reading the body is what populates the cache.
                    resp.body?.bytes()
                    if (resp.isSuccessful) fetched++
                }
            } catch (e: Exception) {
                // Dead poster / offline: skip it, keep warming the rest.
            }
        }
        return fetched
    }

    fun sizeBytes(): Long {
        return try {
            cache.size()
        } catch (e: Exception) {
            0L
        }
    }

    fun clear() {
        try {
            cache.evictAll()
        } catch (e: Exception) {
        }
        try {
            if (cacheDir.exists()) cacheDir.deleteRecursively()
        } catch (e: Exception) {
        }
    }
}
