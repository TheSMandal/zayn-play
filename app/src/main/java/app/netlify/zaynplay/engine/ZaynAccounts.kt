package app.netlify.zaynplay.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase C accounts (engine-design.md §5.8 Accounts, E31–E34).
 *
 * KEY-GATED by design (§8 Q3): the client keys/secrets upstream reads
 * from BuildConfig (ANILIST_KEY, MAL_KEY, SIMKL_CLIENT_ID/SECRET) are
 * NOT in this repo and NOT in the design. There is no key to read, so
 * every login URL request answers a plain "not available yet" and the
 * row stays logged-out — never a crash, never a secret in the repo or
 * in chat. Tokens (when a key exists in a later phase) live in
 * DataStore under zayn/accounts, never in localStorage.
 *
 * Kitsu uses a username/password form login (no OAuth URL); the UI has
 * no credential form in 0.2.0, so Kitsu is gated the same way.
 * AnimeSkip has no login flow in this phase either.
 * accountStatus / accountLogout are real (they read/clear the store).
 */
class ZaynAccounts(private val store: EngineStore) {

    companion object {
        val PROVIDERS = listOf("mal", "anilist", "kitsu", "simkl")
        val NAMES = mapOf(
            "mal" to "MyAnimeList",
            "anilist" to "AniList",
            "kitsu" to "Kitsu",
            "simkl" to "Simkl",
        )
    }

    fun status(): JSONObject {
        val map = store.readJsonMap(EngineStore.KEY_ACCOUNTS)
        val arr = JSONArray()
        for (p in PROVIDERS) {
            val entry = map.optJSONObject(p)
            val loggedIn = entry != null
            val obj = JSONObject()
                .put("provider", p)
                .put("name", NAMES[p] ?: p)
                .put("loggedIn", loggedIn)
            if (loggedIn) obj.put("user", JSONObject().put("name", entry.optString("name", "")))
            arr.put(obj)
        }
        return JSONObject().put("accounts", arr)
    }

    /** Always gated in 0.2.0: no provider key exists in the repo. */
    fun loginUrl(provider: String): JSONObject? {
        if (provider !in PROVIDERS) return null
        return null // key absent -> caller returns the plain not-available error
    }

    fun loginComplete(provider: String, redirectUrl: String, payload: String?): JSONObject {
        // No login flow can complete without a key; a redirect arriving
        // here anyway is a plain failure, never a crash.
        EngineLogger.log("accounts", "loginComplete $provider ignored (no key)")
        return JSONObject().put("loggedIn", false)
    }

    fun logout(provider: String): JSONObject {
        store.removeMapEntry(EngineStore.KEY_ACCOUNTS, provider)
        return JSONObject().put("provider", provider).put("loggedIn", false)
    }

    /** Used by a later phase once keys exist: store a token + user name. */
    fun storeToken(provider: String, token: String, userName: String) {
        store.putMapEntry(
            EngineStore.KEY_ACCOUNTS,
            provider,
            JSONObject().put("token", token).put("name", userName),
        )
    }
}
