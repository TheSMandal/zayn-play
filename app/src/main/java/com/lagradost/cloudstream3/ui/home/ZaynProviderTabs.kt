package com.lagradost.cloudstream3.ui.home

import android.content.Context
import android.graphics.Color
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.json.JSONArray

/**
 * Zayn Play provider tabs + per-provider ON/OFF.
 *
 * Shared contract (other Zayn agents rely on the exact key):
 * enabled set = DataStore key "zayn_enabled_providers", ONE comma-separated
 * string of provider names, default empty (all OFF). The stored set holds
 * RUNTIME MainAPI names: a name is resolved to its loaded runtime provider
 * when it is turned ON ([setProviderEnabled], [setProvidersEnabled]) and
 * any stale entry is healed at plugin-load time ([healEnabledNames]), so a
 * home tab can never be missed because the repo catalogue spells the name
 * differently ("Disney+", "NetflixMirror", "MovieBoxProviderIN"). Every
 * comparison also normalises both sides via [normalizeProviderName], so a
 * provider turned ON under either spelling is recognised under the other.
 * There is no `SettingsManager` class in this codebase; the app-wide
 * settings store is CloudStreamApp/DataStore getKey/setKey, which is what
 * this uses. One plugin = one MainAPI = one tab; resolution only fixes the
 * spelling/identity match, it never splits a plugin (e.g. CNC Verse stays
 * one "CNC Verse" tab — its Netflix/Prime/Disney are sections inside it).
 */
object ZaynProviders {
    const val ENABLED_PROVIDERS_KEY = "zayn_enabled_providers"
    const val ZAYN_REPOS_ASSET = "zayn_repos.json"

    data class ZaynRepo(
        val id: String,
        val name: String,
        val desc: String,
        val shortcode: String,
        val url: String,
        val plugins: List<String>,
    )

    private var cachedRepos: List<ZaynRepo>? = null

