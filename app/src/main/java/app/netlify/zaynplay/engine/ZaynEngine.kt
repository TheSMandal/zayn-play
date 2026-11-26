package app.netlify.zaynplay.engine

import android.app.Activity
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream3.plugins.RepositoryData
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.security.MessageDigest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase B: the real bridge dispatcher (engine-design.md §4.3).
 *
 * Implements E1–E11 + E26–E28 over the vendored PluginManager /
 * RepositoryManager / APIRepository. Everything else stays
 * engine_not_ready (player / downloads / accounts / updater = Phase C).
 *
 * Rules enforced here, not in the UI:
 * - Bridge repo ids are a hash of the repo URL, never the UI's hardcoded
 *   REPOS ids (design §4.3).
 * - Providers of a newly installed repo default OFF. E6 OFF unloads the
 *   plugin (upstream has no disable toggle — isDisabled only reflects
 *   PROVIDER_STATUS_DOWN).
 * - No upstream exception message or stack trace ever reaches the UI:
 *   every failure returns the §4.7 error shape {ok, code, message} with
 *   a fixed message.
 * - All work runs on MainActivity's IO coroutine scope (never the
 *   WebView thread, never main).
 */
class ZaynEngine(
    private val activity: Activity,
    private val store: EngineStore,
) {
    @Volatile
    var engineReady: Boolean = false
        private set

    fun markReady() {
        engineReady = true
    }

    // ---- entry point (called from MainActivity's IO scope) ----

    suspend fun call(method: String, params: JSONObject): JSONObject {
        return try {
            when (method) {
                "engineStatus" -> ok(engineStatus())
                "repos" -> ok(repos())
                "repoAdd" -> repoAdd(params.optString("input", ""))
                "repoRemove" -> repoRemove(params.optString("repoId", ""))
                "repoProviders" -> repoProviders(params.optString("repoId", ""))
                "providerSetOn" -> providerSetOn(
                    params.optString("repoId", ""),
                    params.optString("providerName", ""),
                    params.optBoolean("on", false),
                )
                "providerTest" -> providerTest(
                    params.optString("repoId", ""),
                    params.optString("providerName", ""),
                )
                "providerHealthCheck" -> providerHealthCheck(params.optBoolean("onlyOn", true))
                "homeFeed" -> homeFeed(
                    params.optString("providerName", ""),
                    params.optInt("page", 1),
                )
                "search" -> search(
                    params.optString("query", ""),
                    if (params.has("providerName") && !params.isNull("providerName")) params.optString("providerName") else null,
                    params.optInt("page", 1),
                )
                "details" -> details(
                    params.optString("url", ""),
                    params.optString("providerName", ""),
                )
                "settingsGet" -> ok(JSONObject().put("settings", store.settingsSnapshot()))
                "settingsSet" -> settingsSet(params)
                "pluginUpdateCheck" -> pluginUpdateCheck(params.optBoolean("manual", false))
                else -> {
                    EngineLogger.log("bridge", "$method -> engine_not_ready")
                    err("engine_not_ready", "Engine not ready yet — this feature comes in the next update")
                }
            }
        } catch (e: Exception) {
            EngineLogger.log("bridge", "$method threw ${e.javaClass.simpleName}")
            err("unknown", "Engine error")
        }
    }

    // ---- E1 ----

    private fun engineStatus(): JSONObject = JSONObject()
        .put("version", "0.2.0")
        .put("engineReady", engineReady)
        .put("pluginsLoaded", PluginManager.plugins.size)
        .put("reposInstalled", RepositoryManager.getRepositories().size)
        .put("providersOn", store.providerOnCount())

    // ---- E2 / E3 / E4 ----

    private fun repos(): JSONObject {
        val arr = JSONArray()
        for (repo in RepositoryManager.getRepositories()) {
            val id = repoId(repo.url)
            val folder = PluginManager.getPluginSanitizedFileName(repo.url)
            val downloaded = PluginManager.getPluginsOnline()
                .count { it.filePath.contains("/$folder/") }
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("name", repo.name)
                    .put("url", repo.url)
                    .put("iconUrl", repo.iconUrl ?: JSONObject.NULL)
                    .put("description", repo.description ?: JSONObject.NULL)
                    .put("installed", true)
                    .put("providerCount", downloaded)
                    .put("providersOn", store.providerOnCount(id))
            )
        }
        return JSONObject().put("repos", arr)
    }

    private suspend fun repoAdd(input: String): JSONObject {
        if (input.isBlank()) return err("invalid_input", "Enter a repository link or code")
        val url = try {
            RepositoryManager.parseRepoUrl(input.trim())
        } catch (e: Exception) {
            EngineLogger.log("bridge", "repoAdd parseRepoUrl threw")
            null
        } ?: return err("repo_not_found", "Repository not found")
        val existing = RepositoryManager.getRepositories()
            .firstOrNull { it.url == url }
        if (existing != null) {
            return ok(repoWithProviders(existing))
        }
        val parsed = try {
            RepositoryManager.parseRepository(url)
        } catch (e: Exception) {
            EngineLogger.log("bridge", "repoAdd parseRepository threw")
            null
        } ?: return err("repo_parse_error", "Could not read that repository")
        val repo = RepositoryData(parsed.iconUrl, parsed.name, url, parsed.description)
        try {
            RepositoryManager.addRepository(repo)
        } catch (e: Exception) {
            EngineLogger.log("bridge", "repoAdd addRepository threw")
            return err("unknown", "Could not save that repository")
        }
        EngineLogger.log("bridge", "repoAdd installed ${repo.name}")
        return ok(repoWithProviders(repo))
    }

    private suspend fun repoWithProviders(repo: RepositoryData): JSONObject {
        val providers = try {
            RepositoryManager.getRepoPlugins(repo) ?: emptyList()
        } catch (e: Exception) {
            emptyList<PluginWrapper>()
        }
        val arr = JSONArray()
        for (w in providers) arr.put(providerJson(repo, w))
        return JSONObject()
            .put("repo", repoJson(repo))
            .put("providers", arr)
    }

    private fun repoJson(repo: RepositoryData): JSONObject = JSONObject()
        .put("id", repoId(repo.url))
        .put("name", repo.name)
        .put("url", repo.url)
        .put("iconUrl", repo.iconUrl ?: JSONObject.NULL)
        .put("description", repo.description ?: JSONObject.NULL)
        .put("installed", true)

    private suspend fun repoRemove(repoId: String): JSONObject {
        val repo = findRepo(repoId) ?: return err("repo_not_found", "Repository not found")
        try {
            RepositoryManager.removeRepository(activity, repo)
        } catch (e: Exception) {
            EngineLogger.log("bridge", "repoRemove threw")
            return err("unknown", "Could not remove that repository")
        }
        store.clearRepoState(repoId)
        EngineLogger.log("bridge", "repoRemove removed ${repo.name}")
        return ok(JSONObject().put("repoId", repoId))
    }

    // ---- E5 / E6 ----

    private suspend fun repoProviders(repoId: String): JSONObject {
        val repo = findRepo(repoId) ?: return err("repo_not_found", "Repository not found")
        val wrappers = try {
            RepositoryManager.getRepoPlugins(repo)
        } catch (e: Exception) {
            EngineLogger.log("bridge", "repoProviders getRepoPlugins threw")
            null
        } ?: return err("network_error", "Could not load the provider list")
        val arr = JSONArray()
        for (w in wrappers) arr.put(providerJson(repo, w))
        return ok(JSONObject().put("repoId", repoId).put("providers", arr))
    }

    private fun providerJson(repo: RepositoryData, w: PluginWrapper): JSONObject {
        val p = w.plugin
        val id = repoId(repo.url)
        val api = resolveApi(p.name, p.internalName)
        val loadedPlugin = api?.let { loadedPluginFor(it) }
        return JSONObject()
            .put("name", p.name)
            .put("internalName", p.internalName)
            .put("lang", p.language ?: JSONObject.NULL)
            .put("version", p.version)
            .put("status", p.status)
            .put("on", store.isProviderOn(id, p.name))
            .put("autoOff", store.isProviderAutoOff(id, p.name))
            .put("health", store.providerHealth(id, p.name) ?: JSONObject.NULL)
            .put("hasSettings", (loadedPlugin as? Plugin)?.openSettings != null)
            .put("mainUrl", api?.mainUrl ?: JSONObject.NULL)
            .put("types", JSONArray(p.tvTypes ?: emptyList<String>()))
    }

    private suspend fun providerSetOn(repoId: String, providerName: String, on: Boolean): JSONObject {
        val repo = findRepo(repoId) ?: return err("repo_not_found", "Repository not found")
        val wrapper = findWrapper(repo, providerName)
            ?: return err("provider_not_found", "Provider not found")
        val p = wrapper.plugin
        if (on) {
            val loaded = try {
                PluginManager.downloadPlugin(
                    activity, p.url, p.fileHash, p.internalName, repo.url, true
                )
            } catch (e: Exception) {
                EngineLogger.log("bridge", "providerSetOn download threw")
                false
            }
            if (!loaded) return err("network_error", "Could not download that provider")
            store.setProviderOn(repoId, providerName, true)
            store.setProviderAutoOff(repoId, providerName, false)
            EngineLogger.log("bridge", "providerSetOn ON $providerName")
        } else {
            val data = storedPluginData(repo, p.internalName)
            if (data != null) {
                try {
                    PluginManager.unloadPlugin(data.filePath)
                } catch (e: Exception) {
                    EngineLogger.log("bridge", "providerSetOn unload threw")
                }
            }
            store.setProviderOn(repoId, providerName, false)
            EngineLogger.log("bridge", "providerSetOn OFF $providerName")
        }
        return ok(
            JSONObject()
                .put("repoId", repoId)
                .put("providerName", providerName)
                .put("on", store.isProviderOn(repoId, providerName))
                .put("autoOff", store.isProviderAutoOff(repoId, providerName))
        )
    }

    // ---- E7 / E8 ----

    private suspend fun providerTest(repoId: String, providerName: String): JSONObject {
        val repo = findRepo(repoId) ?: return err("repo_not_found", "Repository not found")
        val wrapper = findWrapper(repo, providerName)
            ?: return err("provider_not_found", "Provider not found")
        val p = wrapper.plugin
        var load = false
        var content = false
        var video = false
        try {
            withTimeout(30_000) {
                var api = resolveApi(p.name, p.internalName)
                if (api == null) {
                    val downloaded = PluginManager.downloadPlugin(
                        activity, p.url, p.fileHash, p.internalName, repo.url, true
                    )
                    if (downloaded) api = resolveApi(p.name, p.internalName)
                }
                if (api != null) {
                    load = true
                    val repoApi = APIRepository(api)
                    val feed = repoApi.getMainPage(1)
                    val sections = (feed as? Resource.Success<List<HomePageResponse?>>)?.value
                    val firstItem = sections
                        ?.flatMap { it?.items ?: emptyList() }
                        ?.firstOrNull { it.list.isNotEmpty() }
                        ?.list?.firstOrNull()
                    content = firstItem != null
                    if (firstItem != null) {
                        val loaded = repoApi.load(firstItem.url)
                        val data = (loaded as? Resource.Success)?.value?.let(::loadDataOf)
                        if (data != null) {
                            var gotLink = false
                            val linksOk = repoApi.loadLinks(data, false, { _: SubtitleFile -> }) { _: ExtractorLink -> gotLink = true }
                            video = linksOk && gotLink
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            EngineLogger.log("bridge", "providerTest timeout $providerName")
        } catch (e: Exception) {
            EngineLogger.log("bridge", "providerTest threw")
        }
        return ok(
            JSONObject()
                .put("repoId", repoId)
                .put("providerName", providerName)
                .put("load", load)
                .put("content", content)
                .put("video", video)
        )
    }

    private suspend fun providerHealthCheck(onlyOn: Boolean): JSONObject {
        val targets = mutableListOf<Triple<String, String, PluginWrapper>>()
        for (repo in RepositoryManager.getRepositories()) {
            val id = repoId(repo.url)
            val wrappers = try {
                RepositoryManager.getRepoPlugins(repo)
            } catch (e: Exception) {
                null
            } ?: continue
            for (w in wrappers) {
                val isOn = store.isProviderOn(id, w.plugin.name)
                if (onlyOn && !isOn) continue
                targets.add(Triple(id, repo.url, w))
            }
        }
        val results = JSONArray()
        val autoOff = JSONArray()
        val autoOn = JSONArray()
        // Concurrency ~4 (design §5.3).
        for (chunk in targets.chunked(4)) {
            val chunkResults = coroutineScope {
                chunk.map { (id, _, w) ->
                    async { healthProbe(id, w) }
                }.awaitAll()
            }
            for (probe in chunkResults) {
                results.put(probe)
                when (probe.optString("change", "")) {
                    "off" -> autoOff.put(probe.optString("providerName", ""))
                    "on" -> autoOn.put(probe.optString("providerName", ""))
                }
            }
        }
        return ok(
            JSONObject()
                .put("results", results)
                .put("autoOff", autoOff)
                .put("autoOn", autoOn)
        )
    }

    /**
     * One health probe. Returns a JSON object with repoId, providerName,
     * health ("ok"/"fail"), on + autoOff (post-probe bridge state, so the
     * UI can sync exactly), and change: "off" when an ON provider failed
     * and was auto-switched off, "on" when a previously auto-off provider
     * recovered, else "".
     */
    private suspend fun healthProbe(
        repoId: String,
        w: PluginWrapper,
    ): JSONObject {
        val name = w.plugin.name
        val wasOn = store.isProviderOn(repoId, name)
        val wasAutoOff = store.isProviderAutoOff(repoId, name)
        val api = resolveApi(w.plugin.name, w.plugin.internalName)
        var healthy = false
        if (api != null) {
            try {
                withTimeout(15_000) {
                    val feed = APIRepository(api).getMainPage(1)
                    val sections = (feed as? Resource.Success<List<HomePageResponse?>>)?.value
                    healthy = sections?.any { it?.items?.any { s -> s.list.isNotEmpty() } == true } == true
                }
            } catch (e: Exception) {
                healthy = false
            }
        }
        store.setProviderHealth(repoId, name, if (healthy) "ok" else "fail")
        var change = ""
        if (healthy && wasAutoOff) {
            store.setProviderOn(repoId, name, true)
            store.setProviderAutoOff(repoId, name, false)
            change = "on"
        } else if (!healthy && wasOn) {
            store.setProviderOn(repoId, name, false)
            store.setProviderAutoOff(repoId, name, true)
            change = "off"
        }
        return JSONObject()
            .put("repoId", repoId)
            .put("providerName", name)
            .put("health", if (healthy) "ok" else "fail")
            .put("on", store.isProviderOn(repoId, name))
            .put("autoOff", store.isProviderAutoOff(repoId, name))
            .put("change", change)
    }

    // ---- E9 / E10 / E11 ----

    private suspend fun homeFeed(providerName: String, page: Int): JSONObject {
        val api = resolveApi(providerName, null)
            ?: return err("provider_not_found", "Provider not found")
        val feed = try {
            APIRepository(api).getMainPage(page.coerceAtLeast(1))
        } catch (e: Exception) {
            EngineLogger.log("bridge", "homeFeed threw")
            return err("network_error", "Could not load the home feed")
        }
        val sections = (feed as? Resource.Success<List<HomePageResponse?>>)?.value
            ?: return err("network_error", "Could not load the home feed")
        val arr = JSONArray()
        var hasNext = false
        for (resp in sections) {
            if (resp == null) continue
            hasNext = hasNext || resp.hasNext
            for (list in resp.items) {
                val items = JSONArray()
                for (item in list.list) items.put(searchItemJson(item))
                arr.put(JSONObject().put("name", list.name).put("items", items))
            }
        }
        return ok(
            JSONObject()
                .put("provider", JSONObject().put("name", api.name).put("mainUrl", api.mainUrl).put("lang", api.lang))
                .put("sections", arr)
                .put("hasNext", hasNext)
        )
    }

    private suspend fun search(query: String, providerName: String?, page: Int): JSONObject {
        if (query.isBlank()) return err("invalid_input", "Enter a search term")
        if (providerName != null) {
            val api = resolveApi(providerName, null)
                ?: return err("provider_not_found", "Provider not found")
            val res = try {
                APIRepository(api).search(query, page.coerceAtLeast(1))
            } catch (e: Exception) {
                EngineLogger.log("bridge", "search threw")
                return err("network_error", "Search failed")
            }
            val list = (res as? Resource.Success<SearchResponseList>)?.value
                ?: return err("network_error", "Search failed")
            val arr = JSONArray()
            for (item in list.items) arr.put(searchItemJson(item))
            return ok(
                JSONObject()
                    .put("results", arr)
                    .put("hasNext", list.hasNext)
                    .put("partial", false)
            )
        }
        // All-provider fan-out over ON providers only, capped concurrency 8,
        // 15 s overall (design §5.3).
        val ordered = onProviderNamesOrdered()
        val results = JSONArray()
        val perProvider = JSONArray()
        var partial = false
        var hasNext = false
        try {
            withTimeout(15_000) {
                for (chunk in ordered.chunked(8)) {
                    val chunkOut = coroutineScope {
                        chunk.map { name ->
                            async {
                                val api = resolveApi(name, null) ?: return@async null
                                val res = try {
                                    APIRepository(api).search(query, page.coerceAtLeast(1))
                                } catch (e: Exception) {
                                    null
                                }
                                val list = (res as? Resource.Success<SearchResponseList>)?.value ?: return@async null
                                name to list
                            }
                        }.awaitAll()
                    }
                    for (entry in chunkOut) {
                        if (entry == null) {
                            partial = true
                            continue
                        }
                        val (name, list) = entry
                        hasNext = hasNext || list.hasNext
                        perProvider.put(JSONObject().put("provider", name).put("count", list.items.size))
                        for (item in list.items) results.put(searchItemJson(item))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            partial = true
            EngineLogger.log("bridge", "search fan-out timed out")
        }
        return ok(
            JSONObject()
                .put("results", results)
                .put("hasNext", hasNext)
                .put("perProvider", perProvider)
                .put("partial", partial)
        )
    }

    private suspend fun details(url: String, providerName: String): JSONObject {
        if (url.isBlank()) return err("invalid_input", "Missing details link")
        val api = resolveApi(providerName, null)
            ?: return err("provider_not_found", "Provider not found")
        val loaded = try {
            APIRepository(api).load(url)
        } catch (e: Exception) {
            EngineLogger.log("bridge", "details threw")
            return err("network_error", "Could not load details")
        }
        val resp = (loaded as? Resource.Success<LoadResponse>)?.value
            ?: return err("network_error", "Could not load details")
        return ok(detailsJson(resp))
    }

    // ---- E26 / E27 (settingsGet is inline in call(); settingsSet here) ----

    private fun settingsSet(params: JSONObject): JSONObject {
        val key = params.optString("key", "")
        if (!params.has("value")) {
            return err("invalid_input", "Missing value")
        }
        val value = params.opt("value")
        return when (val code = store.setSetting(key, value)) {
            null -> {
                if (key == "jsdelivrProxy" || key == "dns") {
                    EngineApp.syncFlagsFromStore()
                }
                ok(JSONObject().put("key", key).put("value", value))
            }
            "unknown_setting" -> err("unknown_setting", "Unknown setting: $key")
            "invalid_value" -> err("invalid_value", "Invalid value for $key")
            else -> err("unknown", "Could not save $key")
        }
    }

    // ---- E28 ----

    private suspend fun pluginUpdateCheck(manual: Boolean): JSONObject {
        val updated = JSONArray()
        for (repo in RepositoryManager.getRepositories()) {
            val id = repoId(repo.url)
            val wrappers = try {
                RepositoryManager.getRepoPlugins(repo)
            } catch (e: Exception) {
                null
            } ?: continue
            for (w in wrappers) {
                val p = w.plugin
                if (p.status == 0) { // PROVIDER_STATUS_DOWN: unload if loaded
                    val data = storedPluginData(repo, p.internalName)
                    if (data != null) {
                        try {
                            PluginManager.unloadPlugin(data.filePath)
                        } catch (e: Exception) {
                            EngineLogger.log("bridge", "pluginUpdateCheck unload threw")
                        }
                    }
                    continue
                }
                val stored = storedPluginData(repo, p.internalName) ?: continue
                if (p.version > stored.version) {
                    val okUpdate = try {
                        PluginManager.downloadPlugin(
                            activity, p.url, p.fileHash, p.internalName, repo.url, true
                        )
                    } catch (e: Exception) {
                        EngineLogger.log("bridge", "pluginUpdateCheck download threw")
                        false
                    }
                    if (okUpdate) {
                        updated.put(
                            JSONObject()
                                .put("repoId", id)
                                .put("providerName", p.name)
                                .put("fromVersion", stored.version)
                                .put("toVersion", p.version)
                        )
                    }
                }
            }
        }
        return ok(
            JSONObject()
                .put("updated", updated)
                .put("upToDate", updated.length() == 0)
        )
    }

    // ---- mapping helpers ----

    private fun searchItemJson(r: SearchResponse): JSONObject {
        val year = when (r) {
            is MovieSearchResponse -> r.year
            is TvSeriesSearchResponse -> r.year
            else -> null
        }
        return JSONObject()
            .put("id", "${r.apiName}|${r.url}")
            .put("title", r.name)
            .put("type", r.type?.name ?: JSONObject.NULL)
            .put("poster", r.posterUrl ?: JSONObject.NULL)
            .put("year", year ?: JSONObject.NULL)
            .put("rating", r.score?.toDouble(10) ?: JSONObject.NULL)
            .put("url", r.url)
            .put("apiName", r.apiName)
    }

    private fun episodeJson(e: Episode): JSONObject = JSONObject()
        .put("data", e.data)
        .put("name", e.name ?: JSONObject.NULL)
        .put("season", e.season ?: JSONObject.NULL)
        .put("episode", e.episode ?: JSONObject.NULL)
        .put("posterUrl", e.posterUrl ?: JSONObject.NULL)
        .put("score", e.score?.toDouble(10) ?: JSONObject.NULL)
        .put("description", e.description ?: JSONObject.NULL)
        .put("date", e.date ?: JSONObject.NULL)
        .put("runTime", e.runTime ?: JSONObject.NULL)

    private fun detailsJson(resp: LoadResponse): JSONObject {
        val episodes = JSONArray()
        var data: Any? = JSONObject.NULL
        when (resp) {
            is MovieLoadResponse -> data = resp.dataUrl
            is LiveStreamLoadResponse -> data = resp.dataUrl
            is TvSeriesLoadResponse -> {
                data = resp.url
                for (e in resp.episodes) episodes.put(episodeJson(e))
            }
            is AnimeLoadResponse -> {
                data = resp.url
                for (list in resp.episodes.values) {
                    for (e in list) episodes.put(episodeJson(e))
                }
            }
            else -> data = resp.url
        }
        val recs = JSONArray()
        for (r in resp.recommendations ?: emptyList()) recs.put(searchItemJson(r))
        val cast = JSONArray()
        for (a in resp.actors ?: emptyList()) cast.put(a.actor.name)
        return JSONObject()
            .put("title", resp.name)
            .put("type", resp.type.name)
            .put("poster", resp.posterUrl ?: JSONObject.NULL)
            .put("year", resp.year ?: JSONObject.NULL)
            .put("rating", resp.score?.toDouble(10) ?: JSONObject.NULL)
            .put("plot", resp.plot ?: JSONObject.NULL)
            .put("duration", resp.duration ?: JSONObject.NULL)
            .put("tags", JSONArray(resp.tags ?: emptyList<String>()))
            .put("cast", cast)
            .put("episodes", episodes)
            .put("recommendations", recs)
            .put("trailer", resp.trailers.firstOrNull()?.extractorUrl ?: JSONObject.NULL)
            .put("data", data ?: JSONObject.NULL)
            .put("apiName", resp.apiName)
    }

    /** The loadLinks input for a LoadResponse (movie / episode / live). */
    private fun loadDataOf(resp: LoadResponse): String? = when (resp) {
        is MovieLoadResponse -> resp.dataUrl
        is LiveStreamLoadResponse -> resp.dataUrl
        is TvSeriesLoadResponse -> resp.episodes.firstOrNull()?.data
        is AnimeLoadResponse -> resp.episodes.values.firstOrNull()?.firstOrNull()?.data
        else -> resp.url
    }

    // ---- lookup helpers ----

    private fun findRepo(repoId: String): RepositoryData? =
        RepositoryManager.getRepositories().firstOrNull { repoId(it.url) == repoId }

    private suspend fun findWrapper(repo: RepositoryData, providerName: String): PluginWrapper? {
        val wrappers = try {
            RepositoryManager.getRepoPlugins(repo)
        } catch (e: Exception) {
            null
        } ?: return null
        return wrappers.firstOrNull { it.plugin.name == providerName }
    }

    private fun storedPluginData(repo: RepositoryData, internalName: String): PluginData? {
        val folder = PluginManager.getPluginSanitizedFileName(repo.url)
        return PluginManager.getPluginsOnline().firstOrNull {
            it.internalName == internalName && it.filePath.contains("/$folder/")
        }
    }

    /** Resolve a provider's MainAPI by display name, falling back to the APIHolder map. */
    private fun resolveApi(providerName: String, internalName: String?): MainAPI? {
        val byName = APIHolder.allProviders.firstOrNull { it.name == providerName }
        if (byName != null) return byName
        return APIHolder.getApiFromNameNull(providerName)
    }

    /** The loaded Plugin instance for an api, if its .cs3 is loaded. */
    private fun loadedPluginFor(api: MainAPI): Plugin? {
        // registerMainAPI sets api.sourcePlugin to the plugin's .cs3 path,
        // which is exactly the key of PluginManager.plugins.
        val path = api.sourcePlugin ?: return null
        return PluginManager.plugins[path] as? Plugin
    }

    /** ON provider display names, in the stored providerOrder first, then the rest by name. */
    private fun onProviderNamesOrdered(): List<String> {
        val on = store.providerOnNames()
        val order = store.getSettingStringList("providerOrder", emptyList())
        val ordered = order.filter { it in on } + (on - order.toSet()).sorted()
        return ordered
    }

    companion object {
        fun repoId(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "repo-" + hex.take(16)
        }
    }

    private fun ok(data: JSONObject): JSONObject =
        JSONObject().put("ok", true).put("data", data)

    private fun err(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("code", code).put("message", message)
}
