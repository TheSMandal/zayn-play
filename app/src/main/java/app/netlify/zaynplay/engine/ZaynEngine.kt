package app.netlify.zaynplay.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
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
import com.lagradost.cloudstream3.plugins.RepositoryManager.RepoLoadError
import com.lagradost.cloudstream3.plugins.RepositoryManager.RepoPluginsResult
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
 * unknown_method (player / downloads / accounts / updater = Phase C).
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
                    EngineLogger.log("bridge", "$method -> unknown_method")
                    err("unknown_method", "Unknown method: $method")
                }
            }
        } catch (e: CancellationException) {
            // (v0.2.1-i) A cancelled bridge call must stay cancelled: the
            // old catch-all swallowed it and replied "unknown" instead.
            throw e
        } catch (e: Exception) {
            EngineLogger.log("bridge", "$method threw ${e.javaClass.simpleName}")
            err("unknown", "Engine error")
        }
    }

    // ---- E1 ----

    private fun engineStatus(): JSONObject = JSONObject()
        .put("version", "0.2.4")
        .put("engineReady", engineReady)
        .put("pluginsLoaded", PluginManager.plugins.size)
        .put("reposInstalled", RepositoryManager.getRepositories().size)
        .put("providersOn", store.providerOnCount())

    // ---- E2 / E3 / E4 ----

    private fun repos(): JSONObject {
        val arr = JSONArray()
        for (repo in RepositoryManager.getRepositories()) {
            val id = repoId(repo.url)
            // (v0.2.1-h) providerCount is LIVE providers (registered
            // MainAPIs from this repo), not downloaded .cs3 files.
            val folder = PluginManager.getPluginSanitizedFileName(repo.url)
            val liveProviders = APIHolder.allProviders.count {
                it.sourcePlugin?.contains("/$folder/") == true
            }
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("catalogueId", catalogueIdOf(repo.url) ?: JSONObject.NULL)
                    .put("name", repo.name)
                    .put("url", repo.url)
                    .put("iconUrl", repo.iconUrl ?: JSONObject.NULL)
                    .put("description", repo.description ?: JSONObject.NULL)
                    .put("installed", true)
                    .put("providerCount", liveProviders)
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
        return JSONObject()
            .put("repo", repoJson(repo))
            .put("providers", providerEntries(repo, providers))
    }

    private fun repoJson(repo: RepositoryData): JSONObject = JSONObject()
        .put("id", repoId(repo.url))
        .put("catalogueId", catalogueIdOf(repo.url) ?: JSONObject.NULL)
        .put("name", repo.name)
        .put("url", repo.url)
        .put("iconUrl", repo.iconUrl ?: JSONObject.NULL)
        .put("description", repo.description ?: JSONObject.NULL)
        .put("installed", true)

    private suspend fun repoRemove(repoId: String): JSONObject {
        val repo = findRepo(repoId) ?: return err("repo_not_found", "Repository not found")
        // State keys use the canonical hash id, not whichever id form the
        // caller passed (catalogue id / hex suffix would miss otherwise).
        val canonicalId = repoId(repo.url)
        try {
            RepositoryManager.removeRepository(activity, repo)
        } catch (e: Exception) {
            EngineLogger.log("bridge", "repoRemove threw")
            return err("unknown", "Could not remove that repository")
        }
        store.clearRepoState(canonicalId)
        EngineLogger.log("bridge", "repoRemove removed ${repo.name}")
        return ok(JSONObject().put("repoId", canonicalId))
    }

    // ---- E5 / E6 ----

    private suspend fun repoProviders(repoId: String): JSONObject {
        val repo = findRepo(repoId) ?: return err("repo_not_found", "Repository not found")
        // (v0.2.1-o) A failed manifest fetch is no longer flattened to
        // "network_error": network / HTTP / parse failures get distinct
        // codes, so the UI never silently shows an empty provider list.
        return when (val result = RepositoryManager.getRepoPluginsResult(repo)) {
            is RepoPluginsResult.Ok -> ok(
                JSONObject()
                    .put("repoId", repoId)
                    .put("catalogueId", catalogueIdOf(repo.url) ?: JSONObject.NULL)
                    .put("providers", providerEntries(repo, result.wrappers))
            )
            is RepoPluginsResult.Err -> when (result.kind) {
                RepoLoadError.NETWORK -> err("network_error", "Could not reach the repository")
                RepoLoadError.HTTP -> err("http_error", "The repository returned an error")
                RepoLoadError.PARSE -> err("repo_parse_error", "Could not read that repository")
            }
        }
    }

    /**
     * (v0.2.1-2) MainAPI-based provider rows. One row per registered
     * MainAPI when the plugin is loaded (name = MainAPI.name,
     * pluginName/internalName = its SitePlugin); exactly ONE row with
     * name = SitePlugin.name and notLoaded=true when it is not loaded.
     * ON/OFF/health state stays keyed by (repoId, row name).
     */
    private fun providerEntries(repo: RepositoryData, wrappers: List<PluginWrapper>): JSONArray {
        val arr = JSONArray()
        // (v0.2.9) Fix G: enforce the provLang setting on ROWS only (see
        // provLangCodes/langRowWanted). Loading, health and providerTest
        // are never gated by language.
        val wantedCodes = provLangCodes()
        for (w in wrappers) {
            val p = w.plugin
            val apis = apisForPlugin(repo, p)
            if (apis.isEmpty()) {
                if (langRowWanted(p.language, wantedCodes)) {
                    arr.put(providerRowJson(repo, p, name = p.name, api = null, notLoaded = true))
                }
            } else {
                for (api in apis) {
                    if (langRowWanted(api.lang, wantedCodes)) {
                        arr.put(providerRowJson(repo, p, name = api.name, api = api, notLoaded = false))
                    }
                }
            }
        }
        return arr
    }

    /**
     * (v0.2.9) Fix G: the provLang setting (full language names, e.g.
     * "English") was never enforced — every provider row was shown. Maps
     * the names to BCP 47 codes with the same table as the UI's LCODE.
     * Returns null when the list is empty, which is the UI's ALL state:
     * no filtering. A row with no language metadata is always shown.
     */
    private fun provLangCodes(): Set<String>? {
        val names = store.getSettingStringList("provLang", listOf("English", "Bengali", "Hindi"))
        if (names.isEmpty()) return null
        return names.map { PROV_LANG_CODES[it] ?: it.trim().lowercase() }.toSet()
    }

    private fun langRowWanted(lang: String?, wantedCodes: Set<String>?): Boolean {
        if (wantedCodes == null) return true
        if (lang.isNullOrBlank()) return true
        val primary = lang.trim().split('-', '_').first().lowercase()
        return primary in wantedCodes
    }

    private fun providerRowJson(
        repo: RepositoryData,
        p: SitePlugin,
        name: String,
        api: MainAPI?,
        notLoaded: Boolean,
    ): JSONObject {
        val id = repoId(repo.url)
        val loadedPlugin = api?.let { loadedPluginFor(it) }
        return JSONObject()
            .put("name", name)
            .put("internalName", p.internalName)
            .put("pluginName", p.name)
            .put("lang", api?.lang ?: p.language ?: JSONObject.NULL)
            .put("version", p.version)
            .put("status", p.status)
            .put("on", store.isProviderOn(id, name))
            .put("autoOff", store.isProviderAutoOff(id, name))
            .put("health", store.providerHealth(id, name) ?: JSONObject.NULL)
            .put("hasSettings", (loadedPlugin as? Plugin)?.openSettings != null)
            .put("mainUrl", api?.mainUrl ?: JSONObject.NULL)
            .put("types", JSONArray(p.tvTypes ?: emptyList<String>()))
            .put("notLoaded", notLoaded)
    }

    /** Loaded MainAPIs registered by one SitePlugin (via its stored .cs3). */
    private fun apisForPlugin(repo: RepositoryData, p: SitePlugin): List<MainAPI> {
        val data = storedPluginData(repo, p.internalName) ?: return emptyList()
        return APIHolder.allProviders.filter { it.sourcePlugin == data.filePath }
    }

    private suspend fun providerSetOn(repoId: String, providerName: String, on: Boolean): JSONObject {
        val repo = findRepo(repoId) ?: return err("repo_not_found", "Repository not found")
        val wrapper = findWrapper(repo, providerName)
            ?: return err("provider_not_found", "Provider not found")
        val p = wrapper.plugin
        if (on) {
            // (v0.2.1-5) Upstream treats status 0 (Down) as disabled: never
            // download a Down plugin.
            if (p.status == PROVIDER_STATUS_DOWN) {
                return err("provider_down", "That provider is currently down")
            }
            // (v0.2.4) Typed code, threaded from the download call
            // itself (downloadPluginDetailed) — no process-wide slot
            // read after the fact.
            // (v0.2.6) Triple now: the detail names the missing class
            // for load_class_missing; p.fileSize is checked too.
            val (loaded, failureCode, failureDetail) = try {
                PluginManager.downloadPluginDetailed(
                    activity, p.url, p.fileHash, p.internalName, repo.url, true, p.fileSize
                )
            } catch (e: CancellationException) {
                // (v0.2.4) Never launder a cancellation into a failure:
                // a withTimeout kill must propagate as
                // TimeoutCancellationException, not "download_failed".
                throw e
            } catch (e: Exception) {
                EngineLogger.log("bridge", "providerSetOn download threw")
                Triple(false, "download_failed", null)
            }
            if (!loaded) {
                // (v0.2.2) A download that reports false may still have
                // registered the API in-memory (e.g. the load succeeded
                // but a later step failed) — resolve before giving up.
                // And report the REAL failure code, not "network_error":
                // download_failed | timeout | network_error | http_<code>
                // | download_empty | hash_mismatch | download_io |
                // dex_writable | load_class_missing | load_no_manifest
                // | load_failed.
                val alreadyLoaded = resolveApi(providerName, p.internalName, repoId)
                    ?: apisForPlugin(repo, p).firstOrNull { it.name == providerName }
                if (alreadyLoaded == null) {
                    val code = failureCode ?: "network_error"
                    EngineLogger.log("bridge", "providerSetOn $providerName failed: $code detail=$failureDetail")
                    // (v0.2.6) Name WHAT is missing in the toast: for
                    // load_class_missing the detail is the missing
                    // class (e.g. com.lagradost.cloudstream3.…);
                    // class names are language-neutral, shown verbatim.
                    val detailText = failureDetail?.let {
                        if (code == "load_class_missing") " — missing class: $it" else " — $it"
                    } ?: ""
                    return err(code, providerFailureMessage(code, providerName) + detailText)
                }
            }
            store.setProviderOn(repoId, providerName, true)
            store.setProviderAutoOff(repoId, providerName, false)
            EngineLogger.log("bridge", "providerSetOn ON $providerName")
        } else {
            // One .cs3 can host several MainAPIs (NetMirror: 4). Unloading
            // it for one API would kill its siblings, so unload only when
            // no sibling API of the same plugin is still ON.
            val siblingsOn = apisForPlugin(repo, p)
                .any { it.name != providerName && store.isProviderOn(repoId, it.name) }
            if (!siblingsOn) {
                val data = storedPluginData(repo, p.internalName)
                if (data != null) {
                    try {
                        PluginManager.unloadPlugin(data.filePath)
                    } catch (e: Exception) {
                        EngineLogger.log("bridge", "providerSetOn unload threw")
                    }
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

    /**
     * (v0.2.2) Human-readable message for a provider download/load
     * failure code (shown as a toast). The code itself is the stable
     * machine value; the message says what it means.
     */
    private fun providerFailureMessage(code: String, providerName: String): String = when {
        code.startsWith("http_") -> "$providerName: server error (${code.removePrefix("http_")}) — try again"
        code == "download_failed" -> "$providerName: could not download — check the connection and try again"
        // (v0.2.4) These two used to fall through to the bare
        // "Could not download X" else-branch: a slow download killed
        // by a timeout, and a provider the content check could not
        // reach at all, both deserved their own message.
        code == "timeout" -> "$providerName: download timed out on a slow connection — try again"
        code == "network_error" -> "$providerName: could not reach the provider — check the connection and try again"
        code == "download_empty" -> "$providerName: server sent an empty file — try again"
        code == "hash_mismatch" -> "$providerName: download failed its integrity check — try again"
        code == "download_io" -> "$providerName: could not save the file — check storage and try again"
        code == "dex_writable" -> "$providerName: phone refused to load the file (writable dex) — try again"
        code == "load_class_missing" -> "$providerName: file is missing its provider class — the repo file is broken"
        code == "download_incomplete" -> "$providerName: download stopped short of the full file — try again"
        // (v0.2.4) New code: the .cs3 has no manifest.json.
        code == "load_no_manifest" -> "$providerName: file is missing its manifest — the repo file is broken"
        // (v0.2.5) New codes from the direct ZipFile manifest read: the
        // downloaded file is not a zip at all, the plugin file is gone
        // from disk, or its manifest JSON does not parse.
        code == "bad_zip" -> "$providerName: downloaded file is damaged — try again"
        code == "file_missing" -> "$providerName: plugin file is missing — try again"
        code == "bad_manifest" -> "$providerName: file info is unreadable — the repo file is broken"
        code == "load_failed" -> "$providerName: could not load — try again"
        else -> "Could not download $providerName"
    }

    /** (v0.2.2) True when the device has an active network. A health
     * probe or provider test that runs while offline must never be
     * reported as "the provider is broken". */
    private fun isOnline(): Boolean {
        return try {
            val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            // INTERNET only — NOT VALIDATED: on a captive-portal Wi-Fi
            // (this app's own use case) validation can lag while the
            // network already works; a real failure is still reported
            // honestly by the download itself.
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * (v0.2.10) Row reason detail, truncated to the first line
     * (exception + top frame). The detail comes from
     * Resource.Failure.errorString via getStackTracePretty() — the
     * message plus one "File.kt NNN" line per frame — and the row
     * renders it inline after " — ", so the full trace overflowed the
     * row and covered other rows (v0.2.9 screenshots). The FULL trace
     * stays in the EngineLogger (providerTest / firstContentItem log
     * it), so forensics lose nothing — only the row display is
     * shortened. The one exception to "first line only": when the
     * second line is a top frame ("Anichi.kt 171"), keep it — that
     * frame names WHERE it failed, which is the useful half.
     */
    private fun truncateReasonDetail(detail: String?): String? {
        if (detail == null) return null
        val lines = detail.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val first = lines[0]
        if (lines.size < 2) return first
        val second = lines[1]
        return if (second.matches(Regex("^[A-Za-z0-9_.\$]+\\.kt \\d+$"))) {
            "$first; $second"
        } else {
            first
        }
    }

    private suspend fun providerTest(repoId: String, providerName: String): JSONObject {
        val repo = findRepo(repoId) ?: return err("repo_not_found", "Repository not found")
        val wrapper = findWrapper(repo, providerName)
            ?: return err("provider_not_found", "Provider not found")
        val p = wrapper.plugin
        // (v0.2.1-5) Never download a Down plugin; report it honestly
        // (extra "down" field, same ok shape — no download attempted).
        if (p.status == PROVIDER_STATUS_DOWN) {
            return ok(
                JSONObject()
                    .put("repoId", repoId)
                    .put("providerName", providerName)
                    .put("load", false)
                    .put("content", false)
                    .put("video", false)
                    .put("down", true)
            )
        }
        // (v0.2.2) Offline is reported as offline — never as three red
        // crosses pretending the provider is broken.
        if (!isOnline()) {
            return ok(
                JSONObject()
                    .put("repoId", repoId)
                    .put("providerName", providerName)
                    .put("load", false)
                    .put("content", false)
                    .put("video", false)
                    .put("error", "offline")
            )
        }
        var load = false
        var content = false
        var video = false
        var error: String? = null
        // (v0.2.6) Forensic detail threaded from the download+load
        // call (missing class name for load_class_missing); null
        // unless the load step failed. Surfaced verbatim in the row.
        var loadDetail: String? = null
        // (v0.2.9) Fix C: detail from the CONTENT/VIDEO stage (the
        // Failure errorString / loadLinks cause). The row shows
        // (detail ?: loadDetail); the two stages never both fail.
        var detail: String? = null
        // (v0.2.4) The download runs OUTSIDE the 45 s box: on a slow
        // network the .cs3 download gets its full 120 s HTTP budget
        // (with resume + retry in downloadPluginToFile), and only the
        // content/video checks are bounded by withTimeout(45_000).
        // The JS side waits 180 s for providerTest (120 s download +
        // 45 s content box + margin). A timeout kill inside the box
        // still lands on the TimeoutCancellationException catch below
        // as an honest error = "timeout".
        var api = resolveApi(providerName, p.internalName, repoId)
        if (api == null) {
            val (downloaded, downloadCode, downloadDetail) = try {
                PluginManager.downloadPluginDetailed(
                    activity, p.url, p.fileHash, p.internalName, repo.url, true, p.fileSize
                )
            } catch (e: CancellationException) {
                // (v0.2.4) A cancellation of the download is not a
                // provider failure: propagate, never relabel.
                throw e
            } catch (e: Exception) {
                EngineLogger.log("bridge", "providerTest download threw")
                Triple(false, "download_failed", null)
            }
            loadDetail = downloadDetail
            // (v0.2.2) Resolve even when the download reports
            // false: the API may already be registered in-memory.
            api = resolveApi(providerName, p.internalName, repoId)
                ?: apisForPlugin(repo, p).firstOrNull { it.name == providerName }
                ?: apisForPlugin(repo, p).firstOrNull()
            // (v0.2.3) Never return load:false with a null error:
            // a download that reports true but still leaves no
            // API registered is a load failure, not a silent one.
            // `downloaded` is kept for the log line only.
            // (v0.2.4) The code is threaded from the download call.
            if (api == null) {
                error = downloadCode ?: "load_failed"
                EngineLogger.log("bridge", "providerTest $providerName failed: $error (downloaded=$downloaded)")
            }
        }
        val apiForCheck = api
        if (apiForCheck != null) {
            try {
                withTimeout(45_000) {
                    load = true
                    error = null
                    detail = null
                    // (v0.2.9) Fix C: every false outcome below now carries
                    // a non-null error code AND a detail string. Before,
                    // content/video could be false with error == null (a
                    // site answering with no usable content, a page with no
                    // links, a loadLinks exception swallowed to `false`),
                    // which the row rendered as "unknown reason".
                    val (firstItem, transportFailed, contentDetail) = firstContentItem(apiForCheck)
                    content = firstItem != null
                    if (transportFailed) {
                        error = "network_error"
                        detail = contentDetail
                    } else if (firstItem == null) {
                        error = "no_content"
                        detail = contentDetail
                    }
                    if (firstItem != null) {
                        val loaded = APIRepository(apiForCheck).load(firstItem.url)
                        if (loaded is Resource.Failure) {
                            error = if (loaded.isNetworkError) "network_error" else "load_failed"
                            detail = loaded.errorString
                        }
                        val data = (loaded as? Resource.Success)?.value?.let(::loadDataOf)
                        if (data != null) {
                            var gotLink = false
                            val links = APIRepository(apiForCheck).loadLinks(data, false, { _: SubtitleFile -> }) { _: ExtractorLink -> gotLink = true }
                            video = links.ok && gotLink
                            if (!video) {
                                if (error == null) error = "no_links"
                                if (detail == null) detail = links.error
                            }
                        } else if (loaded !is Resource.Failure && error == null) {
                            // Page loaded but carried no playable data.
                            error = "no_links"
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                EngineLogger.log("bridge", "providerTest timeout $providerName")
                error = "timeout"
            } catch (e: Exception) {
                EngineLogger.log("bridge", "providerTest threw")
                error = "network_error"
            }
        }
        return ok(
            JSONObject()
                .put("repoId", repoId)
                .put("providerName", providerName)
                .put("load", load)
                .put("content", content)
                .put("video", video)
                .put("error", error ?: JSONObject.NULL)
                // (v0.2.6) Forensic detail, verbatim (a class name for
                // load_class_missing); the row appends it after the
                // translated reason so the screenshot names WHAT is
                // missing. Null unless the load step failed.
                // (v0.2.9) Fix C: content/video-stage detail rides here
                // too — the row no longer drops forensics by construction.
                // (v0.2.10) Truncated to the first line (+ top frame):
                // the full trace overflowed the row. This .put is the
                // ONLY place detail enters a row, so every surface gets
                // the short form; the full trace stays in the log.
                .put("detail", truncateReasonDetail(detail ?: loadDetail) ?: JSONObject.NULL)
        )
    }

    /**
     * (v0.2.1-l, v0.2.2) Content check shared by providerTest and
     * healthProbe: the home page when the provider has one, else a
     * search-based check. A provider with hasMainPage=false (or an empty
     * home page) must not fail Content/Video just for having no home
     * page. Returns the first usable SearchResponse found, or null.
     *
     * (v0.2.2) The second value reports TRANSPORT failure: true when the
     * site could not be reached at all (Resource.Failure with
     * isNetworkError, or a thrown exception) as opposed to the site
     * answering with no usable content. Callers must NOT treat a
     * transport failure as "the provider is broken" — that is what
     * auto-offed every provider on a dead network.
     *
     * (v0.2.9) Fix C/D/E: now a Triple — the third value is the Failure
     * errorString, threaded through so the row can name the cause instead
     * of "unknown reason". Cancellation (including the withTimeout kill)
     * is rethrown, never converted to a transport failure (Fix D). The
     * search probe word is a TITLE word, never a genre word (Fix E):
     * homepage-derived title words first, then "over" / "iron" / "guy".
     * AniKoto's search is a title-keyword filter: keyword=movie returns
     * nothing while a title word returns real links.
     */
    private suspend fun firstContentItem(api: MainAPI): Triple<SearchResponse?, Boolean, String?> {
        var transportFailed = false
        var failureDetail: String? = null
        var probeWord: String? = null
        if (api.hasMainPage) {
            val feed = try {
                APIRepository(api).getMainPage(1)
            } catch (e: CancellationException) {
                // (v0.2.9) Fix D: never launder a cancellation (incl. the
                // withTimeout kill) into a transport failure.
                throw e
            } catch (e: Exception) {
                transportFailed = true
                null
            }
            if (feed is Resource.Failure) {
                if (feed.isNetworkError) transportFailed = true
                failureDetail = feed.errorString
            }
            val sections = (feed as? Resource.Success<List<HomePageResponse?>>)?.value
            probeWord = probeWordFromSections(sections)
            val first = sections
                ?.flatMap { it?.items ?: emptyList() }
                ?.firstOrNull { it.list.isNotEmpty() }
                ?.list?.firstOrNull()
            if (first != null) return Triple(first, false, null)
            // Home page exists but came back empty: fall through to the
            // search check instead of failing a working provider.
        }
        // (v0.2.9) Fix E: try title words in order; a site that answers
        // but matches nothing for one word gets the next word. A transport
        // failure stops the loop — retrying another word against an
        // unreachable site is pointless.
        val words = (listOfNotNull(probeWord) + listOf("over", "iron", "guy")).distinct()
        for (word in words) {
            val res = try {
                APIRepository(api).search(word, 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transportFailed = true
                failureDetail = "${e.javaClass.simpleName}: ${e.message ?: "no message"}".take(200)
                break
            }
            if (res is Resource.Failure) {
                if (res.isNetworkError) transportFailed = true
                failureDetail = res.errorString
                break
            }
            val item = (res as? Resource.Success<SearchResponseList>)?.value?.items?.firstOrNull()
            if (item != null) return Triple(item, false, null)
        }
        return Triple(null, transportFailed, failureDetail)
    }

    /**
     * (v0.2.9) Fix E: first usable title word from home-page list names
     * (and item titles, when present). Genre/section words are excluded:
     * they are not title keywords and a title-keyword search for them
     * returns nothing on providers like AniKoto.
     */
    private fun probeWordFromSections(sections: List<HomePageResponse?>?): String? {
        val stop = setOf(
            "movie", "movies", "tv", "series", "anime", "cartoon", "cartoons",
            "documentary", "live", "home", "popular", "trending", "latest",
            "top", "new", "featured", "recommended", "all", "list", "watch",
            "free", "hd", "dubbed", "subbed"
        )
        val words = ArrayList<String>()
        sections?.forEach { section ->
            section?.items?.forEach { list ->
                words += list.name.split(Regex("[^A-Za-z]+")).filter { it.length >= 3 }
                list.list.forEach { item ->
                    words += item.name.split(Regex("[^A-Za-z]+")).filter { it.length >= 3 }
                }
            }
        }
        return words.map { it.lowercase() }.firstOrNull { it !in stop }
    }

    private suspend fun providerHealthCheck(onlyOn: Boolean): JSONObject {
        // Targets are per MainAPI (one per loaded API; one plugin row when
        // not loaded), so a 4-API plugin is probed as 4 providers.
        val targets = mutableListOf<HealthTarget>()
        for (repo in RepositoryManager.getRepositories()) {
            val id = repoId(repo.url)
            val wrappers = try {
                RepositoryManager.getRepoPlugins(repo)
            } catch (e: Exception) {
                null
            } ?: continue
            for (w in wrappers) {
                val apis = apisForPlugin(repo, w.plugin)
                if (apis.isEmpty()) {
                    targets.add(HealthTarget(id, repo, w, w.plugin.name, null))
                } else {
                    for (api in apis) targets.add(HealthTarget(id, repo, w, api.name, api))
                }
            }
        }
        // (v0.2.1-3) onlyOn is honoured: by default only ON providers are
        // probed; onlyOn=false (explicit "check all") also reports OFF
        // providers — but their probes skip network work when the plugin
        // was never downloaded (see healthProbe).
        val filtered = if (onlyOn) {
            targets.filter { store.isProviderOn(it.repoId, it.providerName) }
        } else {
            targets
        }
        val results = JSONArray()
        val autoOff = JSONArray()
        val autoOn = JSONArray()
        // Concurrency ~4 (design §5.3).
        for (chunk in filtered.chunked(4)) {
            val chunkResults = coroutineScope {
                chunk.map { t ->
                    async { healthProbe(t) }
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

    private data class HealthTarget(
        val repoId: String,
        val repo: RepositoryData,
        val wrapper: PluginWrapper,
        val providerName: String,
        val api: MainAPI?,
    )

    /**
     * One health probe. Returns a JSON object with repoId, providerName,
     * health ("ok"/"fail", or null when skipped), on + autoOff (post-probe
     * bridge state, so the UI can sync exactly), and change: "off" when an
     * ON provider failed and was auto-switched off, "on" when a previously
     * auto-off provider recovered, else "".
     *
     * (v0.2.1-3) A provider the probe cannot resolve is NEVER auto-offed:
     * it first attempts a download (like providerTest), and only marks
     * fail after resolution + a real getMainPage/search attempt. A
     * provider that was never downloaded is skipped entirely — no
     * health="fail" stamp, no state change.
     */
    private suspend fun healthProbe(t: HealthTarget): JSONObject {
        val name = t.providerName
        val wasOn = store.isProviderOn(t.repoId, name)
        val wasAutoOff = store.isProviderAutoOff(t.repoId, name)
        val previousHealth = store.providerHealth(t.repoId, name)
        // (v0.2.2) Offline: never stamp a provider "fail", never attempt
        // a doomed download, never auto-off when the device itself has no
        // network. A dead network used to auto-off EVERY provider at once.
        if (!isOnline()) {
            return probeJson(t.repoId, name, previousHealth, wasOn, wasAutoOff, "")
        }
        var api = t.api ?: resolveApi(name, t.wrapper.plugin.internalName, t.repoId)
        if (api == null) {
            if (!wasOn && !wasAutoOff) {
                // Never downloaded, never ON: skip, do not stamp "fail".
                return probeJson(t.repoId, name, previousHealth, wasOn, wasAutoOff, "")
            }
            // Was ON (or auto-off): attempt download first, like providerTest.
            if (t.wrapper.plugin.status != PROVIDER_STATUS_DOWN) {
                val downloaded = try {
                    PluginManager.downloadPlugin(
                        activity, t.wrapper.plugin.url, t.wrapper.plugin.fileHash,
                        t.wrapper.plugin.internalName, t.repo.url, true, t.wrapper.plugin.fileSize
                    )
                } catch (e: CancellationException) {
                    // (v0.2.4) Never launder a cancellation into a
                    // failure: propagate the TimeoutCancellationException.
                    throw e
                } catch (e: Exception) {
                    EngineLogger.log("bridge", "healthProbe download threw")
                    false
                }
                // (v0.2.2) Resolve even when the download reports false:
                // the API may already be registered in-memory.
                api = resolveApi(name, t.wrapper.plugin.internalName, t.repoId)
                    ?: apisForPlugin(t.repo, t.wrapper.plugin).firstOrNull { it.name == name }
                    ?: apisForPlugin(t.repo, t.wrapper.plugin).firstOrNull()
            }
            if (api == null) {
                // Still unresolvable: skip, do not stamp "fail".
                return probeJson(t.repoId, name, previousHealth, wasOn, wasAutoOff, "")
            }
        }
        // (v0.2.1-m) Probe with the provider's own getMainPage timeout,
        // capped sanely at 30 s — not a fixed 15 s that fails
        // slow-but-working providers.
        val probeTimeoutMs = APIRepository.getTimeout(api.getMainPageTimeoutMs)
            .coerceIn(5_000L, 30_000L)
        var healthy = false
        var transportFailed = false
        try {
            withTimeout(probeTimeoutMs) {
                val (item, transport, _) = firstContentItem(api)
                healthy = item != null
                transportFailed = transport
            }
        } catch (e: CancellationException) {
            // (v0.2.9) Fix D: a cancelled probe propagates; only a probe
            // TIMEOUT (not an external cancellation) counts as a transport
            // failure with no state change. withTimeout throws
            // TimeoutCancellationException, a CancellationException — so
            // rethrow when the parent scope itself is cancelled.
            if (!currentCoroutineContext().isActive) throw e
            healthy = false
            transportFailed = true
        } catch (e: Exception) {
            healthy = false
            transportFailed = true
        }
        // (v0.2.2) The site could not be reached at all (transport
        // failure): keep the previous health, change NO state. Only a
        // site that answered with no usable content counts as unhealthy.
        if (transportFailed && !healthy) {
            EngineLogger.log("bridge", "healthProbe $name transport failed — no state change")
            return probeJson(t.repoId, name, previousHealth, wasOn, wasAutoOff, "")
        }
        store.setProviderHealth(t.repoId, name, if (healthy) "ok" else "fail")
        var change = ""
        // (v0.2.9) Fix F: AUTO-OFF IS REMOVED. Upstream has no auto-off at
        // all, and this probe turned a provider OFF whenever
        // firstContentItem() returned nothing — without ever consulting
        // Load. An unhealthy probe now only stamps health="fail"; the
        // provider stays ON, and the user turns it off if they want. The
        // recovery branch is KEPT: a provider auto-offed by v0.2.8 turns
        // back ON the next time it probes healthy (self-heal).
        if (healthy && wasAutoOff) {
            store.setProviderOn(t.repoId, name, true)
            store.setProviderAutoOff(t.repoId, name, false)
            change = "on"
        }
        return probeJson(
            t.repoId, name,
            if (healthy) "ok" else "fail",
            store.isProviderOn(t.repoId, name),
            store.isProviderAutoOff(t.repoId, name),
            change,
        )
    }

    private fun probeJson(
        repoId: String,
        providerName: String,
        health: String?,
        on: Boolean,
        autoOff: Boolean,
        change: String,
    ): JSONObject = JSONObject()
        .put("repoId", repoId)
        .put("providerName", providerName)
        .put("health", health ?: JSONObject.NULL)
        .put("on", on)
        .put("autoOff", autoOff)
        .put("change", change)

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
        // (v0.2.1-n) Per-provider resolution failures are surfaced, not
        // silently swallowed as partial=true with zero results.
        val failures = JSONArray()
        var partial = false
        var hasNext = false
        try {
            withTimeout(15_000) {
                for (chunk in ordered.chunked(8)) {
                    val chunkOut = coroutineScope {
                        chunk.map { name ->
                            async<Triple<String, SearchResponseList?, String?>> {
                                val api = resolveApi(name, null)
                                    ?: return@async Triple(name, null, "unresolved")
                                val res = try {
                                    APIRepository(api).search(query, page.coerceAtLeast(1))
                                } catch (e: Exception) {
                                    return@async Triple(name, null, "error")
                                }
                                val list = (res as? Resource.Success<SearchResponseList>)?.value
                                    ?: return@async Triple(name, null, "error")
                                Triple(name, list, null)
                            }
                        }.awaitAll()
                    }
                    for ((name, list, reason) in chunkOut) {
                        if (list == null) {
                            partial = true
                            failures.put(
                                JSONObject()
                                    .put("provider", name)
                                    .put("reason", reason ?: "error")
                            )
                            continue
                        }
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
                .put("failures", failures)
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
        // (v0.2.1-f) The manual parameter is honoured now: a manual check
        // forces a fresh manifest fetch (bypassing the 15-min repo cache)
        // and runs even when automatic plugin updates are off. An
        // automatic check respects the plugAutoUpd setting.
        if (!manual && !store.getSettingBoolean("plugAutoUpd", true)) {
            return ok(
                JSONObject()
                    .put("updated", JSONArray())
                    .put("upToDate", true)
                    .put("skippedAutoCheck", true)
            )
        }
        val updated = JSONArray()
        for (repo in RepositoryManager.getRepositories()) {
            val id = repoId(repo.url)
            val wrappers = when (val result = RepositoryManager.getRepoPluginsResult(repo, manual)) {
                is RepoPluginsResult.Ok -> result.wrappers
                is RepoPluginsResult.Err -> continue
            }
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
                            activity, p.url, p.fileHash, p.internalName, repo.url, true, p.fileSize
                        )
                    } catch (e: CancellationException) {
                        // (v0.2.4) Never launder a cancellation into a
                        // failure: propagate the TimeoutCancellationException.
                        throw e
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
                // (v0.2.9) Fix C: loadLinks now returns its cause.
                val linksResult = APIRepository(api).loadLinks(data, false, { s -> foundSubs += s }) { l -> foundLinks += l }
                if (!linksResult.ok) {
                    partial = foundLinks.isEmpty()
                    linksResult.error?.let { EngineLogger.log("bridge", "resolveLinks loadLinks failed: $it") }
                }
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

    /**
     * (v0.2.1-4) Repo lookup accepts every id form the UI can hold:
     * the URL-hash id ("repo-" + first 16 hex of SHA-256(url)), a catalogue
     * id (e.g. "netmirror"), a bare 16-hex suffix, or the raw URL itself.
     * Native keys are ONLY URL hashes — the catalogue map exists solely so
     * a catalogue id resolves to its URL (and so responses can carry the
     * catalogue id alongside the hash id).
     */
    private fun findRepo(repoId: String): RepositoryData? {
        if (repoId.isBlank()) return null
        val repos = RepositoryManager.getRepositories()
        repos.firstOrNull { it.url == repoId }?.let { return it }
        repos.firstOrNull { repoId(it.url) == repoId }?.let { return it }
        CATALOGUE_URL_BY_ID[repoId]?.let { url ->
            repos.firstOrNull { it.url == url }?.let { return it }
        }
        val hex = repoId.removePrefix("repo-")
        if (hex.length == 16 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            repos.firstOrNull { repoId(it.url) == "repo-${hex.lowercase()}" }?.let { return it }
        }
        return null
    }

    private fun catalogueIdOf(url: String): String? = CATALOGUE_ID_BY_URL[url]

    private suspend fun findWrapper(repo: RepositoryData, providerName: String): PluginWrapper? {
        val wrappers = try {
            RepositoryManager.getRepoPlugins(repo)
        } catch (e: Exception) {
            null
        } ?: return null
        // (v0.2.1-2) Match by SitePlugin name first, then by MainAPI name
        // (repo-scoped, via the resolve fallback chain): a row for one of
        // NetMirror's four MainAPIs must find the Netmirror wrapper.
        wrappers.firstOrNull { it.plugin.name == providerName }?.let { return it }
        val api = resolveApi(providerName, null, repoId(repo.url)) ?: return null
        val filePath = api.sourcePlugin ?: return null
        return wrappers.firstOrNull { w ->
            storedPluginData(repo, w.plugin.internalName)?.filePath == filePath
        }
    }

    private fun storedPluginData(repo: RepositoryData, internalName: String): PluginData? {
        val folder = PluginManager.getPluginSanitizedFileName(repo.url)
        return PluginManager.getPluginsOnline().firstOrNull {
            it.internalName == internalName && it.filePath.contains("/$folder/")
        }
    }

    /**
     * (v0.2.1-1) Resolve a provider's MainAPI. The old code matched ONLY
     * SitePlugin.name against MainAPI.name, so a plugin whose name differs
     * from its APIs (NetMirror: one SitePlugin "Netmirror" registering four
     * MainAPIs — Netflix, Disney Plus, Hotstar, Prime Video) could never
     * resolve. Fallback chain, in order — repo-scoped (to this repo's
     * loaded APIs) whenever repoId is known, global otherwise:
     * (a) exact MainAPI.name; (b) exact internalName (via the stored
     * .cs3 for that internalName); (c) internalName minus a trailing
     * "Provider" suffix, case-insensitive (as upstream loadSinglePlugin
     * does); (d) normalised comparison (lowercase, strip spaces / + / - / .).
     * The 2-arg overload keeps the existing call sites working.
     */
    private fun resolveApi(providerName: String, internalName: String?): MainAPI? =
        resolveApi(providerName, internalName, null)

    private fun resolveApi(providerName: String, internalName: String?, repoId: String?): MainAPI? {
        // Repo-scoped pool when the repo is known; global pool otherwise.
        // A known repo with nothing loaded resolves to nothing — falling
        // back to another repo's same-named provider would be wrong.
        val pool: List<MainAPI> = if (repoId != null) apisForRepo(repoId) else APIHolder.allProviders
        // (a) exact MainAPI.name.
        pool.firstOrNull { it.name == providerName }?.let { return it }
        if (!internalName.isNullOrBlank()) {
            // (b) exact internalName -> stored .cs3 -> its MainAPIs.
            apiForInternalName(internalName, pool)?.let { return it }
            // (c) internalName minus a trailing "Provider" suffix.
            val stripped = stripProviderSuffix(internalName)
            if (!stripped.equals(internalName, ignoreCase = true)) {
                pool.firstOrNull { it.name.equals(stripped, ignoreCase = true) }?.let { return it }
            }
            // (d) normalised comparison against the internalName.
            pool.firstOrNull { normaliseName(it.name) == normaliseName(internalName) }?.let { return it }
        }
        // (d) normalised comparison against the provider name.
        pool.firstOrNull { normaliseName(it.name) == normaliseName(providerName) }?.let { return it }
        // Legacy fallback: upstream's own name map (global only).
        if (repoId == null) return APIHolder.getApiFromNameNull(providerName)
        return null
    }

    private fun normaliseName(s: String): String =
        s.lowercase().replace(Regex("[\\s+\\-.]"), "")

    private fun stripProviderSuffix(s: String): String =
        if (s.endsWith("provider", ignoreCase = true)) s.dropLast("provider".length) else s

    /** Loaded MainAPIs of one repo (by .cs3 path inside the repo folder). */
    private fun apisForRepo(repoId: String): List<MainAPI> {
        val repo = findRepo(repoId) ?: return emptyList()
        val folder = PluginManager.getPluginSanitizedFileName(repo.url)
        return APIHolder.allProviders.filter { it.sourcePlugin?.contains("/$folder/") == true }
    }

    /** The MainAPI registered by the stored .cs3 for an internalName. */
    private fun apiForInternalName(internalName: String, pool: List<MainAPI>): MainAPI? {
        val datas = PluginManager.getPluginsOnline().filter { it.internalName == internalName }
        for (d in datas) {
            pool.firstOrNull { it.sourcePlugin == d.filePath }?.let { return it }
        }
        return null
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
        // (v0.2.9) Fix G: provLang setting names → BCP 47 codes, same
        // table as the UI's LCODE (zaynplay-app.html).
        private val PROV_LANG_CODES = mapOf(
            "English" to "en", "Bengali" to "bn", "Hindi" to "hi",
            "Marathi" to "mr", "Telugu" to "te", "Tamil" to "ta",
            "Gujarati" to "gu", "Urdu" to "ur", "Kannada" to "kn",
            "Odia" to "or", "Malayalam" to "ml"
        )

        fun repoId(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "repo-" + hex.take(16)
        }

        /**
         * (v0.2.2) The 24-repo catalogue (id + URL), rebuilt from the
         * UI's REPO_CATALOGUE and live-verified 2026-11-26 (23/24 return
         * HTTP 200; turkish's manifest is 404 — kept, as the UI keeps
         * it flagged manifestDead). The v0.2.1 list was the STALE 27-repo
         * snapshot: phisher pointed at "zaynplay-extensions-phisher"
         * (404; correct is "cloudstream-extensions-phisher"), cspr at
         * "zaynplay/extensions" (404), redowan had a literal space in
         * the URL, and extcloud / cuxplug / sarapcanagii are dead — so
         * findRepo() could never resolve a catalogue id for those repos
         * and catalogueIdOf() returned null for their installed repos.
         */
        private val CATALOGUE_REPOS: List<Pair<String, String>> = listOf(
            "cspr" to "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json",
            "mega" to "https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json",
            "phisher" to "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json",
            "megix" to "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json",
            "3rabi" to "https://raw.githubusercontent.com/Abodabodd/re-3arabi/refs/heads/main/repo",
            "indostream" to "https://raw.githubusercontent.com/TeKuma25/IndoStream/builds/repo.json",
            "cloudx" to "https://raw.githubusercontent.com/Asm0d3usX/CloudX/builds/repo.json",
            "dogior" to "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/builds/repo.json",
            "cncverse" to "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json",
            "diegon" to "https://pastebin.com/raw/qndZtL6D",
            "cskarma" to "https://raw.githubusercontent.com/Kraptor123/cs-Karma/refs/heads/master/repo.json",
            "cakestwix" to "https://codeberg.org/CakesTwix/cloudstream-extensions-uk/raw/branch/master/repo.json",
            "lawlietrepo" to "https://raw.githubusercontent.com/lawlietbr/lietrepo/refs/heads/main/builds/repo.json",
            "king" to "https://pastebin.com/raw/Cd2g2tfz",
            "italianprovider" to "https://raw.githubusercontent.com/Gian-Fr/ItalianProvider/builds/repo.json",
            "netmirror" to "https://raw.githubusercontent.com/Sushan64/NetMirror-Extension/refs/heads/builds/Netflix.json",
            "redowan" to "https://raw.githubusercontent.com/redowan99/Redowan-Cloudstream/master/repo.json",
            "saimuel" to "https://raw.githubusercontent.com/saimuelbr/saimuelrepo/refs/heads/main/builds/repo.json",
            "vietnamese" to "https://gitlab.com/tearrs/cloudstream-vietnamese/-/raw/main/repo.json",
            "turkish" to "https://raw.githubusercontent.com/Kraptor123/cs-kraptor/refs/heads/master/repo.json",
            "german" to "https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json",
            "luna712" to "https://raw.githubusercontent.com/Luna712/Luna712-Cloudstream-Extensions/28885d17ceb7f24782b732b6056085c14c1fd027/repo.json",
            "zzikozz" to "https://raw.githubusercontent.com/zzikozz/frenchCS/refs/heads/main/repo.json",
            "gramflix" to "https://raw.githubusercontent.com/tOntOnbOuLii/GramFlix/main/repo.json",
            // (v0.2.10) 15 repos from the CloudStream wiki's maintained
            // list that were missing from the catalogue (verified live
            // 2026-11-27; eclipsia's manifest 403s from here, so its
            // URL is the wiki's repo link and its plugin names are the
            // wiki's list, flagged manifestDead in the HTML).
            "raghav" to "https://raw.githubusercontent.com/raghavdhingra11/extensions/refs/heads/builds/repo.json",
            "eclipsia" to "https://cs.eclipsia.dpdns.org/repo.json",
            "indflix" to "https://raw.githubusercontent.com/Gujjar001/Indflix/refs/heads/builds/repo.json",
            "storm" to "https://raw.githubusercontent.com/stormunblessed/storm-cloudstream/refs/heads/builds/repo.json",
            "reflex" to "https://raw.githubusercontent.com/Reflex-frp/reflex-repo/refs/heads/builds/repo.json",
            "flummox" to "https://raw.githubusercontent.com/FlummoxCS/repo/refs/heads/builds/repo.json",
            "plaviohd" to "https://raw.githubusercontent.com/mr-robot-ops/plaviohd/refs/heads/builds/repo.json",
            "csar" to "https://raw.githubusercontent.com/alihassan-1997/CloudStream-Arabic/refs/heads/builds/repo.json",
            "mehdigm" to "https://raw.githubusercontent.com/MehdiiGM/Cloudstream/refs/heads/builds/repo.json",
            "cartoony" to "https://raw.githubusercontent.com/Lord-Kamina/Cartoony/refs/heads/builds/repo.json",
            "bigbull" to "https://raw.githubusercontent.com/BigBullApp/Cloudstream-repo/refs/heads/builds/repo.json",
            "yorik100" to "https://raw.githubusercontent.com/Yorik100/Cloudstream-French/refs/heads/builds/repo.json",
            "anicompat" to "https://raw.githubusercontent.com/recloudstream/anicompat/refs/heads/builds/repo.json",
            "desi" to "https://raw.githubusercontent.com/fahim2204/extensions/refs/heads/builds/repo.json",
            "csgames" to "https://raw.githubusercontent.com/errorcode26/ayu-cloudstream-games/builds/repo.json",
        )
        private val CATALOGUE_URL_BY_ID: Map<String, String> = CATALOGUE_REPOS.toMap()
        private val CATALOGUE_ID_BY_URL: Map<String, String> =
            CATALOGUE_REPOS.associate { (id, url) -> url to id }
    }

    private fun ok(data: JSONObject): JSONObject =
        JSONObject().put("ok", true).put("data", data)

    private fun err(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("code", code).put("message", message)
}