    /** Bundled 27-repo catalogue from assets/zayn_repos.json (cloudstreamrepo.com). */
    fun loadRepos(context: Context): List<ZaynRepo> {
        cachedRepos?.let { return it }
        val parsed = try {
            val json = context.assets.open(ZAYN_REPOS_ASSET).bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val plugins = o.optJSONArray("plugins")
                ZaynRepo(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    desc = o.optString("desc"),
                    shortcode = o.optString("shortcode"),
                    url = o.optString("url"),
                    plugins = List(plugins?.length() ?: 0) { j -> plugins!!.getString(j) },
                )
            }
        } catch (e: Throwable) {
            logError(e)
            emptyList()
        }
        cachedRepos = parsed
        return parsed
    }

    fun getEnabledProviders(): Set<String> {
        val raw = getKey<String>(ENABLED_PROVIDERS_KEY).orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun setEnabledProviders(names: Set<String>) {
        setKey(ENABLED_PROVIDERS_KEY, names.sorted().joinToString(","))
    }

    /**
     * Provider names come from two sources that do not always agree: the repo
     * catalogue (zayn_repos.json `plugins`, e.g. "Disney+") and the runtime
     * MainAPI name (e.g. "Disney Plus"). Every enabled-set comparison
     * normalises both sides — lowercase, "+" reads as "plus", all other
     * non-letters/digits dropped — so a provider turned ON under either
     * spelling is recognised under the other ("Disney+" and "Disney Plus"
     * both become "disneyplus"). Normalisation alone is NOT enough: some
     * catalogue names differ from the runtime name by more than spelling
     * ("NetflixMirror" vs "Netflix", "MovieBoxProviderIN" vs "MovieBox") —
     * those are handled by resolving to the runtime identity below
     * ([resolveToRuntimeName], [healEnabledNames]). One plugin = one
     * MainAPI = one tab; this only fixes the spelling/identity match, it
     * never splits a plugin (e.g. CNC Verse stays one "CNC Verse" tab —
     * its Netflix/Prime/Disney are sections inside it).
     */
    fun normalizeProviderName(name: String): String =
        name.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }

    private fun Set<String>.containsNormalized(name: String): Boolean {
        val wanted = normalizeProviderName(name)
        return any { normalizeProviderName(it) == wanted }
    }

    private fun MutableSet<String>.removeNormalized(name: String) {
        val wanted = normalizeProviderName(name)
        removeAll { normalizeProviderName(it) == wanted }
    }

    /**
     * All currently loaded providers, unfiltered: no language, media-type or
     * hasMainPage filter — resolution is about identity, tab visibility is
     * decided separately in [visibleTabProviders].
     */
    fun loadedProviders(): List<MainAPI> =
        APIHolder.apis.withLock { APIHolder.apis.toList() }

    /**
     * Resolve a stored/repo-catalogue name to the RUNTIME MainAPI name of
     * the loaded provider it means, or null when it cannot be resolved.
     * Exact normalised match wins first — this keeps distinct providers
     * separate ("CineVood" vs "CineVood Mirror" never collapse into one).
     * Only when there is no exact match, a containment match is used, and
     * only when EXACTLY ONE loaded provider matches (its normalised name
     * contains the stored one, or the stored one contains it) — several
     * candidates means ambiguous, so do NOT guess and return null instead.
     * Examples: "Disney+" -> "Disney Plus", "NetflixMirror" -> "Netflix",
     * "MovieBoxProviderIN" -> "MovieBox".
     */
    fun resolveToRuntimeName(name: String, loaded: List<MainAPI>): String? {
        val wanted = normalizeProviderName(name)
        loaded.firstOrNull { normalizeProviderName(it.name) == wanted }
            ?.let { return it.name }
        val candidates = loaded.filter {
            val runtime = normalizeProviderName(it.name)
            runtime.contains(wanted) || wanted.contains(runtime)
        }.distinctBy { it.name }
        return if (candidates.size == 1) candidates[0].name else null
    }

    /**
     * Heal the stored enabled set: rewrite every entry to its runtime
     * MainAPI name (see [resolveToRuntimeName]). This is what makes
     * providers the user already turned ON — e.g. "Disney+" stored before
     * resolution existed, or a name stored while its plugin was not loaded
     * yet — start working without re-toggling. Entries that cannot be
     * resolved (plugin not installed) are left untouched. No-ops when
     * nothing is stored, nothing is loaded, or nothing changed.
     */
    fun healEnabledNames(loaded: List<MainAPI>) {
        val current = getEnabledProviders()
        if (current.isEmpty() || loaded.isEmpty()) return
        var changed = false
        val healed = current.map { stored ->
            val resolved = resolveToRuntimeName(stored, loaded)
            if (resolved != null && resolved != stored) {
                changed = true
                resolved
            } else stored
        }.toSet()
        if (changed) setEnabledProviders(healed)
    }

    fun isProviderEnabled(name: String): Boolean {
        val enabled = getEnabledProviders()
        if (enabled.containsNormalized(name)) return true
        // Stored under its runtime spelling (e.g. "Netflix" for the repo
        // name "NetflixMirror") — resolve and check that spelling too.
        val resolved = resolveToRuntimeName(name, loadedProviders())
        return resolved != null && enabled.containsNormalized(resolved)
    }

    /** Returns the new state. Default is OFF: a name not in the set is OFF. */
    fun setProviderEnabled(name: String, enabled: Boolean): Boolean {
        val current = getEnabledProviders().toMutableSet()
        val loaded = loadedProviders()
        if (enabled) {
            // Store the RUNTIME name, so the home tabs (which match on
            // runtime names) can never miss this provider over spelling.
            val toStore = resolveToRuntimeName(name, loaded) ?: name
            // Drop any stale spelling of the same provider (stored before
            // resolution existed), so the set holds one canonical entry.
            current.removeAll { resolveToRuntimeName(it, loaded) == toStore }
            if (!current.containsNormalized(toStore)) current.add(toStore)
        } else {
            val toRemove = resolveToRuntimeName(name, loaded) ?: name
            current.removeNormalized(toRemove)
            // Also drop the raw spelling, in case it was stored before
            // resolution existed (plugin not loaded at toggle time).
            current.removeNormalized(name)
        }
        setEnabledProviders(current)
        return enabled
    }

    /** Enable/disable a batch of providers (Enable All / Disable All). */
    fun setProvidersEnabled(names: Collection<String>, enabled: Boolean) {
        val current = getEnabledProviders().toMutableSet()
        val loaded = loadedProviders()
        if (enabled) {
            for (name in names) {
                val toStore = resolveToRuntimeName(name, loaded) ?: name
                current.removeAll { resolveToRuntimeName(it, loaded) == toStore }
                if (!current.containsNormalized(toStore)) current.add(toStore)
            }
        } else {
            for (name in names) {
                val toRemove = resolveToRuntimeName(name, loaded) ?: name
                current.removeNormalized(toRemove)
                current.removeNormalized(name)
            }
        }
        setEnabledProviders(current)
    }

    /** How many of this repo's providers are currently ON. */
    fun onCountForRepo(repo: ZaynRepo): Int {
        val enabled = getEnabledProviders()
        val loaded = loadedProviders()
        return repo.plugins.count { plugin ->
            enabled.containsNormalized(plugin) ||
                (resolveToRuntimeName(plugin, loaded)
                    ?.let { enabled.containsNormalized(it) } == true)
        }
    }

    /** Deterministic brand tile colour from the provider name (same name = same colour). */
    fun providerTileColor(name: String): Int {
        val palette = intArrayOf(
            Color.parseColor("#E50914"), // NetMirror red
            Color.parseColor("#6C63FF"),
            Color.parseColor("#0D9488"),
            Color.parseColor("#F59E0B"),
            Color.parseColor("#EC4899"),
            Color.parseColor("#2563EB"),
            Color.parseColor("#16A34A"),
            Color.parseColor("#EA580C"),
            Color.parseColor("#4F46E5"),
            Color.parseColor("#0891B2"),
        )
        val hash = name.hashCode() and Int.MAX_VALUE
        return palette[hash % palette.size]
    }

    fun providerInitial(name: String): String =
        name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

    /**
     * Providers shown as home tabs: hasMainPage + enabled + matching the
     * selected languages/media types. NSFW-only providers are hidden unless
     * NSFW is among the selected media types (handled inside
     * filterProviderByPreferredMedia). Order: pinned first, then A-Z.
     * CustomSite clones already appear as distinct MainAPI entries (see
     * MainActivity.onAllPluginsLoaded) so each site is its own tab; names are
     * de-duplicated so no provider appears twice in the tab row.
     */
    fun Context.visibleTabProviders(): List<MainAPI> {
        val enabled = getEnabledProviders()
        if (enabled.isEmpty()) return emptyList()
        val pinned = DataStoreHelper.pinnedProviders.toHashSet()
        return filterProviderByPreferredMedia()
            .filter { it.hasMainPage && enabled.containsNormalized(it.name) }
            .distinctBy { it.name }
            .sortedWith(
                compareByDescending<MainAPI> { pinned.contains(it.name) }
                    .thenBy { it.name.lowercase() }
            )
    }
}
