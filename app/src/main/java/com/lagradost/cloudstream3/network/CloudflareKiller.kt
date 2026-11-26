package com.lagradost.cloudstream3.network

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response

/**
 * (v0.2.6) Minimal host-compatibility stub for upstream's
 * CloudflareKiller. 7 Phisher plugins construct it in their load
 * closure (member surface proven from their dexes: only the no-arg
 * <init>), so without this class their plugin classes fail
 * verification with load_class_missing.
 *
 * HONEST LIMIT — this is NOT upstream's bypass. Upstream solves
 * Cloudflare challenges in a WebView (WebViewResolver) and replays
 * the cf_clearance cookies; that solver is not ported. This stub
 * implements okhttp3.Interceptor and passes requests straight
 * through (chain.proceed), so a provider that NEEDS a bypass will
 * get the site's real 403/503 at content/link time instead of
 * failing to load. getCookieHeaders returns whatever cookies this
 * stub has saved for the host (none, unless a caller saved them
 * via the map) and parseCookieMap keeps upstream's parsing shape.
 */
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /**
     * Gets the headers with cookies for the url (no WebView user
     * agent here — upstream's came from WebViewResolver).
     */
    fun getCookieHeaders(url: String): Headers {
        val host = try {
            java.net.URI(url).host
        } catch (_: Exception) {
            null
        }
        val cookies = host?.let { savedCookies[it] } ?: emptyMap()
        val builder = Headers.Builder()
        if (cookies.isNotEmpty()) {
            builder.add(
                "Cookie",
                cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            )
        }
        return builder.build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // Pass-through: no WebView challenge solver in this build.
        return chain.proceed(chain.request())
    }
}
