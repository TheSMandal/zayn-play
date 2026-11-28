package app.netlify.zaynplay.engine

import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * (v1.0.13 P3-C) Header manager — the ONE place that builds request
 * headers per provider/link. Every request shape that talks to a
 * provider's CDN goes through here, so the probe/validator and the
 * ExoPlayer MediaSource path can never drift apart:
 *
 *  - probe/validator: [headersFor] (ZaynEngine probePlayable /
 *    validateManifestLink / measureTtfb)
 *  - ExoPlayer: [headersForPlayLink] (ZaynPlayer.play, applied as
 *    setDefaultRequestProperties on the per-link OkHttpDataSource)
 *
 * Per-provider isolation is STRUCTURAL: a header map is built ONLY from
 * the link it belongs to (its own Referer + its own link headers via
 * getAllHeaders()), copied into a fresh map — never from a shared or
 * previously-built map. [isIsolated] is the runtime check for the one
 * case structure cannot rule out: a provider plugin handing back
 * another provider's Referer/headers. The engine runs it when it builds
 * headers for a link from a different source than the previous link and
 * logs a WARNING (never blocks play — two providers on the same site
 * may legitimately share a Referer).
 *
 * (v1.0.13 P3-B) Signed-URL request policy lives here too: [isSignedUrl]
 * recognises a signed URL (an `in=` query token and/or a
 * `::<epoch-seconds>::` token segment) and [cacheControlFor] returns
 * "no-store" for it, so a cacheable error response can never be replayed.
 * No token value is ever logged from this file.
 */
object ZaynHeaderManager {

    /** True when the URL carries a signed query token (`in=...`) and/or a
     * `::<epoch-seconds>::` token segment (INVESTIGATION-4 §5.1). */
    fun isSignedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.contains("?in=") || url.contains("&in=") ||
            Regex("::\\d{9,11}::").containsMatchIn(url)
    }

    /** Request Cache-Control for a manifest fetch: "no-store" for signed
     * URLs (never serve from cache, never store — a cacheable error
     * response must not be replayed to the player), null otherwise. */
    fun cacheControlFor(url: String?): String? =
        if (isSignedUrl(url)) "no-store" else null

    /** Headers for one provider link: its Referer + link headers via
     * getAllHeaders(), copied into a fresh map. The copy matters: the
     * map getAllHeaders() returns must never be shared or mutated by a
     * caller, or one provider's headers could leak into the next link's
     * request. */
    fun headersFor(link: ExtractorLink): Map<String, String> =
        LinkedHashMap(link.getAllHeaders())

    /** Headers for a PlayLink (the snapshot ExoPlayer plays): the
     * link's Referer first, then the snapshot headers, deduped
     * case-insensitively on "referer" (the snapshot already contains the
     * Referer via getAllHeaders(), so a naive merge would send it twice). */
    fun headersForPlayLink(link: ZaynPlayer.PlayLink): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (link.referer.isNotBlank()) out["referer"] = link.referer
        for ((k, v) in link.headers) {
            if (k.equals("referer", ignoreCase = true)) continue
            out[k] = v
        }
        return out
    }

    /** (v1.0.13 P4-A) Re-attach the top-level URL's query (the signed
     * token) to a resolved child URL that carries none. RFC 3986 drops
     * the query on relative resolution, so a child resolved from a
     * signed master URL loses `?in=...` unless it is put back here.
     * SAME RULE as ZaynEngine.reattachToken (validator's own fetches)
     * — the two MUST stay identical; this copy serves ExoPlayer's
     * child/segment requests at play time via
     * ZaynPlayer.TokenReattachingDataSource, the engine copy serves
     * the validator's child probes. A child URL that already carries
     * a query is never touched, and a top URL with no query changes
     * nothing. */
    fun reattachToken(topUrl: String, childUrl: String): String {
        if (childUrl.contains("?")) return childUrl
        val query = topUrl.substringAfter("?", "").substringBefore("#")
        if (query.isEmpty()) return childUrl
        val hashIdx = childUrl.indexOf('#')
        return if (hashIdx >= 0) {
            childUrl.substring(0, hashIdx) + "?" + query + childUrl.substring(hashIdx)
        } else {
            "$childUrl?$query"
        }
    }

    /** Per-provider isolation check: [candidateHeaders] (for one
     * provider's link) must not carry [otherHeaders]' (another
     * provider's) Referer, Authorization or Cookie. Returns false when
     * the candidate's Referer equals the other's Referer, or when a
     * sensitive header value is identical — either is a cross-provider
     * leak. Two links from the SAME provider legitimately share headers;
     * only ever compare across providers. */
    fun isIsolated(
        candidateHeaders: Map<String, String>,
        otherHeaders: Map<String, String>,
    ): Boolean {
        val candReferer = candidateHeaders.entries
            .firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value
        val otherReferer = otherHeaders.entries
            .firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value
        if (candReferer != null && otherReferer != null && candReferer == otherReferer) return false
        for ((k, v) in candidateHeaders) {
            val lk = k.lowercase()
            if (lk == "authorization" || lk == "cookie") {
                val ov = otherHeaders.entries
                    .firstOrNull { it.key.equals(k, ignoreCase = true) }?.value
                if (ov != null && ov == v) return false
            }
        }
        return true
    }
}
