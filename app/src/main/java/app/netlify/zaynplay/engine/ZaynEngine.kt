package app.netlify.zaynplay.engine

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase B: the real bridge dispatcher (engine-design.md §4.3).
 * Phase C adds E12–E25 + E29–E38 (player, downloads, library,
 * accounts, updater, backup, cache, logcat).
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
 *
 * Phase C notes (see VERIFY-0.2.0-phaseC.md for the full list):
 * - DataStoreHelper (841 lines, Compose-coupled) and DownloadManager
 *   (2,154 lines, R/Toast/MainActivity-coupled) are NOT vendored; the
 *   library/progress/download contracts are implemented in ZaynLibrary /
 *   ZaynDownloads on the same DataStore with the same upstream
 *   bookmark-id derivation.
 * - Audio per link is derived from the link NAME (language words) —
 *   ExtractorLink has no audio-language field (only audioTracks URLs);
 *   audio is null when the provider supplies no name hint.
 * - Speed is MEASURED (TTFB of a ranged HEAD per link, parallel, 3 s
 *   cap) — never invented. A link that cannot be measured ranks after
 *   measured ones.
 */
class ZaynEngine(
    private val activity: Activity,
    private val store: EngineStore,
    private val player: ZaynPlayer,
    private val downloads: ZaynDownloads,
    private val bridgeScope: CoroutineScope,
    private val onStageRect: (JSONObject) -> Unit,
) {
    private val library = ZaynLibrary(store)
    private val subtitles = ZaynSubtitles()
    private val accounts = ZaynAccounts(store)
    private val updater = ZaynUpdater(activity)
    private val subtitleCache = ConcurrentHashMap<String, String>()
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
                // ---- Phase C: player (E12–E16) ----
                "links" -> links(params)
                "play" -> play(params)
                "playerControl" -> playerControl(params)
                "playerState" -> ok(player.stateSnapshot())
                "externalPlay" -> externalPlay(params)
                "playerStage" -> playerStage(params)
                // ---- Phase C: subtitles (E17–E18) ----
                "subtitlesSearch" -> subtitlesSearch(params)
                "subtitlesLoad" -> subtitlesLoad(params.optString("id", ""))
                // ---- Phase C: library / progress (E19–E21) ----
                "library" -> ok(library.library())
                "librarySet" -> librarySet(params)
                "progressGet" -> ok(library.progressGet(params.optString("title", "")))
                "progressSet" -> ok(
                    library.progressSet(
                        params.optString("title", ""),
                        params.optInt("posSec", 0),
                        params.optInt("durSec", 0),
                    ),
                )
                "progressAll" -> ok(library.progressAll())
                // ---- Phase C: downloads (E22–E25) ----
                "downloads" -> ok(downloads.downloads())
                "downloadStart" -> downloadStart(params)
                "downloadControl" -> ok(
                    downloads.control(
                        params.optString("id", ""),
                        params.optString("action", ""),
                    ),
                )
                "downloadOpen" -> downloadOpen(params.optString("id", ""))
                // ---- Phase C: updater (E29–E30) ----
                "appUpdateCheck" -> ok(updater.checkUpdate())
                "appUpdateDownload" -> ok(updater.downloadUpdate(params.optString("url", "")))
                // ---- Phase C: accounts (E31–E34) ----
                "accountLoginUrl" -> accountLoginUrl(params.optString("provider", ""))
                "accountLoginComplete" -> ok(
                    accounts.loginComplete(
                        params.optString("provider", ""),
                        params.optString("redirectUrl", ""),
                        if (params.has("payload")) params.optString("payload") else null,
                    ),
                )
                "accountStatus" -> ok(accounts.status())
                "accountLogout" -> ok(accounts.logout(params.optString("provider", "")))
                // ---- Phase C: backup / cache / log (E35–E38) ----
                "backupExport" -> ok(backupExport())
                "backupImport" -> backupImport(params.optString("json", ""))
                "cacheClear" -> ok(cacheClear())
                "logcat" -> ok(
                    JSONObject().put(
                        "log",
                        EngineLogger.snapshot().takeLast(params.optInt("lines", 200)).joinToString("\n"),
                    ),
                )
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

    // ---- E12–E16: player ----

    /** Resolve the loadLinks input: explicit data/episodeData, else load(url) first. */
    private suspend fun resolveLinkData(params: JSONObject, api: MainAPI): String? {
        val direct = params.optString("episodeData", "").ifBlank { params.optString("data", "") }
        if (direct.isNotBlank()) return direct
        val url = params.optString("episodeUrl", "").ifBlank { params.optString("url", "") }
        if (url.isBlank()) return null
        val loaded = try {
            APIRepository(api).load(url)
        } catch (e: Exception) {
            null
        }
        val resp = (loaded as? Resource.Success<LoadResponse>)?.value ?: return null
        return loadDataOf(resp)
    }

    private data class ResolvedLinks(
        val api: MainAPI,
        val links: List<ExtractorLink>,
        val subtitles: List<SubtitleFile>,
        val partial: Boolean,
    )

    private suspend fun resolveLinks(params: JSONObject): ResolvedLinks? {
        val providerName = params.optString("providerName", "")
        val api = resolveApi(providerName, null) ?: return null
        val data = resolveLinkData(params, api) ?: return null
        val timeoutSec = params.optInt("timeoutSec", 10).coerceIn(1, 120)
        val foundLinks = mutableListOf<ExtractorLink>()
        val foundSubs = mutableListOf<SubtitleFile>()
        var partial = false
        try {
            withTimeout(timeoutSec * 1000L) {
                val okLinks = APIRepository(api).loadLinks(data, false, { s -> foundSubs += s }) { l -> foundLinks += l }
                if (!okLinks) partial = foundLinks.isEmpty()
            }
        } catch (e: TimeoutCancellationException) {
            partial = true
        }
        return ResolvedLinks(api, foundLinks, foundSubs, partial)
    }

    private fun qualityLabel(q: Int): String = when (q) {
        Qualities.P2160.value -> "4K"
        Qualities.P1440.value -> "2K"
        Qualities.Unknown.value, 0 -> ""
        else -> "${q}p"
    }

    /**
     * Audio language from the link NAME (language words). ExtractorLink
     * has no audio-language field (only audioTracks URLs, no language),
     * so this is null when the provider supplies no name hint — the
     * ranking then falls through to speed, honestly.
     */
    private fun audioFromName(name: String): String? {
        val n = " ${name.lowercase()} "
        if (n.contains("dual audio") || n.contains("multi audio") || n.contains("dual-audio")) return null
        val langs = listOf(
            "english" to "en", "hindi" to "hi", "bengali" to "bn", "bangla" to "bn",
            "tamil" to "ta", "telugu" to "te", "malayalam" to "ml", "marathi" to "mr",
            "gujarati" to "gu", "urdu" to "ur", "kannada" to "kn", "odia" to "or",
            "punjabi" to "pa", "japanese" to "ja", "korean" to "ko", "spanish" to "es",
            "french" to "fr", "german" to "de", "portuguese" to "pt", "russian" to "ru",
            "arabic" to "ar", "turkish" to "tr",
        )
        for ((word, code) in langs) if (n.contains(word)) return code
        return null
    }

    /** Settings store audio as display names (English / বাংলা); map to codes. */
    private fun audioCodeOf(name: String): String? {
        val code = when (name.trim()) {
            "English" -> "en"
            "Hindi", "हिन्दी" -> "hi"
            "Bengali", "বাংলা" -> "bn"
            "Telugu", "తెలుగు" -> "te"
            "Marathi", "मराठी" -> "mr"
            "Tamil", "தமிழ்" -> "ta"
            "Gujarati", "ગુજરાતી" -> "gu"
            "Urdu", "اردو" -> "ur"
            "Kannada", "ಕನ್ನಡ" -> "kn"
            "Odia", "ଓଡ଼ିଆ" -> "or"
            "Malayalam", "മലയാളം" -> "ml"
            "Punjabi", "ਪੰਜਾਬੀ" -> "pa"
            "Assamese" -> "as"
            "Maithili" -> "mai"
            "Nepali" -> "ne"
            "Sanskrit" -> "sa"
            "Kashmiri" -> "ks"
            "Konkani" -> "kok"
            "Dogri" -> "doi"
            "Bodo" -> "brx"
            "Santali" -> "sat"
            "Sindhi" -> "sd"
            "Manipuri" -> "mni"
            "none" -> null
            else -> null
        }
        if (code != null) return code
        val trimmed = name.trim()
        return if (trimmed.matches(Regex("[a-z]{2,3}(-[a-z]+)?"))) trimmed else null
    }

    private data class RankedLink(
        val link: ExtractorLink,
        val score: Int,
        val ttfbMs: Long?,
        val audio: String?,
    )

    /**
     * Smart-Streams ranking (design §4.6): quality match +100, audio1
     * +40, audio2 +20, then MEASURED TTFB (1-byte ranged request,
     * parallel, 3 s cap) ascending. Unmeasured links rank after measured
     * ones at the same score. Nothing is invented.
     */
    private suspend fun rankLinks(links: List<ExtractorLink>): List<RankedLink> {
        val resPref = store.getSettingString("res", "Auto")
        val a1 = audioCodeOf(store.getSettingString("audio1", "English"))
        val a2 = audioCodeOf(store.getSettingString("audio2", "বাংলা"))
        val playable = links.filter {
            it.type != ExtractorLinkType.TORRENT && it.type != ExtractorLinkType.MAGNET
        }
        val scored = playable.map { link ->
            var score = 0
            val ql = qualityLabel(link.quality)
            if (resPref != "Auto" && ql.isNotEmpty() && ql == resPref) score += 100
            val audio = audioFromName(link.name)
            if (audio != null && audio == a1) score += 40
            else if (audio != null && a2 != null && audio == a2) score += 20
            link to (score to audio)
        }
        // Measure TTFB for the top 8 by score only (keeps the call fast).
        val top = scored.sortedByDescending { it.second.first }.take(8).map { it.first }
        val ttfb = measureTtfb(top)
        return scored.map { (link, scoreAudio) ->
            RankedLink(link, scoreAudio.first, ttfb[link], scoreAudio.second)
        }.sortedWith(
            compareByDescending<RankedLink> { it.score }
                .thenBy { it.ttfbMs ?: Long.MAX_VALUE },
        )
    }

    private suspend fun measureTtfb(links: List<ExtractorLink>): Map<ExtractorLink, Long?> {
        return coroutineScope {
            links.map { link ->
                async {
                    val ms = try {
                        withTimeout(3000) {
                            val t0 = System.currentTimeMillis()
                            val req = Request.Builder().url(link.url)
                                .apply { link.getAllHeaders().forEach { (k, v) -> header(k, v) } }
                                .header("Range", "bytes=0-0").build()
                            com.lagradost.cloudstream3.app.baseClient.newCall(req).execute().use {
                                it.body?.close()
                                System.currentTimeMillis() - t0
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                    link to ms
                }
            }.awaitAll().toMap()
        }
    }

    private fun linkJson(r: RankedLink, index: Int): JSONObject {
        val l = r.link
        return JSONObject()
            .put("index", index)
            .put("source", l.source)
            .put("name", l.name)
            .put("url", l.url)
            .put("quality", l.quality)
            .put("qualityLabel", qualityLabel(l.quality))
            .put("type", l.type.name)
            .put("referer", l.referer)
            .put("headers", JSONObject(l.headers))
            .put("audio", r.audio ?: JSONObject.NULL)
            .put("ttfbMs", r.ttfbMs ?: JSONObject.NULL)
    }

    private suspend fun links(params: JSONObject): JSONObject {
        val resolved = resolveLinks(params) ?: return err("provider_not_found", "Provider not found")
        if (resolved.links.isEmpty()) return err("no_links", "No playable links found")
        val ranked = rankLinks(resolved.links)
        if (ranked.isEmpty()) return err("no_links", "No playable links found")
        val arr = JSONArray()
        for ((i, r) in ranked.withIndex()) arr.put(linkJson(r, i))
        val subs = JSONArray()
        for (s in resolved.subtitles) {
            subs.put(JSONObject().put("lang", s.lang).put("url", s.url).put("source", "provider"))
        }
        return ok(JSONObject().put("links", arr).put("subtitles", subs).put("partial", resolved.partial))
    }

    private suspend fun play(params: JSONObject): JSONObject {
        if (store.getSettingString("engine", "exoplayer") == "libmpv") {
            return err("playback_error", "libmpv is not available in this version")
        }
        val mode = params.optString("mode", store.getSettingString("mode", "auto"))
        val resolved = resolveLinks(params) ?: return err("provider_not_found", "Provider not found")
        if (resolved.links.isEmpty()) return err("no_links", "No playable links found")
        val ranked = rankLinks(resolved.links)
        if (ranked.isEmpty()) return err("no_links", "No playable links found")
        val linkIndex = if (params.has("linkIndex") && !params.isNull("linkIndex")) params.optInt("linkIndex", -1) else -1
        if (mode == "manual" && linkIndex < 0) {
            val arr = JSONArray()
            for ((i, r) in ranked.withIndex()) arr.put(linkJson(r, i))
            return ok(JSONObject().put("links", arr).put("manual", true))
        }
        val picked = if (linkIndex in ranked.indices) ranked[linkIndex] else ranked.first()
        val link = picked.link
        if (link is DrmExtractorLink) {
            return err("playback_error", "This stream uses DRM, which is not supported")
        }
        val title = params.optString("title", "")
        val reuse = store.getSettingBoolean("reuse", true)
        var reused = false
        if (reuse && title.isNotBlank()) {
            val last = store.mapEntry(EngineStore.KEY_LAST_LINK, title)
            if (last != null) {
                val at = last.optLong("atMs", 0)
                val durH = parseHours(store.getSettingString("reuseDur", "24h"))
                if (last.optInt("linkIndex", -1) == ranked.indexOf(picked) &&
                    System.currentTimeMillis() - at < durH * 3600_000L
                ) reused = true
            }
        }
        val startAtSec = when {
            params.has("startAtSec") -> params.optInt("startAtSec", 0)
            store.getSettingBoolean("resume", true) && title.isNotBlank() ->
                library.progressGet(title).optInt("posSec", 0)
            else -> 0
        }
        val subLangCode = audioCodeOf(store.getSettingString("subLang", "বাংলা")) ?: "bn"
        val providerSub = resolved.subtitles.firstOrNull { it.langTag == subLangCode || it.lang == subLangCode }
            ?: resolved.subtitles.firstOrNull()
        val subtitleText = if (params.has("subtitleText") && !params.isNull("subtitleText")) params.optString("subtitleText") else null
        val subtitleLang = if (params.has("subtitleLang")) params.optString("subtitleLang") else subLangCode
        val playLink = ZaynPlayer.PlayLink(
            url = link.url,
            source = link.source,
            qualityLabel = qualityLabel(link.quality),
            audio = picked.audio,
            referer = link.referer,
            headers = link.getAllHeaders(),
            type = link.type,
        )
        player.play(playLink, startAtSec * 1000L, subtitleText, subtitleLang, providerSub?.url, cacheSizeBytes())
        if (title.isNotBlank()) {
            store.putMapEntry(
                EngineStore.KEY_LAST_LINK,
                title,
                JSONObject().put("linkIndex", ranked.indexOf(picked)).put("atMs", System.currentTimeMillis()),
            )
            val pageUrl = params.optString("episodeUrl", "").ifBlank { params.optString("url", "") }
            library.markWatching(
                pageUrl,
                params.optString("providerName", ""),
                title,
                params.optString("type", "movie"),
                params.optString("poster", ""),
            )
        }
        // extractorVerifierJob while playing, where the provider implements it.
        val extractorData = link.extractorData
        if (extractorData != null) {
            bridgeScope.launch {
                try {
                    APIRepository(resolved.api).extractorVerifierJob(extractorData)
                } catch (e: Exception) {
                    EngineLogger.log("player", "verifier job ended")
                }
            }
        }
        return ok(
            JSONObject()
                .put("playing", true)
                .put(
                    "link",
                    JSONObject()
                        .put("source", link.source)
                        .put("qualityLabel", qualityLabel(link.quality))
                        .put("audio", picked.audio ?: JSONObject.NULL),
                )
                .put("reused", reused),
        )
    }

    private fun parseHours(raw: String): Long =
        Regex("""(\d+)""").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 24L

    private fun cacheSizeBytes(): Long {
        val raw = store.getSettingString("bufDisk", "250 MB")
        val num = Regex("""(\d+)""").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 250L
        return num.coerceIn(16, 2048) * 1024L * 1024L
    }

    private suspend fun playerControl(params: JSONObject): JSONObject {
        val action = params.optString("action", "")
        return when (action) {
            "toggle", "pause", "resume", "seek", "rate", "volume", "mute" -> {
                val value = if (params.has("value") && !params.isNull("value")) params.optDouble("value") else null
                ok(JSONObject().put("state", player.control(action, value)))
            }
            "setSubtitle" -> {
                val text = if (params.has("text") && !params.isNull("text")) params.optString("text") else null
                player.setSubtitleText(text, params.optString("lang", ""))
                ok(JSONObject().put("state", player.stateSnapshot()))
            }
            "nextEp", "prevEp" -> {
                // Episode switch is driven by play with the episode's data.
                val epData = params.optString("episodeData", "")
                if (epData.isBlank()) return err("invalid_input", "No next episode")
                val playParams = JSONObject(params.toString())
                playParams.put("data", epData)
                play(playParams)
            }
            else -> err("invalid_input", "Unknown player action")
        }
    }

    private suspend fun externalPlay(params: JSONObject): JSONObject {
        val appName = params.optString("app", store.getSettingString("extApp", "VLC"))
        val pkg = when (appName) {
            "MX Player" -> "com.mxtech.videoplayer.ad"
            "JustPlayer" -> "com.brouken.player"
            else -> "org.videolan.vlc"
        }
        val installed = try {
            activity.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return err("app_not_installed", "$appName is not installed")
        val resolved = resolveLinks(params) ?: return err("provider_not_found", "Provider not found")
        val ranked = rankLinks(resolved.links)
        val picked = ranked.firstOrNull() ?: return err("no_links", "No playable links found")
        if (picked.link is DrmExtractorLink) {
            return err("playback_error", "This stream uses DRM, which is not supported")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(picked.link.url), "video/*")
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            ok(JSONObject().put("launched", true))
        } catch (e: Exception) {
            err("app_not_installed", "$appName could not be opened")
        }
    }

    private fun playerStage(params: JSONObject): JSONObject {
        onStageRect(params)
        return ok(JSONObject().put("received", true))
    }

    // ---- E17–E18: subtitles ----

    private suspend fun subtitlesSearch(params: JSONObject): JSONObject {
        val title = params.optString("title", "")
        if (title.isBlank()) return err("invalid_input", "Missing title")
        val lang = params.optString("lang", "en").ifBlank { "en" }
        val year = if (params.has("year") && !params.isNull("year")) params.optInt("year", 0).takeIf { it > 0 } else null
        val rows = try {
            subtitles.searchAddic7ed(title, year, lang)
        } catch (e: Exception) {
            EngineLogger.log("subs", "search failed")
            return err("network_error", "Subtitle search failed")
        }
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            subtitleCache[row.optString("id")] = row.optString("url")
        }
        return ok(JSONObject().put("subtitles", rows))
    }

    private suspend fun subtitlesLoad(id: String): JSONObject {
        if (id.startsWith("opensubtitles|") || id.startsWith("subdl|")) {
            return err("auth_failed", "Subtitle download needs an account login first")
        }
        val url = subtitleCache[id] ?: id.substringAfter("|", "")
        if (url.isBlank() || !url.startsWith("http")) return err("invalid_input", "Unknown subtitle")
        val text = try {
            subtitles.loadAddic7ed(url)
        } catch (e: Exception) {
            null
        } ?: return err("network_error", "Could not download that subtitle")
        return ok(JSONObject().put("text", text))
    }

    // ---- E19–E21: library / progress ----

    private fun librarySet(params: JSONObject): JSONObject {
        return ok(
            library.librarySet(
                params.optString("url", ""),
                params.optString("providerName", ""),
                params.optString("status", ""),
                params.optString("title", ""),
                params.optString("type", "movie"),
                params.optString("poster", ""),
            ),
        )
    }

    // ---- E22–E25: downloads ----

    private suspend fun downloadStart(params: JSONObject): JSONObject {
        val title = params.optString("title", "")
        val url = params.optString("url", "")
        if (title.isBlank() || url.isBlank()) return err("invalid_input", "Missing title or link")
        val resolved = resolveLinks(params) ?: return err("provider_not_found", "Provider not found")
        val ranked = rankLinks(resolved.links)
        if (ranked.isEmpty()) return err("no_links", "No downloadable links found")
        val wantQ = store.getSettingString("dlQuality", "Auto")
        val picked = if (wantQ != "Auto") {
            ranked.firstOrNull { qualityLabel(it.link.quality) == wantQ } ?: ranked.first()
        } else {
            ranked.first()
        }
        val link = picked.link
        if (link is DrmExtractorLink) {
            return err("playback_error", "This stream uses DRM, which is not supported")
        }
        if (link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.MAGNET) {
            return err("no_links", "Torrent downloads are not supported")
        }
        val id = "dl-" + (title + "|" + url).hashCode().toString().replace("-", "n")
        val started = downloads.start(
            id,
            link,
            title,
            params.optString("type", "movie"),
            params.optString("poster", ""),
            params.optString("providerName", ""),
            params.optString("episodeUrl", "").ifBlank { null },
        )
        if (started == null) {
            return err("download_failed", "Wi-Fi only downloads are on — connect to Wi-Fi to download")
        }
        return ok(JSONObject().put("id", started).put("queued", true))
    }

    private fun downloadOpen(id: String): JSONObject {
        val path = downloads.localPath(id) ?: return err("download_failed", "Download not found")
        val isM3u8 = path.endsWith(".m3u8")
        val link = ZaynPlayer.PlayLink(
            url = "file://$path",
            source = "Downloads",
            qualityLabel = "",
            audio = null,
            referer = "",
            headers = emptyMap(),
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            isLocalFile = true,
        )
        player.play(link, 0, null, null, null, cacheSizeBytes())
        return ok(JSONObject().put("launched", true))
    }

    // ---- E31–E34: accounts ----

    private fun accountLoginUrl(provider: String): JSONObject {
        if (provider !in ZaynAccounts.PROVIDERS) return err("invalid_input", "Unknown account")
        // Key-gated: no client key exists in the repo for any provider.
        EngineLogger.log("accounts", "loginUrl $provider gated (no key)")
        return err("auth_failed", "Login is not available yet")
    }

    // ---- E35–E38: backup / cache / log ----

    private fun backupExport(): JSONObject {
        val settings = store.settingsSnapshot()
        val repos = JSONArray()
        for (repo in RepositoryManager.getRepositories()) {
            repos.put(JSONObject().put("name", repo.name).put("url", repo.url))
        }
        val json = JSONObject()
            .put("format", "zaynplay-backup-0.2.0")
            .put("app", "Zayn Play")
            .put("version", "0.2.0")
            .put("settings", settings)
            .put("repos", repos)
            .put("provOn", store.readJsonMap("zayn/provOn"))
            .put("provAutoOff", store.readJsonMap("zayn/provAutoOff"))
            .put("provHealth", store.readJsonMap("zayn/provHealth"))
            .put("library", store.readJsonMap(EngineStore.KEY_LIBRARY))
            .put("progress", store.readJsonMap(EngineStore.KEY_PROGRESS))
            .put("downloads", store.readJsonMap(EngineStore.KEY_DOWNLOADS))
            .put("savedAt", System.currentTimeMillis())
        // NOTE: account tokens are deliberately NOT exported — a backup
        // file can be shared, and tokens are secrets.
        return JSONObject().put("json", json.toString(2)).put("fileName", "zaynplay-backup-0.2.0.json")
    }

    private suspend fun backupImport(jsonText: String): JSONObject {
        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return err("invalid_backup", "That file is not a Zayn Play backup")
        }
        val format = json.optString("format", "")
        if (format != "zaynplay-backup-0.2.0" && !json.has("installed")) {
            return err("invalid_backup", "That file is not a Zayn Play backup")
        }
        // Validate first: nothing is applied until the whole file parses.
        val settingsObj = if (json.has("settings")) json.optJSONObject("settings") else null
        val provOn = if (json.has("provOn")) json.optJSONObject("provOn") else null
        val provAutoOff = if (json.has("provAutoOff")) json.optJSONObject("provAutoOff") else null
        val libraryObj = if (json.has("library")) json.optJSONObject("library") else null
        val progressObj = if (json.has("progress")) json.optJSONObject("progress") else null
        val reposArr = if (json.has("repos")) json.optJSONArray("repos") else null
        var restoredRepos = 0
        if (reposArr != null) {
            for (i in 0 until reposArr.length()) {
                val r = reposArr.optJSONObject(i) ?: continue
                val url = r.optString("url", "")
                if (url.isBlank()) continue
                if (RepositoryManager.getRepositories().any { it.url == url }) {
                    restoredRepos++
                    continue
                }
                val added = repoAdd(url)
                if (added.optBoolean("ok", false)) restoredRepos++
            }
        }
        // Legacy 0.1.7 format: {installed, provOn, pb, theme, lang}.
        if (format != "zaynplay-backup-0.2.0") {
            val legacyProvOn = json.optJSONObject("provOn")
            if (legacyProvOn != null) store.writeJsonMap("zayn/provOn", legacyProvOn)
            return ok(JSONObject().put("restored", true).put("reposInstalled", restoredRepos))
        }
        if (settingsObj != null) {
            val keys = settingsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                store.setSetting(k, settingsObj.opt(k))
            }
        }
        if (provOn != null) store.writeJsonMap("zayn/provOn", provOn)
        if (provAutoOff != null) store.writeJsonMap("zayn/provAutoOff", provAutoOff)
        if (libraryObj != null) store.writeJsonMap(EngineStore.KEY_LIBRARY, libraryObj)
        if (progressObj != null) store.writeJsonMap(EngineStore.KEY_PROGRESS, progressObj)
        return ok(JSONObject().put("restored", true).put("reposInstalled", restoredRepos))
    }

    private suspend fun cacheClear(): JSONObject {
        fun dirSize(f: File): Long {
            if (!f.exists()) return 0
            if (f.isFile) return f.length()
            return f.listFiles()?.sumOf { dirSize(it) } ?: 0
        }
        fun deleteContents(f: File) {
            f.listFiles()?.forEach {
                try {
                    if (it.isDirectory) it.deleteRecursively() else it.delete()
                } catch (e: Exception) {
                }
            }
        }
        val cacheDir = activity.cacheDir
        val extCache = activity.externalCacheDir
        val before = dirSize(cacheDir) + (extCache?.let { dirSize(it) } ?: 0)
        // Release the player first (its SimpleCache lives in cacheDir).
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            player.clearCache { cont.resume(Unit) }
        }
        deleteContents(cacheDir)
        if (extCache != null) deleteContents(extCache)
        val after = dirSize(cacheDir) + (extCache?.let { dirSize(it) } ?: 0)
        val freedMB = (before - after).coerceAtLeast(0) / 1048576.0
        EngineLogger.log("cache", "cleared ${"%.1f".format(freedMB)} MB")
        return JSONObject().put("freedMB", freedMB)
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
