package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.SyncAPI

/**
 * (v0.2.10) Minimal restore of upstream's SimklApi.
 *
 * StreamCenter's Simkl tracking calls only
 * SimklApi.Companion.getHeaders(token) to build the auth headers for
 * its Simkl library calls. The class itself is never constructed by
 * plugins. Upstream's getHeaders returns the Bearer token plus the
 * "simkl-api-key" CLIENT_ID, which is upstream's own app secret
 * (BuildConfig.SIMKL_CLIENT_ID) — it is not in the public repo and
 * cannot be reproduced here, so the key is empty: Simkl's API will
 * reject the calls, and the plugin reports the tracking as not
 * connected, instead of crashing on a missing class. The Bearer part
 * is the user's own token, passed through untouched.
 */
class SimklApi : SyncAPI() {
    companion object {
        fun getHeaders(token: AuthToken): Map<String, String> = mapOf(
            "Authorization" to "Bearer ${token.accessToken}",
            "simkl-api-key" to "",
        )
    }
}
