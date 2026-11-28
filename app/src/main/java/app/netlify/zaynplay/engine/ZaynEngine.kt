package app.netlify.zaynplay.engine

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.initClient
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
import com.lagradost.cloudstream3.utils.HlsPlaylistParser
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
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
    private val onEvent: (String, JSONObject) -> Unit = { _, _ -> },
    private val onStageRect: (JSONObject) -> Unit,
    // (v1.0.18 P16-06) WebView cache clear runs on the UI thread inside
    // MainActivity (WebView.clearCache must be called there); the engine
    // only invokes it. Null in tests / when the host does not provide it.
    private val clearWebViewCache: (() -> Unit)? = null,
) {
    // (v1.0.18 P16-07) Bounded poster prefetch cache (OkHttp disk cache,
    // 100 MB cap — see ZaynPosterCache).
    private val posterCache = ZaynPosterCache(activity)
    private val library = ZaynLibrary(store)
    private val subtitles = ZaynSubtitles()
    private val accounts = ZaynAccounts(store)
    private val updater = ZaynUpdater(activity) { downloaded, total ->
        onEvent(
            "updateDownloadProgress",
            JSONObject()
                .put("downloadedBytes", downloaded)
                .put("totalBytes", total)
                .put("pct", if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else -1),
        )
    }
    private val subtitleCache = ConcurrentHashMap<String, String>()
    @Volatile
    var engineReady: Boolean = false
        private set

    // (v1.0.5 B2) Auto-retry state: the ranked list of the CURRENT play
    // session, so an ExoPlayer error can advance to the next link without
    // a new bridge call. A new play() replaces the session; the session id
    // guards against a late error from an old session retrying.
    private data class ActivePlay(
        var ranked: List<RankedLink>,
        var index: Int,
        var attempts: Int,
        val session: Int,
        // (v1.0.14 G8) Mutable: a retry resumes from the position the
        // failure actually happened at (captured in onPlaybackError),
        // not from the original resume point.
        var safeStartSec: Int,
        val subtitleText: String?,
        val subtitleLang: String?,
        val providerSubUrl: String?,
        val title: String,
        // (v1.0.6 R1) The original play() params, so a retry can re-resolve
        // links (fresh loadLinks) when the ranked list is exhausted.
        val params: JSONObject,
        // (v1.0.13 P4-B) A fresh re-resolve has already been spent once
        // for a MANIFEST_AUTH failure in this session — never spend it twice.
        var authReresolved: Boolean = false,
        // (v1.0.13 P6-B) A fresh re-resolve has already been spent once
        // for a SOURCE_NOT_FOUND / LINK_EXPIRED failure in this session —
        // never spend it twice (never replay the failed URL).
        var sourceReresolved: Boolean = false,
    )

    private var activePlay: ActivePlay? = null
    private var playSessionCounter = 0
    private val maxPlayAttempts = 3

    // (v1.0.13 P6-C) Dead-session latch (INVESTIGATION-4 §7.3): set when a
    // play session ends in failure (no retry left, or a fail-fast class);
    // playerControl refuses seek/resume/toggle/rate while it is set, until
    // the next play() arms a new session and clears it.
    @Volatile
    private var playbackFailed: Boolean = false

    // (v1.0.14 CAUSE-2) Last probe skip reason (validator verdict, e.g.
    // "media playlist has no segments"), set by probePlayable() and
    // cleared at the start of each playInternal(). Surfaced in the play
    // rejection's detail slot so the user sees WHY, not just "No playable
    // links found" — and so the log names the failing link's verdict.
    private var lastProbeSkipReason: String? = null


    // (v1.0.12 §7) Duplicate-play guard: one play() at a time (a new user
    // play supersedes the old one — the old one's activePlay is replaced
    // anyway), plus request-id dedup (a repeated bridge id returns the
    // cached reply) so the c31–c36 residue can never recur.
    private val playMutex = Mutex()
    // (v1.0.23 D) Mirror-probe warming (TRACE-1.0.23-LOADLINKS §8.1):
    // a background loadLinks on details open populates the provider's
    // shared API-base cache (24 h prefs + process static) BEFORE the
    // play tap, so the play path starts at the otp/player stages. One
    // job per process at most (warmLinksActive single-flight) and one
    // success per process (warmLinksDone); a later details open never
    // launches a duplicate. Warming never holds playMutex and play
    // never waits for it — a tap during warming behaves exactly as
    // today (its own probe), only slower to benefit, never slower
    // than today.
    private val warmLinksActive = AtomicBoolean(false)
    private val warmLinksDone = AtomicBoolean(false)
    private var lastPlayRequestId: String? = null
    private var lastPlayResult: JSONObject? = null
    // (v1.0.14 P4 rank 4) Play dedup is BOUNDED: a params hash (the same
    // id with different params is a different play), a 60 s TTL, and
    // ONE-USE — the cached reply is consumed once, then cleared, so a
    // third identical delivery replays for real. The old cache lived
    // forever and ignored the params.
    private var lastPlayParamsHash: Int = 0
    private var lastPlayAtMs: Long = 0L
    // (v1.0.14 P4 rank 4) Bounded generic dedup for idempotent READS
    // only (homeFeed/search/details/repos/library/progressAll/downloads):
    // 64 entries, 60 s TTL, keyed (method, requestId, params hash).
    // NEVER dedup writes — a retried repoAdd/providerSetOn/settingsSet
    // must run again. Only ok replies are cached.
    private val readDedup = LinkedHashMap<String, Pair<Long, JSONObject>>()
    private val readDedupMethods = setOf("homeFeed", "homeFeedSection", "homeFeedSections", "search", "details", "repos", "library", "progressAll", "downloads")

    // (v1.0.20 P21-B3/B4) Playback-start timing: playInternal records
    // the request start + link-resolve duration; noteFirstFrame() (called
    // from MainActivity when the player fires playerFirstFrame) logs
    // request -> first frame with the resolve split. Logging only — no
    // behaviour change to playback.
    private var lastPlayT0Ms: Long = 0L
    private var lastPlayResolveMs: Long = -1L
    private var lastPlaySession: String = ""
    private var lastPlayTitle: String = ""
    private var firstFrameLoggedForSession: String = ""
    private var mem30sToken: Int = 0

    /** (v1.0.20 P21-B4) One PSS memory sample: PSS + device available
     * memory, epoch-ms timestamped via logPerf. Best-effort; never throws. */
    private fun logMemSample(activityLabel: String) {
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val pssKb = try {
                val infos = am?.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
                infos?.firstOrNull()?.totalPss ?: -1
            } catch (e: Exception) {
                -1
            }
            @Suppress("DEPRECATION")
            val runtimeMb = Runtime.getRuntime().totalMemory() / (1024 * 1024)
            EngineLogger.logPerf(
                "perf",
                "mem pssMb=${if (pssKb >= 0) pssKb / 1024 else -1} availMb=${memInfo.availMem / (1024 * 1024)} runtimeMb=$runtimeMb activity=$activityLabel",
            )
        } catch (e: Exception) {
            // Memory sampling is best-effort; never break playback/feed.
        }
    }

    /**
     * (v1.0.20 P21-B3) Called by MainActivity when either engine fires
     * playerFirstFrame. Logs request -> first frame for the current play
     * session (once per session), plus a memory sample at first frame
     * and one more 30 s into playback. The first-frame callback proves
     * decoding, not display (v1.0.9) — the ms is request-to-decode time.
     */
    fun noteFirstFrame() {
        val session = lastPlaySession
        if (session.isEmpty() || session == firstFrameLoggedForSession) return
        firstFrameLoggedForSession = session
        val ms = if (lastPlayT0Ms > 0) System.currentTimeMillis() - lastPlayT0Ms else -1
        EngineLogger.logPerf(
            "perf",
            "player firstFrame session=$session ms=$ms engine=exoplayer resolveMs=$lastPlayResolveMs title=$lastPlayTitle",
        )
        logMemSample("player")
        val token = ++mem30sToken
        Handler(Looper.getMainLooper()).postDelayed({
            if (token == mem30sToken) logMemSample("player+30s")
        }, 30_000)
    }

    /** (v1.0.20 P21-B4) JS calls this once, when the first feed paints:
     * cold start = onCreate anchor -> first paint. Returns the ms so JS
     * can also log it to the console (which reaches device logcat). */
    private fun perfFirstPaint(): JSONObject {
        val t0 = EngineLogger.coldStartT0Ms
        val ms = if (t0 > 0) SystemClock.elapsedRealtime() - t0 else -1
        EngineLogger.logPerf("perf", "coldStart ms=$ms")
        return ok(JSONObject().put("ms", ms))
    }


    /** (v1.0.12 §8) Current diagnostics session id. */
    fun currentSession(): Int = playSessionCounter

    init {
        // (v1.0.5 B2) ZaynPlayer offers every ExoPlayer error here first.
        // Start a retry when this session has a next ranked link and an
        // attempt left (3 attempts total per session); otherwise return
        // false and the player fires the usual playerError toast.
        // (v1.0.6 R1) A single-link session has no next link, so the old
        // gate could NEVER retry it (V1.0.5 log: ranked.size==1, "no retry
        // left (attempts=1)"). When the list is exhausted but attempts
        // remain, re-resolve links for a NEW ranked list instead — a retry
        // of the same URL after a transient transport drop is worth it too.
        player.onPlaybackError = { error ->
            val ap = activePlay
            // (v1.0.13 P4-B, P6-B) Classify the failure once; the class drives
            // both the log line below and the recovery route after it.
            // MANIFEST_MALFORMED -> next link / other engine (P2);
            // MANIFEST_AUTH -> fresh re-resolve once, never the same URL;
            // DECODE_ERROR / UNSUPPORTED_FORMAT -> other engine once;
            // SOURCE_NOT_FOUND / LINK_EXPIRED -> fresh re-resolve once;
            // DRM_ERROR / OTHER (UNKNOWN) -> fail fast, no blind retry;
            // MANIFEST_HTTP / NETWORK / NETWORK_TIMEOUT /
            // PLAYBACK_INTERRUPTED / STALLED -> existing retry below.
            // (v1.0.14 G8) Retries resume from HERE, not from the
            // original resume point: capture the current position (cached
            // fields only — never touch the player off the main thread)
            // and clamp it: within 30 s of the end restarts at 0, same
            // rule as the play-time safeStartSec guard.
            if (ap != null) {
                val posMs = player.positionMs()
                val durMs = player.durationMs()
                val posSec = (posMs / 1000).toInt()
                if (posSec > 0) {
                    ap.safeStartSec = if (durMs > 0 && posSec >= durMs / 1000 - 30) 0 else posSec
                    EngineLogger.log("player", "retry resume position posSec=${ap.safeStartSec} session=${ap.session}")
                }
            }
            val errorClass = player.classifyPlaybackError(error)
            // (v1.0.13 P2-B) Log the failure in full: message + cause chain +
            // failing URI, token-redacted — the V1.0.12 log recorded only the
            // errorCodeName, which cannot distinguish top-level-malformed
            // from child-playlist-failed from error-page-body
            // (INVESTIGATION-4 §3.5). (v1.0.13 P4-B) errorClass is
            // appended LAST so the P2 log shape stays an exact
            // substring (gate AJ asserts it).
            val failingUri = if (ap != null) ap.ranked.getOrNull(ap.index)?.link?.url else null
            EngineLogger.log(
                "player",
                "playback error ${error.errorCodeName} message=${redactUrlForLog(error.message)} cause=${causeChainForLog(error)} uri=${redactUrlForLog(failingUri)} errorClass=$errorClass",
            )
            if (ap == null || ap.attempts >= maxPlayAttempts) {
                // (v1.0.13 P6-B/C) Attempts exhausted: end the session here.
                // Before P6 only failPlaySession cleared activePlay, and this
                // path never took it — the dead session stayed armed and its
                // controls kept silently no-op'ing (INVESTIGATION-4 §7.3).
                // The playerError event is still fired by
                // ZaynPlayer.onPlayerError (false below), which latches the
                // JS banner; the engine latch below refuses further controls.
                if (ap != null) activePlay = null
                playbackFailed = true
                EngineLogger.log("player", "recovery errorClass=$errorClass action=fail attempts=${ap?.attempts ?: 0} session=${ap?.session ?: 0}")
                EngineLogger.log("player", "error ${error.errorCodeName} — no retry left (attempts=${ap?.attempts ?: 0} ranked.size=${ap?.ranked?.size ?: 0})")
                false
            } else if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) {
                // (v1.0.13 P2-B, v1.0.23) MALFORMED: never re-resolve the same provider
                // for the same URL shape — the old loop re-probed and re-played
                // the same class of failure 3x with fresh tokens and no new
                // information (INVESTIGATION-4 §3.7). Spend the attempts on
                // DIFFERENT links instead: next ranked link if one exists,
                // else fail. (v1.0.23: the other-engine resort is gone —
                // ExoPlayer is the only player now.) The 3-attempt budget
                // (maxPlayAttempts) still caps it all.
                if (ap.index + 1 < ap.ranked.size) {
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=next-link attempts=${ap.attempts} session=${ap.session}")
                    bridgeScope.launch { retryNextLink(ap) }
                    true
                } else {
                    playbackFailed = true
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=fail attempts=${ap.attempts} session=${ap.session}")
                    EngineLogger.log("player", "error ${error.errorCodeName} — no retry left (MALFORMED: no next link, attempts=${ap.attempts} ranked.size=${ap.ranked.size})")
                    false
                }
            } else if (errorClass == ZaynPlayer.PlaybackErrorClass.MANIFEST_AUTH) {
                // (v1.0.13 P4-B) AUTH (401/403 — token/Referer rejected):
                // NEVER retry the same URL, and never spend the attempt on
                // another link from the same resolve batch (same minting
                // time, same rejection). Re-resolve fresh ONCE (new
                // tokens), then fail. The 3-attempt budget still caps it
                // all: the re-resolve's play counts via playRetryLink,
                // and the top guard stops any further retry.
                if (!ap.authReresolved) {
                    ap.authReresolved = true
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=fresh-resolve attempts=${ap.attempts} session=${ap.session}")
                    EngineLogger.log("player", "error ${error.errorCodeName} errorClass=$errorClass — auth rejected, re-resolving fresh once (attempts=${ap.attempts} session=${ap.session})")
                    bridgeScope.launch { reresolveAndRetry(ap) }
                    true
                } else {
                    playbackFailed = true
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=fail attempts=${ap.attempts} session=${ap.session}")
                    EngineLogger.log("player", "error ${error.errorCodeName} errorClass=$errorClass — no retry left (AUTH: fresh re-resolve already tried, attempts=${ap.attempts} ranked.size=${ap.ranked.size})")
                    false
                }
            } else if (errorClass == ZaynPlayer.PlaybackErrorClass.DECODE_ERROR ||
                errorClass == ZaynPlayer.PlaybackErrorClass.UNSUPPORTED_FORMAT
            ) {
                // (v1.0.13 P6-B, v1.0.23) DECODE_ERROR / UNSUPPORTED_FORMAT:
                // (v1.0.23) there is no other engine any more — ExoPlayer
                // is the only player, so the retry is the NEXT ranked link
                // (a different encode may decode) — never the same link,
                // and never a re-resolve (a fresh token cannot fix a
                // decoder). Then fail. The 3-attempt budget still caps it.
                if (ap.index + 1 < ap.ranked.size) {
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=next-link attempts=${ap.attempts} session=${ap.session}")
                    bridgeScope.launch { retryNextLink(ap) }
                    true
                } else {
                    playbackFailed = true
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=fail attempts=${ap.attempts} session=${ap.session}")
                    EngineLogger.log("player", "error ${error.errorCodeName} — no retry left (DECODE: no next link, attempts=${ap.attempts} ranked.size=${ap.ranked.size})")
                    false
                }
            } else if (errorClass == ZaynPlayer.PlaybackErrorClass.SOURCE_NOT_FOUND ||
                errorClass == ZaynPlayer.PlaybackErrorClass.LINK_EXPIRED
            ) {
                // (v1.0.13 P6-B) SOURCE_NOT_FOUND / LINK_EXPIRED: the URL
                // itself is dead (404 / 410 / expired auth) — NEVER replay
                // it as-is. Re-resolve fresh ONCE (a new URL, a new token),
                // then fail.
                if (!ap.sourceReresolved) {
                    ap.sourceReresolved = true
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=fresh-resolve attempts=${ap.attempts} session=${ap.session}")
                    bridgeScope.launch { reresolveAndRetry(ap) }
                    true
                } else {
                    playbackFailed = true
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=fail attempts=${ap.attempts} session=${ap.session}")
                    EngineLogger.log("player", "error ${error.errorCodeName} — no retry left (SOURCE: fresh re-resolve already tried, attempts=${ap.attempts} ranked.size=${ap.ranked.size})")
                    false
                }
            } else if (errorClass == ZaynPlayer.PlaybackErrorClass.DRM_ERROR ||
                errorClass == ZaynPlayer.PlaybackErrorClass.OTHER
            ) {
                // (v1.0.13 P6-B) DRM_ERROR / OTHER (the UNKNOWN fallback):
                // fail fast with the error surfaced — no blind retry. A DRM
                // failure cannot be fixed by another link or a new token,
                // and an unclassified failure has no signal to aim a retry
                // at, so a retry would only burn attempts on the same
                // failure.
                playbackFailed = true
                EngineLogger.log("player", "recovery errorClass=$errorClass action=fail attempts=${ap.attempts} session=${ap.session}")
                false
            } else if (ap.index + 1 < ap.ranked.size) {
                // (v1.0.13 P4-B) MANIFEST_HTTP / NETWORK / OTHER: the
                // pre-P6 retry grouped these three; (v1.0.13 P6-B) OTHER
                // leaves the group and fails fast above (DRM_ERROR /
                // UNKNOWN: no blind retry), while MANIFEST_HTTP, NETWORK,
                // NETWORK_TIMEOUT, PLAYBACK_INTERRUPTED and STALLED keep
                // the existing retry — next ranked link, else a fresh
                // re-resolve.
                EngineLogger.log("player", "recovery errorClass=$errorClass action=next-link attempts=${ap.attempts} session=${ap.session}")
                bridgeScope.launch { retryNextLink(ap) }
                true
            } else {
                EngineLogger.log("player", "recovery errorClass=$errorClass action=fresh-resolve attempts=${ap.attempts} session=${ap.session}")
                bridgeScope.launch { reresolveAndRetry(ap) }
                true
            }
        }

    }

    // (v1.0.23) ExoPlayer is the ONLY player: no engine setting, no
    // switch, no fallback. playActive prepares the one player and plays.
    private fun playActive(
        link: ZaynPlayer.PlayLink,
        startAtMs: Long,
        subtitleText: String?,
        subtitleLang: String?,
        subtitleUrl: String?,
        cacheSizeBytes: Long,
        bufferLevel: String = "normal",
        preferredAudioLang: String? = null,
    ) {
        // (v1.0.18 P17-02) Subtitle style from Settings, for the
        // ExoPlayer HTML overlay.
        val subtitleStyle = subtitleStyleJson()
        player.play(link, startAtMs, subtitleText, subtitleLang, subtitleUrl, cacheSizeBytes, bufferLevel, preferredAudioLang, subtitleStyle)
    }

    private fun activeStateSnapshot(): JSONObject = player.stateSnapshot()

    /** (v1.0.22 B) The playing stream's REAL audio/subtitle tracks, from the player (ExoPlayer currentTracks). The JS pickers show only these (+ link audio / provider subtitles as fallback) — never a hardcoded list. */
    private fun playerTracks(): JSONObject = player.tracksSnapshot()

    private fun subtitlesJson(subs: List<SubtitleFile>): JSONArray {
        val arr = JSONArray()
        for (s in subs) arr.put(JSONObject().put("lang", s.lang).put("url", s.url).put("source", "provider"))
        return arr
    }

    /**
     * (v1.0.5 B2) Play one ranked link as a retry: count the attempt,
     * log it, remember the link that actually played, then play it.
     * Shared by retryNextLink (next link in the current list) and
     * reresolveAndRetry (first link of a freshly resolved list).
     */
    private suspend fun playRetryLink(ap: ActivePlay, ranked: List<RankedLink>, next: Int, candidate: RankedLink) {
        val link = candidate.link
        ap.ranked = ranked
        ap.index = next
        ap.attempts++
        EngineLogger.log("player", "retry attempt ${ap.attempts}/$maxPlayAttempts: play ${link.source} ${qualityLabel(link.quality)} type=${link.type} url=${redactUrlForLog(link.url)}")
        if (ap.title.isNotBlank()) {
            putLastLink(
                ap.title,
                library.progressKey(
                    ap.params.optString("url", ""),
                    ap.params.optString("providerName", ""),
                    ap.params.optString("ep", ""),
                ),
                next,
            )
        }
        // (v1.0.12 §8) Retries keep the session id.
        player.diagSessionId = ap.session
        playActive(
            ZaynPlayer.PlayLink(
                url = link.url,
                source = link.source,
                qualityLabel = qualityLabel(link.quality),
                audio = candidate.audio,
                referer = link.referer,
                headers = ZaynHeaderManager.headersFor(link),
                type = link.type,
            ),
            ap.safeStartSec * 1000L,
            ap.subtitleText,
            ap.subtitleLang,
            ap.providerSubUrl,
            cacheSizeBytes(),
            bufferLevel(),
            preferredAudioLang(candidate.audio),
        )
    }

    /** Fire the withheld playerError event and end the session. */
    private fun failPlaySession(ap: ActivePlay, reason: String) {
        EngineLogger.log("player", reason)
        activePlay = null
        // (v1.0.13 P6-C) The session ended in failure — latch the
        // dead-session flag so further controls are refused until the
        // next play().
        playbackFailed = true
        onEvent(
            "playerError",
            JSONObject()
                .put("code", "playback_error")
                .put("message", "Video could not be played")
                .put("retryable", true),
        )
    }

    /**
     * (v1.0.6 R1) The ranked list is exhausted but attempts remain:
     * re-resolve links (fresh loadLinks via resolveLinks with the original
     * play params) to build a NEW ranked list and retry from it. The
     * re-resolve itself counts as the retry attempt (playRetryLink
     * increments ap.attempts), so the 3-attempt cap still holds. If the
     * re-resolve yields nothing playable, the withheld playerError event
     * is finally fired.
     */
    private suspend fun reresolveAndRetry(ap: ActivePlay) {
        // A newer play() replaces the session — a late error must not
        // restart an old title.
        if (activePlay !== ap) return
        EngineLogger.log("player", "retry re-resolving links (attempts=${ap.attempts} ranked.size=${ap.ranked.size} session=${ap.session})")
        val (resolved, _) = resolveLinks(ap.params)
        if (activePlay !== ap) return
        if (resolved == null || resolved.links.isEmpty()) {
            failPlaySession(ap, "retry re-resolve found no links — no retry left (attempts=${ap.attempts} ranked.size=${ap.ranked.size} session=${ap.session})")
            return
        }
        val ranked = rankLinks(resolved.links, ap.params.optString("providerName", ""))
        if (ranked.isEmpty()) {
            failPlaySession(ap, "retry re-resolve found no playable links — no retry left (attempts=${ap.attempts} ranked.size=${ap.ranked.size} session=${ap.session})")
            return
        }
        var next = 0
        var candidate: RankedLink? = null
        val probeLimit = if (ranked.size < 3) ranked.size else 3
        while (next < probeLimit) {
            val c = ranked[next]
            if (c.link is DrmExtractorLink) {
                next++
                continue
            }
            if (probePlayable(c.link, ap.params.optString("providerName", ""))) {
                candidate = c
                break
            }
            next++
        }
        if (candidate == null) {
            failPlaySession(ap, "retry re-resolve found no playable link — no retry left (attempts=${ap.attempts} ranked.size=${ap.ranked.size} session=${ap.session})")
            return
        }
        playRetryLink(ap, ranked, next, candidate)
    }

    /**
     * (v1.0.5 B2) Advance the CURRENT session to the next ranked link
     * after an ExoPlayer error. Probing skips unplayable candidates
     * without spending a play attempt; DRM links are skipped too. When
     * no candidate is left, the withheld playerError event is finally
     * fired so the user sees the failure instead of silence.
     */
    private suspend fun retryNextLink(ap: ActivePlay) {
        // A newer play() replaces the session — a late error must not
        // restart an old title.
        if (activePlay !== ap) return
        var next = ap.index + 1
        var candidate: RankedLink? = null
        while (next < ap.ranked.size) {
            val c = ap.ranked[next]
            if (c.link is DrmExtractorLink) {
                next++
                continue
            }
            if (!probePlayable(c.link, ap.params.optString("providerName", ""))) {
                next++
                continue
            }
            candidate = c
            break
        }
        if (candidate == null) {
            failPlaySession(ap, "retry exhausted — no playable link left in session ${ap.session}")
            return
        }
        playRetryLink(ap, ap.ranked, next, candidate)
    }

    fun markReady() {
        engineReady = true
    }

    // ---- entry point (called from MainActivity's IO scope) ----

    // (v1.0.14 P4 rank 5) Bridge version: JS stamps _bv on every call
    // (H BRIDGE_VERSION); a mismatched version is rejected rather than
    // silently misread. Absent _bv is allowed (legacy/internal calls).
    private val bridgeVersion = 1

    suspend fun call(method: String, params: JSONObject, requestId: String? = null): JSONObject {
        if (params.has("_bv") && !params.isNull("_bv") && params.optInt("_bv", 1) != bridgeVersion) {
            EngineLogger.log("bridge", "$method bridge_version_mismatch js=${params.optInt("_bv", -1)} native=$bridgeVersion")
            return err("bridge_version_mismatch", "Bridge version mismatch — update the app")
        }
        // (v1.0.14 P4 rank 4) Bounded generic dedup for idempotent reads
        // (see field comment): same method + requestId + params within
        // 60 s returns the cached ok reply. Writes are never cached.
        val dedupKey = if (method in readDedupMethods && requestId != null) {
            "$method|$requestId|${params.toString().hashCode()}"
        } else null
        if (dedupKey != null) {
            synchronized(readDedup) {
                val cached = readDedup[dedupKey]
                if (cached != null && System.currentTimeMillis() - cached.first <= 60_000) {
                    EngineLogger.log("bridge", "$method dedup request=$requestId — returning cached reply")
                    return cached.second
                }
                if (cached != null) readDedup.remove(dedupKey)
            }
        }
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
                "providerHealth" -> ok(JSONObject().put("records", store.providerHealthDetails()))
                "providerHealthRecord" -> providerHealthRecord(params)
                "providerHealthCheck" -> providerHealthCheck(params.optBoolean("onlyOn", true))
                "homeFeed" -> homeFeed(
                    params.optString("providerName", ""),
                    params.optInt("page", 1),
                )
                // (v1.0.14 P7 F5) Per-section feed: one section per call.
                "homeFeedSection" -> homeFeedSection(
                    params.optString("providerName", ""),
                    params.optInt("page", 1),
                    params.optInt("sectionIndex", 0),
                )
                "homeFeedSections" -> ok(homeFeedSections(params.optString("providerName", "")))
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
                // (v1.0.23 D) Background mirror-probe warming (see
                // warmLinks): fire-and-forget, replies at once.
                "warmLinks" -> warmLinks(params)
                "play" -> play(params, requestId)
                "playerControl" -> playerControl(params, requestId)
                "playerTracks" -> ok(playerTracks())
                "playerAspect" -> playerAspect(params)
                "playerState" -> ok(activeStateSnapshot())
                "externalPlay" -> externalPlay(params)
                "playerStage" -> playerStage(params)
                // ---- Phase C: subtitles (E17–E18) ----
                "subtitlesSearch" -> subtitlesSearch(params)
                "subtitlesLoad" -> subtitlesLoad(params.optString("id", ""))
                // ---- Phase C: library / progress (E19–E21) ----
                "library" -> ok(library.library())
                "librarySet" -> librarySet(params)
                "progressGet" -> ok(
                    library.progressGet(
                        params.optString("title", ""),
                        params.optString("url", ""),
                        params.optString("providerName", ""),
                        params.optString("ep", ""),
                    ),
                )
                "progressSet" -> ok(
                    library.progressSet(
                        params.optString("title", ""),
                        params.optInt("posSec", 0),
                        params.optInt("durSec", 0),
                        params.optString("url", ""),
                        params.optString("providerName", ""),
                        params.optString("poster", ""),
                        params.optString("ep", ""),
                        params.optString("syn", ""),
                    ),
                )
                "progressAll" -> ok(library.progressAll())
                // ---- Phase C: downloads (E22–E25) ----
                "downloads" -> ok(downloads.downloads())
                "storageInfo" -> ok(downloads.storageInfo())
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
                "appUpdateDownload" -> ok(
                    updater.downloadUpdate(
                        params.optString("url", ""),
                        params.optBoolean("autoInstall", true),
                        params.optLong("size", -1),
                        params.optString("sha256", "").ifBlank { null },
                        params.optLong("versionCode", -1),
                        params.optString("apkSha256", "").ifBlank { null },
                    ),
                )
                "appUpdateInstall" -> ok(
                    updater.installDownloadedApk(
                        params.optString("apkSha256", "").ifBlank { null },
                        params.optLong("versionCode", -1),
                    ),
                )
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
                // (v1.0.18 P16-04) Native backup: JS calls backupSave, the
                // file is written natively into backupFolder. The old
                // blob-click path cannot save in this WebView at all.
                "backupSave" -> backupSave()
                "backupRestore" -> backupImport(params.optString("json", ""))
                "cacheClear" -> ok(cacheClear())
                // (v1.0.18 P16-06) Real cache sizes (never the old 248 MB).
                "cacheSize" -> ok(cacheSize())
                // (v1.0.18 P17-23) Custom sites now persist in Room.
                "customSitesGet" -> ok(JSONObject().put("sites", store.customSitesMap()))
                "customSitesSet" -> customSitesSet(params)
                // (v1.0.18 P16-07) Poster prefetch into the bounded cache.
                "posterPrefetch" -> ok(
                    JSONObject().put(
                        "prefetched",
                        posterCache.prefetch(
                            params.optJSONArray("urls")?.let { arr ->
                                (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
                            } ?: emptyList(),
                        ),
                    ),
                )
                // (v1.0.18 P17-21) Real settings reset (confirm lives in JS).
                "settingsReset" -> ok(settingsReset())
                // (v1.0.18 P16-13) Orphan download sweep / delete.
                "orphanSweep" -> ok(downloads.orphanSweep())
                "orphanDelete" -> ok(downloads.orphanDelete(params.optJSONArray("names") ?: JSONArray()))
                "logcat" -> ok(
                    JSONObject().put(
                        "log",
                        EngineLogger.snapshot().takeLast(params.optInt("lines", 200)).joinToString("\n"),
                    ),
                )
                // (v1.0.20 P21-B5) Dedicated perf-ring dump: epoch-ms
                // perf lines, kept even when the 200-line main ring has
                // scrolled them off.
                "perfLog" -> ok(
                    JSONObject().put(
                        "log",
                        EngineLogger.perfSnapshot().takeLast(params.optInt("lines", 500)).joinToString("\n"),
                    ),
                )
                "perfFirstPaint" -> perfFirstPaint()
                else -> {
                    EngineLogger.log("bridge", "$method -> unknown_method")
                    err("unknown_method", "Unknown method: $method")
                }
            }.also { result ->
                if (dedupKey != null && result.optBoolean("ok", false)) {
                    synchronized(readDedup) {
                        while (readDedup.size >= 64) readDedup.remove(readDedup.keys.first())
                        readDedup[dedupKey] = System.currentTimeMillis() to result
                    }
                }
            }
        } catch (e: CancellationException) {
            // (v0.2.1-i) A cancelled bridge call must stay cancelled: the
            // old catch-all swallowed it and replied "unknown" instead.
            throw e
        } catch (e: Exception) {
            // (v1.0.14 SEEK-TRACE §4a.1) Never swallow the cause again: the
            // V1.0.13 seek toast ("Engine error") hid the real exception.
            // code is now the specific internal_error (was the generic
            // "unknown"); detail carries "method: Class: message" verbatim
            // (existing err() detail slot, v1.0.2); Log.e survives the
            // 200-line EngineLogger ring buffer so device logcat names it.
            // Message stays the generic "Engine error" — no invented
            // friendly wording; the code does not know the cause until the
            // log names it. Latch behaviour unchanged.
            val cause = "${e.javaClass.simpleName}: ${e.message}"
            EngineLogger.log("bridge", "$method threw $cause")
            android.util.Log.e("ZaynPlay", "bridge $method threw $cause", e)
            err("internal_error", "Engine error", "$method: $cause")
        }
    }

    // ---- E1 ----

    private fun engineStatus(): JSONObject = JSONObject()
        .put("version", "1.0.24")
        // (v1.0.24 L ITEM 4 P0-1) Installed versionCode for the JS
        // banner gate: stored.versionCode > installed, evaluated at
        // paint time. Existing "version" string untouched.
        .put("installedVersionCode", updater.installedVersionCode())
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
    /** (v0.2.11) Mega: true when the plugin is loaded in-memory right
     * now yet registers no MainAPI — a Plugin-only plugin (its load()
     * does something else, e.g. adding repos). A stored-but-not-loaded
     * plugin is NOT noApi: its APIs simply are not in memory yet. */
    private fun pluginHasNoApi(repo: RepositoryData, p: SitePlugin): Boolean {
        if (apisForPlugin(repo, p).isNotEmpty()) return false
        val data = storedPluginData(repo, p.internalName) ?: return false
        return PluginManager.plugins.containsKey(data.filePath)
    }

    private fun providerEntries(repo: RepositoryData, wrappers: List<PluginWrapper>): JSONArray {
        val arr = JSONArray()
        // (v1.0.5 F) Samim decided 2026-11-27 ("সব দেখাও"): the repo list
        // shows ALL providers, every language. The v0.2.9 Fix G language
        // gate on these rows is REMOVED — it hid 28 of the CNC repo's 47
        // plugins (all 25 Tamil ones included) and the hidden rows could
        // not even be turned ON. His strict-language rule for CONTENT
        // (Home tabs / search) is untouched — only this LIST shows all.
        for (w in wrappers) {
            val p = w.plugin
            val apis = apisForPlugin(repo, p)
            // (v0.2.11) B F1 migration: a stored ON key under the PLUGIN
            // name (e.g. "Netmirror") is orphaned — no MainAPI has that
            // name. Expand it to the plugin's real MainAPIs (each becomes
            // its own Home tab) and clear the orphan key, so an already
            // broken install heals without remove/reinstall.
            if (apis.isNotEmpty() && p.name !in apis.map { it.name }) {
                val rid = repoId(repo.url)
                if (store.isProviderOn(rid, p.name)) {
                    for (api in apis) store.setProviderOn(rid, api.name, true)
                    store.setProviderOn(rid, p.name, false)
                }
            }
            if (apis.isEmpty()) {
                arr.put(providerRowJson(repo, p, name = p.name, api = null, notLoaded = true, noApi = pluginHasNoApi(repo, p)))
            } else {
                for (api in apis) {
                    arr.put(providerRowJson(repo, p, name = api.name, api = api, notLoaded = false))
                }
            }
        }
        return arr
    }

    private fun providerRowJson(
        repo: RepositoryData,
        p: SitePlugin,
        name: String,
        api: MainAPI?,
        notLoaded: Boolean,
        noApi: Boolean = false,
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
            .put("healthDetail", store.providerHealthDetail(id, name) ?: JSONObject.NULL)
            .put("hasSettings", (loadedPlugin as? Plugin)?.openSettings != null)
            .put("mainUrl", api?.mainUrl ?: JSONObject.NULL)
            .put("types", JSONArray(p.tvTypes ?: emptyList<String>()))
            // (v0.2.11) Target 16: full provider details, same fields as
            // upstream PluginAdapter rows — original icon, description,
            // authors, version (already) and file size in bytes.
            .put("iconUrl", p.iconUrl ?: JSONObject.NULL)
            .put("description", p.description ?: JSONObject.NULL)
            .put("authors", JSONArray(p.authors))
            .put("fileSize", p.fileSize ?: JSONObject.NULL)
            .put("notLoaded", notLoaded)
            .put("noApi", noApi)
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
                    return err(code, providerFailureMessage(code, providerName) + detailText, failureDetail)
                }
            }
            // (v0.2.11) B F1: ON-ing a multi-API plugin ONs each of its
            // MainAPIs (NetMirror: Netflix, Disney Plus, Hotstar, Prime
            // Video — each its own Home tab, per the 2026-11-25 rule (c)).
            // The plugin-name key is cleared so it cannot build an
            // unresolvable Home tab.
            val apis = apisForPlugin(repo, p)
            if (apis.isNotEmpty() && providerName !in apis.map { it.name }) {
                for (api in apis) {
                    store.setProviderOn(repoId, api.name, true)
                    store.setProviderAutoOff(repoId, api.name, false)
                }
                store.setProviderOn(repoId, providerName, false)
                EngineLogger.log("bridge", "providerSetOn ON $providerName expanded to ${apis.map { it.name }}")
            } else {
                store.setProviderOn(repoId, providerName, true)
                store.setProviderAutoOff(repoId, providerName, false)
            }
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
        // (v1.0.19 BUG 6) Effective ON for the toggled row: ON-ing a
        // multi-API plugin-name row ONs each real API and clears the
        // plugin-name key, so re-reading that cleared key would report
        // false while the provider is genuinely ON. The reply must
        // reflect whether any expanded API is ON. (The OFF branch has
        // no expansion, so the plain re-read stays correct there.)
        val apisNow = apisForPlugin(repo, p)
        val effectiveOn = if (on && apisNow.isNotEmpty() && providerName !in apisNow.map { it.name }) {
            apisNow.any { store.isProviderOn(repoId, it.name) }
        } else {
            store.isProviderOn(repoId, providerName)
        }
        return ok(
            JSONObject()
                .put("repoId", repoId)
                .put("providerName", providerName)
                .put("on", effectiveOn)
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
        // (v0.2.11) Mega: loaded fine, registers no MainAPI — it adds
        // repositories instead of streaming video.
        code == "plugin_only" -> "$providerName: loaded — adds repositories, no video provider"
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
            recordHealth(repoId, providerName, JSONObject().put("load", false).put("linkResolution", false).put("lastError", "down"))
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
        var feed = false
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
                // (v0.2.11) Mega: a Plugin-only plugin (no MainAPI — e.g.
                // MegaProvider, whose Plugin.load() adds repos) loads
                // SUCCESSFULLY (downloaded=true, no code) yet registers
                // no API. That is not a load failure: report load=true
                // with the distinct plugin_only code so the row shows
                // "loads, adds repos" instead of three red crosses.
                if (downloaded && downloadCode == null) {
                    load = true
                    error = "plugin_only"
                    EngineLogger.log("bridge", "providerTest $providerName: plugin-only (no MainAPI) — loaded OK")
                } else {
                    error = downloadCode ?: "load_failed"
                    EngineLogger.log("bridge", "providerTest $providerName failed: $error (downloaded=$downloaded)")
                }
            }
        }
        val apiForCheck = api
        if (apiForCheck != null) {
            try {
                withTimeout(45_000) {
                    error = null
                    detail = null
                    // (v0.2.9) Fix C: every false outcome below now carries
                    // a non-null error code AND a detail string. Before,
                    // content/video could be false with error == null (a
                    // site answering with no usable content, a page with no
                    // links, a loadLinks exception swallowed to `false`),
                    // which the row rendered as "unknown reason".
                    // (v0.2.11) C-1/C-4: the feed is probed on its own
                    // (getMainPage only, no search fallback). A provider
                    // WITH a home page passes Content only from its feed;
                    // search is a separate signal for providers WITHOUT a
                    // home page. load=true only once the probe has actually
                    // returned — a timeout no longer reports "Load ✓".
                    val feedProbe = feedContentItem(apiForCheck)
                    load = true
                    feed = feedProbe.item != null
                    val probe = if (apiForCheck.hasMainPage) feedProbe
                        else searchContentItem(apiForCheck, feedProbe.probeWord)
                    val firstItem = probe.item
                    val transportFailed = probe.transportFailed
                    val contentDetail = probe.detail
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
                        // (v0.2.11) C-5: try up to the first 3 episodes'
                        // data, not just the first — one dead episode no
                        // longer condemns a working provider.
                        val datas = (loaded as? Resource.Success)?.value?.let(::loadDataCandidates) ?: emptyList()
                        if (datas.isNotEmpty()) {
                            var gotLink = false
                            var linksError: String? = null
                            for (data in datas) {
                                val links = APIRepository(apiForCheck).loadLinks(data, false, { _: SubtitleFile -> }) { _: ExtractorLink -> gotLink = true }
                                if (links.ok && gotLink) break
                                if (linksError == null) linksError = links.error
                            }
                            video = gotLink
                            if (!video) {
                                if (error == null) error = "no_links"
                                if (detail == null) detail = linksError
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
        // (v1.0.17 P15-01) Feed the Part H record: load / data-validation
        // (content) / link-resolution (links found). Stream validation and
        // playback are NOT claimed here — the Test never validates a stream
        // or plays anything. A success clears the last error.
        recordHealth(repoId, providerName, JSONObject()
            .put("load", load)
            .put("dataValidation", content)
            .put("linkResolution", video)
            .put("lastError", if (error != null) (detail ?: error) else JSONObject.NULL))
        return ok(
            JSONObject()
                .put("repoId", repoId)
                .put("providerName", providerName)
                .put("load", load)
                .put("content", content)
                .put("feed", feed)
                .put("video", video)
                .put("links", video)
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

    /** (v1.0.17 P15-01) JS-side playback verdicts: only real playback may
     * set playback success. playback=true stamps playback + the last
     * successful-playback time and the summary "ok"; playback=false
     * records the failure + last error but leaves the summary alone (one
     * bad link is not proof the provider is dead). */
    private fun providerHealthRecord(params: JSONObject): JSONObject {
        val repoId = params.optString("repoId", "")
        val name = params.optString("providerName", "")
        if (repoId.isBlank() || name.isBlank()) return err("invalid_input", "Missing provider")
        val update = JSONObject()
        if (params.has("playback") && !params.isNull("playback")) {
            val okPlay = params.optBoolean("playback", false)
            update.put("playback", okPlay)
            if (okPlay) {
                update.put("lastSuccessAtMs", System.currentTimeMillis())
                store.setProviderHealth(repoId, name, "ok")
            }
        }
        if (params.has("lastError") && !params.isNull("lastError")) update.put("lastError", params.optString("lastError"))
        if (params.has("playerPrep") && !params.isNull("playerPrep")) update.put("playerPrep", params.optBoolean("playerPrep", false))
        store.updateProviderHealthDetail(repoId, name, update)
        return ok(JSONObject().put("repoId", repoId).put("providerName", name))
    }

    /** (v1.0.17 P15-01/P15-08) Resolve a provider NAME to its repo id for
     * health recording from the play path (which only carries the name).
     * Non-suspend by construction: providerDiag (a plain fun) calls this
     * via recordHealthByName, so the suspend
     * RepositoryManager.getRepoPlugins cannot be used here (it broke CI
     * run 46093356609: "Suspend function 'getRepoPlugins' should be called
     * only from a coroutine or another suspend function"). The live
     * MainAPI set (APIHolder.allProviders) carries sourcePlugin paths,
     * matched against each repo's sanitized folder — the same pattern
     * apisForRepo uses (E:4184). A plugin with no MainAPI cannot produce
     * a play-path provider name, so no plugin-name fallback is needed. */
    private fun repoIdForProviderName(name: String): String? {
        for (api in APIHolder.allProviders) {
            if (api.name != name) continue
            val pluginPath = api.sourcePlugin ?: continue
            for (repo in RepositoryManager.getRepositories()) {
                if (pluginPath.contains("/${PluginManager.getPluginSanitizedFileName(repo.url)}/")) return repoId(repo.url)
            }
        }
        return null
    }

    private fun recordHealthByName(name: String, update: JSONObject) {
        val repoId = repoIdForProviderName(name) ?: return
        store.updateProviderHealthDetail(repoId, name, update)
    }

    private fun recordHealth(repoId: String, name: String, update: JSONObject) =
        store.updateProviderHealthDetail(repoId, name, update)

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
    // (v0.2.11) C-1: the Content probe is split in two so the test and
    // the Home feed exercise the SAME path. feedContentItem is getMainPage
    // alone (exactly what homeFeed does); searchContentItem is the search
    // fallback, used only for providers with no home page. firstContentItem
    // keeps the old combined behaviour for the health probe.
    private data class ContentProbe(
        val item: SearchResponse?,
        val transportFailed: Boolean,
        val detail: String?,
        val probeWord: String?,
    )

    private suspend fun feedContentItem(api: MainAPI): ContentProbe {
        var transportFailed = false
        var failureDetail: String? = null
        if (!api.hasMainPage) return ContentProbe(null, false, null, null)
        val feed = try {
            APIRepository(api).getMainPage(1)
        } catch (e: CancellationException) {
            // (v0.2.9) Fix D: never launder a cancellation (incl. the
            // withTimeout kill) into a transport failure.
            throw e
        } catch (e: Exception) {
            transportFailed = true
            failureDetail = "${e.javaClass.simpleName}: ${e.message ?: "no message"}".take(200)
            null
        }
        if (feed is Resource.Failure) {
            if (feed.isNetworkError) transportFailed = true
            failureDetail = feed.errorString
        }
        val sections = (feed as? Resource.Success<List<HomePageResponse?>>)?.value
        val probeWord = probeWordFromSections(sections)
        val first = sections
            ?.flatMap { it?.items ?: emptyList() }
            ?.firstOrNull { it.list.isNotEmpty() }
            ?.list?.firstOrNull()
        return ContentProbe(first, transportFailed, failureDetail, probeWord)
    }

    private suspend fun searchContentItem(api: MainAPI, probeWord: String?): ContentProbe {
        var transportFailed = false
        var failureDetail: String? = null
        // (v0.2.11) C-3: try EVERY word unless the failure is a transport
        // failure; a site rejecting one query no longer ends the probe.
        // The failing word is recorded in the detail.
        val words = (listOfNotNull(probeWord) + listOf("over", "iron", "guy")).distinct()
        for (word in words) {
            val res = try {
                APIRepository(api).search(word, 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transportFailed = true
                failureDetail = "$word: ${e.javaClass.simpleName}: ${e.message ?: "no message"}".take(200)
                break
            }
            if (res is Resource.Failure) {
                failureDetail = "$word: ${res.errorString}"
                if (res.isNetworkError) {
                    transportFailed = true
                    break
                }
                continue
            }
            val item = (res as? Resource.Success<SearchResponseList>)?.value?.items?.firstOrNull()
            if (item != null) return ContentProbe(item, false, null, probeWord)
        }
        return ContentProbe(null, transportFailed, failureDetail, probeWord)
    }

    private suspend fun firstContentItem(api: MainAPI): ContentProbe {
        val feedProbe = feedContentItem(api)
        if (feedProbe.item != null) return feedProbe
        // Home page exists but came back empty, or no home page: fall
        // through to the search check instead of failing a working provider.
        val searchProbe = searchContentItem(api, feedProbe.probeWord)
        if (searchProbe.item != null) return searchProbe
        // Keep the feed's transport signal when the search found nothing.
        return ContentProbe(
            null,
            feedProbe.transportFailed || searchProbe.transportFailed,
            searchProbe.detail ?: feedProbe.detail,
            feedProbe.probeWord,
        )
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
                // (v0.2.11) C-3: unicode letters, not ASCII only — a
                // Bengali/Hindi catalogue yields its own title words.
                words += list.name.split(Regex("""[^\p{L}]+""")).filter { it.length >= 3 }
                list.list.forEach { item ->
                    words += item.name.split(Regex("""[^\p{L}]+""")).filter { it.length >= 3 }
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
        // (v1.0.17 P15-07) Bounded pool (4 in flight, design §5.3) instead
        // of sequential chunks, with a 100 s overall cap (inside the JS
        // 120 s wait) and partial results: one hung provider can no longer
        // make the whole sweep report nothing.
        var partial = false
        var deferreds: List<Deferred<JSONObject>> = emptyList()
        try {
            withTimeout(100_000) {
                coroutineScope {
                    val sem = Semaphore(4)
                    val jobs = filtered.map { t ->
                        async { sem.withPermit { healthProbe(t) } }
                    }
                    deferreds = jobs
                    jobs.awaitAll()
                }
            }
        } catch (e: TimeoutCancellationException) {
            partial = true
            EngineLogger.log("bridge", "health sweep timed out — partial results")
        }
        for (d in deferreds) {
            if (!d.isCompleted) continue
            val probe = try { d.getCompleted() } catch (e: Exception) { continue }
            results.put(probe)
            when (probe.optString("change", "")) {
                "off" -> autoOff.put(probe.optString("providerName", ""))
                "on" -> autoOn.put(probe.optString("providerName", ""))
            }
        }
        return ok(
            JSONObject()
                .put("results", results)
                .put("autoOff", autoOff)
                .put("autoOn", autoOn)
                .put("partial", partial)
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
        var loadOk: Boolean? = null
        var probeError: String? = null
        try {
            withTimeout(probeTimeoutMs) {
                val probe = firstContentItem(api)
                healthy = probe.item != null
                transportFailed = probe.transportFailed
                // (v1.0.17 P15-04) Probe "ok" must mean more than "the home
                // page answered": load the first item too, inside the same
                // timeout box, so ok means content actually loads.
                if (probe.item != null && !transportFailed) {
                    val loaded = APIRepository(api).load(probe.item.url)
                    loadOk = (loaded as? Resource.Success<*>) != null
                    if (loaded is Resource.Failure) {
                        if (loaded.isNetworkError) transportFailed = true
                        else probeError = "load_failed"
                    }
                }
                if (loadOk == false) healthy = false
                if (probe.item == null && !transportFailed) probeError = "no_content"
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
        // (v1.0.17 P15-01) Feed the Part H record. Skipped / transport-
        // failed probes return earlier and record nothing (no verdict).
        recordHealth(t.repoId, name, JSONObject()
            .put("load", loadOk ?: JSONObject.NULL)
            .put("dataValidation", healthy)
            .put("lastError", if (healthy) JSONObject.NULL else (probeError ?: "no_content"))
            .put("summary", if (healthy) "ok" else "fail"))
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
        // (v1.0.14 P5 F4) Feed-lifecycle logging: start + EVERY return
        // path with elapsed ms + counts, so a slow/empty feed is visible
        // in the log without guessing which provider stalled.
        val hfT0 = System.currentTimeMillis()
        fun hfMs() = System.currentTimeMillis() - hfT0
        EngineLogger.log("bridge", "homeFeed start provider=$providerName page=$page")
        val api = resolveApi(providerName, null)
            ?: run {
                EngineLogger.log("bridge", "homeFeed provider_not_found provider=$providerName ms=${hfMs()}")
                return err("provider_not_found", "Provider not found")
            }
        val feed = try {
            APIRepository(api).getMainPage(page.coerceAtLeast(1))
        } catch (e: CancellationException) {
            // (v0.2.11) A P7: a timeout is a timeout, never a network
            // error — rethrow like providerTest/firstContentItem do.
            throw e
        } catch (e: Exception) {
            EngineLogger.log("bridge", "homeFeed network_error provider=$providerName ms=${hfMs()} ${e.javaClass.simpleName}: ${e.message}")
            return err("network_error", "Could not load the home feed")
        }
        val sections = (feed as? Resource.Success<List<HomePageResponse?>>)?.value
            ?: run {
                EngineLogger.log("bridge", "homeFeed network_error provider=$providerName ms=${hfMs()} (not Success)")
                return err("network_error", "Could not load the home feed")
            }
        val arr = JSONArray()
        var hasNext = false
        var totalItems = 0
        // (v1.0.23 D) Title diagnostic counters (see loop below).
        var titledItems = 0
        var firstTitleLen = -1
        for (resp in sections) {
            if (resp == null) continue
            hasNext = hasNext || resp.hasNext
            for (list in resp.items) {
                val items = JSONArray()
                for (item in list.list) {
                    // (v1.0.23 D) Title diagnostic: count provider titles
                    // that arrive non-blank (TRACE-1.0.23-TITLE §5.1) —
                    // presence/length only, never the title text.
                    if (firstTitleLen < 0) firstTitleLen = item.name.length
                    if (item.name.isNotBlank()) titledItems++
                    items.put(searchItemJson(item))
                    totalItems++
                }
                arr.put(JSONObject().put("name", list.name).put("items", items))
            }
        }
        // (v1.0.2) Empty success is a failure (upstream HomeViewModel:
        // "No homepage responses") — an ok feed with zero items must
        // surface as no_content, never render blank. This is the common
        // funnel for every provider: a Cloudflare challenge page parses
        // to zero selectors, so the provider still returns Success with
        // empty lists, and the old code rendered nothing with no error.
        if (totalItems == 0) {
            EngineLogger.log("bridge", "homeFeed no_content provider=$providerName ms=${hfMs()} sections=${arr.length()}")
            return err("no_content", "No homepage responses")
        }
        EngineLogger.log("bridge", "homeFeed ok provider=$providerName ms=${hfMs()} sections=${arr.length()} items=$totalItems titled=$titledItems firstTitleLen=$firstTitleLen hasNext=$hasNext")
        // (v1.0.20 P21-B4) Home settle = memory sample point; fetchMs is
        // returned so JS can log fetch + render separately (N1).
        logMemSample("home")
        return ok(
            JSONObject()
                .put("provider", JSONObject().put("name", api.name).put("mainUrl", api.mainUrl).put("lang", api.lang))
                .put("sections", arr)
                .put("hasNext", hasNext)
                .put("fetchMs", hfMs())
        )
    }

    // (v1.0.14 P7 F5) Per-section feed (option (b)): fetch exactly ONE
    // section via APIRepository.getMainPage(page, nameIndex). Same
    // response shape as homeFeed (provider, sections with ONE entry,
    // hasNext) plus sectionIndex + sectionCount so JS can place the
    // rail and know when the feed is complete. Same error contract and
    // same F4 start/end logging per section. An out-of-range index is
    // invalid_input — getMainPage would otherwise silently fall back to
    // fetching ALL sections (AR:154-165), which must never happen here.
    private suspend fun homeFeedSection(providerName: String, page: Int, sectionIndex: Int): JSONObject {
        val hfT0 = System.currentTimeMillis()
        fun hfMs() = System.currentTimeMillis() - hfT0
        EngineLogger.log("bridge", "homeFeedSection start provider=$providerName page=$page sectionIndex=$sectionIndex")
        val api = resolveApi(providerName, null)
            ?: run {
                EngineLogger.log("bridge", "homeFeedSection provider_not_found provider=$providerName ms=${hfMs()} sectionIndex=$sectionIndex")
                return err("provider_not_found", "Provider not found")
            }
        if (sectionIndex !in api.mainPage.indices) {
            EngineLogger.log("bridge", "homeFeedSection invalid_input provider=$providerName ms=${hfMs()} sectionIndex=$sectionIndex sectionCount=${api.mainPage.size}")
            return err("invalid_input", "No such section")
        }
        val sectionCount = api.mainPage.size
        val feed = try {
            APIRepository(api).getMainPage(page.coerceAtLeast(1), sectionIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EngineLogger.log("bridge", "homeFeedSection network_error provider=$providerName ms=${hfMs()} sectionIndex=$sectionIndex ${e.javaClass.simpleName}: ${e.message}")
            return err("network_error", "Could not load the home feed")
        }
        val sections = (feed as? Resource.Success<List<HomePageResponse?>>)?.value
            ?: run {
                EngineLogger.log("bridge", "homeFeedSection network_error provider=$providerName ms=${hfMs()} sectionIndex=$sectionIndex (not Success)")
                return err("network_error", "Could not load the home feed")
            }
        val arr = JSONArray()
        var hasNext = false
        var totalItems = 0
        // (v1.0.23 D) Title diagnostic counters (see loop below).
        var titledItems = 0
        var firstTitleLen = -1
        for (resp in sections) {
            if (resp == null) continue
            hasNext = hasNext || resp.hasNext
            for (list in resp.items) {
                val items = JSONArray()
                for (item in list.list) {
                    // (v1.0.23 D) Title diagnostic: count provider titles
                    // that arrive non-blank (TRACE-1.0.23-TITLE §5.1) —
                    // presence/length only, never the title text.
                    if (firstTitleLen < 0) firstTitleLen = item.name.length
                    if (item.name.isNotBlank()) titledItems++
                    items.put(searchItemJson(item))
                    totalItems++
                }
                arr.put(JSONObject().put("name", list.name).put("items", items))
            }
        }
        if (totalItems == 0) {
            EngineLogger.log("bridge", "homeFeedSection no_content provider=$providerName ms=${hfMs()} sectionIndex=$sectionIndex sections=${arr.length()}")
            return err("no_content", "No homepage responses")
        }
        EngineLogger.log("bridge", "homeFeedSection ok provider=$providerName ms=${hfMs()} sectionIndex=$sectionIndex sections=${arr.length()} items=$totalItems titled=$titledItems firstTitleLen=$firstTitleLen hasNext=$hasNext")
        return ok(
            JSONObject()
                .put("provider", JSONObject().put("name", api.name).put("mainUrl", api.mainUrl).put("lang", api.lang))
                .put("sections", arr)
                .put("hasNext", hasNext)
                .put("sectionIndex", sectionIndex)
                .put("sectionCount", sectionCount)
        )
    }

    // (v1.0.14 P7 F5) Section list: the canonical section order + the
    // sequential flag, so JS can create placeholder rails in the right
    // order and honour a sequential provider's contract BEFORE firing
    // any per-section call. sectionCount <= 1 means "use the whole-feed
    // path" (Disney rule) — JS never routes a single-section provider
    // through homeFeedSection.
    private fun homeFeedSections(providerName: String): JSONObject {
        val api = resolveApi(providerName, null)
            ?: return err("provider_not_found", "Provider not found")
        val arr = JSONArray()
        api.mainPage.forEachIndexed { index, data ->
            arr.put(JSONObject().put("index", index).put("name", data.name).put("data", data.data))
        }
        return JSONObject()
            .put("sections", arr)
            .put("sectionCount", api.mainPage.size)
            .put("sequential", api.sequentialMainPage)
            .put("sequentialDelayMs", api.sequentialMainPageDelay)
    }

    private suspend fun search(query: String, providerName: String?, page: Int): JSONObject {
        if (query.isBlank()) return err("invalid_input", "Enter a search term")
        // (v1.0.20 P21-B2) Fan-out timing: total + per-provider ms.
        val searchT0 = System.currentTimeMillis()
        fun searchMs() = System.currentTimeMillis() - searchT0
        if (providerName != null) {
            val api = resolveApi(providerName, null)
                ?: return err("provider_not_found", "Provider not found")
            val res = try {
                APIRepository(api).search(query, page.coerceAtLeast(1))
            } catch (e: Exception) {
                EngineLogger.log("bridge", "search threw ${e.javaClass.simpleName}: ${e.message}")
                return err("network_error", "Search failed")
            }
            val list = (res as? Resource.Success<SearchResponseList>)?.value
                ?: return err("network_error", "Search failed")
            val arr = JSONArray()
            var titledItems = 0
            var firstTitleLen = -1
            for (item in list.items) {
                if (firstTitleLen < 0) firstTitleLen = item.name.length
                if (item.name.isNotBlank()) titledItems++
                arr.put(searchItemJson(item))
            }
            EngineLogger.logPerf("perf", "search ok query=$query ms=${searchMs()} providers=1/1 timedOut=0 items=${arr.length()} titled=$titledItems firstTitleLen=$firstTitleLen")
            return ok(
                JSONObject()
                    .put("results", arr)
                    .put("hasNext", list.hasNext)
                    .put("partial", false)
                    .put("searchMs", searchMs())
            )
        }
        // All-provider fan-out over ON providers only, capped concurrency 8,
        // 60 s overall (v0.2.11 C-10: the 15 s cap killed every provider on
        // a slow network before any of them could answer).
        // Targets are (repoId, name) pairs — same-named providers in
        // different repos no longer collapse onto the first match.
        val ordered = onProviderPairsOrdered()
        val results = JSONArray()
        val perProvider = JSONArray()
        // (v0.2.1-n) Per-provider resolution failures are surfaced, not
        // silently swallowed as partial=true with zero results.
        val failures = JSONArray()
        var partial = false
        var hasNext = false
        // (v1.0.17 P14-01/P14-02) No more sequential chunks: every provider
        // is one async job under a Semaphore(8) permit, so one slow
        // provider cannot starve later providers, and results are
        // collected per provider from the finished jobs — a timeout keeps
        // what was already fetched instead of discarding the whole
        // in-flight chunk (the V1.0.14 not_attempted fix covered only
        // later chunks). The 60 s overall cap is unchanged.
        val started = Collections.synchronizedSet(mutableSetOf<String>())
        var deferreds: List<Deferred<Triple<String, SearchResponseList?, String?>>> = emptyList()
        try {
            withTimeout(60_000) {
                coroutineScope {
                    val sem = Semaphore(8)
                    val jobs = ordered.map { (repoId, name) ->
                        async<Triple<String, SearchResponseList?, String?>> {
                            sem.withPermit {
                                started.add(name)
                                // (v1.0.20 P21-B2) Per-provider fan-out ms.
                                val provT0 = System.currentTimeMillis()
                                fun provMs() = System.currentTimeMillis() - provT0
                                val api = resolveApi(name, null, repoId)
                                    ?: run {
                                        EngineLogger.logPerf("perf", "search provider=$name ms=${provMs()} items=unresolved")
                                        return@async Triple(name, null, "unresolved")
                                    }
                                val res = try {
                                    APIRepository(api).search(query, page.coerceAtLeast(1))
                                } catch (e: Exception) {
                                    EngineLogger.logPerf("perf", "search provider=$name ms=${provMs()} items=error")
                                    return@async Triple(name, null, "error")
                                }
                                val list = (res as? Resource.Success<SearchResponseList>)?.value
                                    ?: run {
                                        EngineLogger.logPerf("perf", "search provider=$name ms=${provMs()} items=error")
                                        return@async Triple(name, null, "error")
                                    }
                                EngineLogger.logPerf("perf", "search provider=$name ms=${provMs()} items=${list.items.size}")
                                Triple(name, list, null)
                            }
                        }
                    }
                    deferreds = jobs
                    jobs.awaitAll()
                }
            }
        } catch (e: TimeoutCancellationException) {
            partial = true
            EngineLogger.log("bridge", "search fan-out timed out")
        }
        // Collect per provider from finished jobs; unfinished jobs are
        // timed_out (started, in flight) or not_attempted (never started).
        // Every ordered provider lands in perProvider or failures — none
        // vanishes.
        val byName = mutableMapOf<String, Triple<String, SearchResponseList?, String?>>()
        for (d in deferreds) {
            if (!d.isCompleted) continue
            try {
                val t = d.getCompleted()
                byName[t.first] = t
            } catch (e: Exception) { /* cancelled job: handled below via started */ }
        }
        for ((_, name) in ordered) {
            val done = byName[name]
            if (done != null) {
                val (_, list, reason) = done
                if (list == null) {
                    partial = true
                    failures.put(JSONObject().put("provider", name).put("reason", reason ?: "error"))
                } else {
                    hasNext = hasNext || list.hasNext
                    perProvider.put(JSONObject().put("provider", name).put("count", list.items.size))
                    for (item in list.items) results.put(searchItemJson(item))
                }
            } else if (started.contains(name)) {
                partial = true
                failures.put(JSONObject().put("provider", name).put("reason", "timed_out"))
            } else {
                partial = true
                failures.put(JSONObject().put("provider", name).put("reason", "not_attempted"))
            }
        }
        // (v1.0.23 D) Title diagnostic for fan-out search: count
        // non-blank titles in the assembled results (presence and
        // lengths only, never the title text).
        var titledSearch = 0
        var firstSearchTitleLen = -1
        for (i in 0 until results.length()) {
            val rt = results.optJSONObject(i)?.optString("title", "") ?: ""
            if (firstSearchTitleLen < 0) firstSearchTitleLen = rt.length
            if (rt.isNotBlank()) titledSearch++
        }
        // (v1.0.20 P21-B2) Fan-out totals: settled providers, timed-out
        // count, item count — the numbers the on-phone report needs.
        var timedOut = 0
        for (i in 0 until failures.length()) {
            if (failures.optJSONObject(i)?.optString("reason") == "timed_out") timedOut++
        }
        EngineLogger.logPerf(
            "perf",
            "search ok query=$query ms=${searchMs()} providers=${perProvider.length()}/${ordered.size} timedOut=$timedOut items=${results.length()} titled=$titledSearch firstTitleLen=$firstSearchTitleLen",
        )
        return ok(
            JSONObject()
                .put("results", results)
                .put("hasNext", hasNext)
                .put("perProvider", perProvider)
                .put("failures", failures)
                .put("partial", partial)
                .put("searchMs", searchMs())
        )
    }

    private suspend fun details(url: String, providerName: String): JSONObject {
        if (url.isBlank()) return err("invalid_input", "Missing details link")
        val api = resolveApi(providerName, null)
            ?: return err("provider_not_found", "Provider not found")
        // (v1.0.14 P4 rank 8) Native 25 s timeout: without it a hung
        // load() holds the bridge until the JS 30 s timer fires, and the
        // native job then finishes into a deleted pending entry.
        // CancellationException is rethrown so MainActivity's cancel path
        // still replies (the M.call timeout reply path).
        val _detailsT0 = System.currentTimeMillis()
        val loaded = try {
            withTimeout(25_000) { APIRepository(api).load(url) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            EngineLogger.log("bridge", "details threw ${e.javaClass.simpleName}: ${e.message}")
            return err("network_error", "Could not load details")
        }
        val resp = (loaded as? Resource.Success<LoadResponse>)?.value
            ?: return err("network_error", "Could not load details")
        // (v1.0.23 D) Title diagnostic: does the provider's load()
        // supply a real name (TRACE-1.0.23-TITLE §5.4)? Presence and
        // length only — never the title text, never a URL.
        EngineLogger.log("bridge", "details titleProbe provider=$providerName titlePresent=${resp.name.isNotBlank()} titleLen=${resp.name.length} elapsedMs=${System.currentTimeMillis() - _detailsT0}")
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
                    // (v1.0.19 BUG 1) The DNS mode is baked into baseClient
                    // at build time (RequestsHelper.buildDefaultClient reads
                    // EngineApp.dnsMode once), so a mid-session DNS change
                    // had no network effect until restart. Rebuild the
                    // client here so the new DNS applies at once. The
                    // jsDelivr flag is live-read, but the same rebuild
                    // keeps both paths consistent. A rebuild failure must
                    // never fail the settings write itself.
                    try {
                        app.initClient(activity)
                    } catch (t: Throwable) {
                        android.util.Log.w("ZaynEngine", "HTTP client rebuild failed after $key change", t)
                    }
                }
                // (v1.0.18 P17-20) A backupFreq/backupFolder change takes
                // effect at once — the next auto-backup uses the new
                // cadence/folder instead of waiting for a restart.
                if (key == "backupFreq" || key == "backupFolder") {
                    ZaynBackupScheduler.schedule(activity)
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
        val dead = JSONArray()
        for (repo in RepositoryManager.getRepositories()) {
            val id = repoId(repo.url)
            val wrappers = when (val result = RepositoryManager.getRepoPluginsResult(repo, manual)) {
                is RepoPluginsResult.Ok -> result.wrappers
                // (v0.2.11) C-13: a dead repo is recorded, not silently
                // skipped — the UI can mark it dead instead of showing
                // stale providers with no marker.
                is RepoPluginsResult.Err -> { dead.put(repo.name); continue }
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
                .put("dead", dead)
                .put("upToDate", updated.length() == 0)
        )
    }

    // ---- (v1.0.23 D) CNC mirror-probe warming + cold-probe detection ----
    // TRACE-1.0.23-LOADLINKS (Worker B, dex-verified v116): every CNC
    // mirror provider's loadLinks starts with UtilsKt.resolveApiUrl(),
    // a sequential probe of 25 hard-coded mobidetect* mirrors sharing
    // ONE static + SharedPreferences cache ("NetflixMirrorPrefs" /
    // "newtv_api_base", 24 h). Feeds/details never call it, so the
    // first play after a fresh install pays the whole probe inside
    // the 30 s play budget — and the V1.0.22 retry restarts it from
    // candidate #1. Warming runs that same loadLinks in the background
    // on details open, while the user reads, so the cache is populated
    // before the play tap. The plugin gives no resume hook and no
    // resolve-only entry point, so warming IS a background loadLinks
    // (links discarded, never shown); the retry is left exactly as
    // V1.0.22 wrote it and the warm cache is what makes it moot.

    /** True when this api is one of the CNC mirror providers (dex v116:
     * NetflixMirrorProvider / PrimeVideoMirrorProvider /
     * HotStarMirrorProvider / DisneyStudioProvider, incl. the Marvel /
     * Star Wars / Pixar studio instances, which are the same class).
     * Plugin classes are loaded at runtime, so this matches on class
     * names (walking superclasses for DisneyPlusProvider, which
     * extends DisneyStudioProvider) instead of referencing them. */
    private fun isMirrorProvider(api: MainAPI): Boolean {
        var c: Class<*>? = api::class.java
        while (c != null && c != Any::class.java) {
            val n = c.name
            if (n.contains("MirrorProvider") || n.contains("DisneyStudioProvider")) return true
            c = c.superclass
        }
        return false
    }

    /** True when the provider's shared API-base cache is absent or
     * stale (the same 24 h rule resolveApiUrl uses: fresh iff
     * now - timestamp < 86_400_000). Reads the plugin's own
     * SharedPreferences on the application context — the same store
     * NetflixMirrorStorage.init() opens. Never throws into the play
     * path: an unreadable store reads as cold. */
    private fun isMirrorApiBaseCold(): Boolean {
        return try {
            val prefs = activity.applicationContext.getSharedPreferences("NetflixMirrorPrefs", Context.MODE_PRIVATE)
            val base = prefs.getString("newtv_api_base", null)
            val ts = prefs.getLong("newtv_api_base_timestamp", 0L)
            base.isNullOrBlank() || System.currentTimeMillis() - ts >= 86_400_000L
        } catch (e: Exception) {
            true
        }
    }

    /** Fire-and-forget mirror-probe warming for one details page.
     * JS calls this once when the details reply lands, with the
     * title's loadLinks data (first episode data for series, the
     * details data for movies). Only mirror providers are warmed —
     * any other provider has no probe to warm. Runs on bridgeScope,
     * outside playMutex: it must never block a play, and a play tap
     * mid-warm behaves exactly as today. The returned JSON only says
     * whether a job was launched; the outcome is in the log. */
    private fun warmLinks(params: JSONObject): JSONObject {
        val providerName = params.optString("providerName", "")
        if (providerName.isBlank()) return ok(JSONObject().put("warming", false))
        if (warmLinksDone.get()) {
            EngineLogger.log("bridge", "warmLinks already warmed this process provider=$providerName — skipping")
            return ok(JSONObject().put("warming", false))
        }
        if (!warmLinksActive.compareAndSet(false, true)) {
            EngineLogger.log("bridge", "warmLinks already warming provider=$providerName — skipping duplicate")
            return ok(JSONObject().put("warming", false))
        }
        bridgeScope.launch {
            val warmT0 = System.currentTimeMillis()
            try {
                val api = resolveApi(providerName, null)
                if (api == null) {
                    EngineLogger.log("bridge", "warmLinks provider_not_found provider=$providerName")
                    return@launch
                }
                if (!isMirrorProvider(api)) {
                    EngineLogger.log("bridge", "warmLinks skip provider=$providerName (not a mirror provider — no probe to warm)")
                    return@launch
                }
                val data = resolveLinkData(params, api)
                if (data.isNullOrBlank()) {
                    EngineLogger.log("bridge", "warmLinks no_data provider=$providerName")
                    return@launch
                }
                val warmDataForLinks = normalizeLoadData(data, params.optString("title", "")) ?: data
                var warmLinksFound = 0
                val result = APIRepository(api).loadLinks(warmDataForLinks, false, {}) { warmLinksFound++ }
                val warmMs = System.currentTimeMillis() - warmT0
                // A returned call means resolveApiUrl finished: ok, or a
                // later-stage error (otp/player) — either way the base
                // is cached. Only "Failed to resolve" means the probe
                // itself found no base, so a later details open may
                // retry warming.
                val probeFailed = result.error?.contains("Failed to resolve") == true
                if (!probeFailed) warmLinksDone.set(true)
                EngineLogger.log("bridge", "warmLinks done provider=$providerName ms=$warmMs ok=${result.ok} links=$warmLinksFound error=${result.error ?: "-"} warmed=${!probeFailed}")
            } catch (e: CancellationException) {
                // Best-effort background job: a cancelled warm (its own
                // 120 s vendored timeout, or a dying scope) is logged
                // and dropped, never rethrown into the bridge — the
                // play path has its own timeouts and never depends on
                // this job.
                EngineLogger.log("bridge", "warmLinks cancelled provider=$providerName ms=${System.currentTimeMillis() - warmT0}")
            } catch (e: Exception) {
                EngineLogger.log("bridge", "warmLinks threw provider=$providerName ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                warmLinksActive.set(false)
            }
        }
        return ok(JSONObject().put("warming", true))
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
        val timedOut: Boolean = false,
        // (v1.0.23 D) Timeout while the mirror probe was still cold:
        // a distinct cause from a generic timeout (see resolveLinks).
        val coldProbeTimeout: Boolean = false,
    )

    // (v0.2.11) C-6/C-7: resolveLinks now returns its failure REASON
    // ("provider_not_found" vs "no_data") instead of a bare null, and the
    // timeout comes from the user's stored Stream-timeout setting (the UI
    // sends timeoutSec when it has one; the setting default is 30 s).
    // A timeout with zero links is flagged timedOut so callers report
    // "timeout" instead of misreporting it as "no_links" (S-5).
    private suspend fun resolveLinks(params: JSONObject): Pair<ResolvedLinks?, String?> {
        val resolveStartMs = System.currentTimeMillis()
        val providerName = params.optString("providerName", "")
        val api = resolveApi(providerName, null) ?: return null to "provider_not_found"
        // (v1.0.23 D) Cold-probe classification (TRACE-1.0.23-LOADLINKS
        // §2a/§8.3): for a CNC mirror provider with no fresh cached API
        // base, a timeout means the play died inside the provider's
        // mirror-probe loop before anything was cached — a distinct
        // cause from a generic timeout. Read once, at play start.
        val coldProbe = isMirrorProvider(api) && isMirrorApiBaseCold()
        // (v1.0.13 P2-C P1) Provider Loaded.
        providerDiag("P1", "Provider Loaded", "provider=$providerName", providerName)
        val data = resolveLinkData(params, api) ?: return null to "no_data"
        val dataSource = when {
            params.optString("episodeData", "").isNotBlank() -> "episodeData"
            params.optString("data", "").isNotBlank() -> "data"
            else -> "fresh"
        }
        val playTitle = params.optString("title", "")
        val pageUrl = params.optString("episodeUrl", "").ifBlank { params.optString("url", "") }
        // (v1.0.13 P2-C P2) LoadData Completed. (v1.0.22 D ITEM 10 /
        // spec §5) P2 now also records the validation inputs — data
        // source, title presence and URL presence — so the
        // "shape=id / titlePresent=false" signature is visible here
        // instead of being inferred from a later line.
        providerDiag("P2", "LoadData Completed", "shape=${loadDataShape(data)} source=$dataSource titlePresent=${playTitle.isNotBlank()} urlPresent=${pageUrl.isNotBlank()}", providerName)
        val settingSec = parseSeconds(store.getSettingString("timeout", "30s"))
        // (v1.0.18 P17-07) The 120 s cap silently re-capped the
        // "Unlimited" 300 s value back down. Cap is the documented 300.
        val timeoutSec = params.optInt("timeoutSec", settingSec).coerceIn(1, 300)
        val foundLinks = mutableListOf<ExtractorLink>()
        val foundSubs = mutableListOf<SubtitleFile>()
        var partial = false
        var timedOut = false
        var linksError: String? = null
        var loadLinksOk = false
        // (v1.0.14 C11) How many loadLinks attempts actually ran (0/1/2):
        // P4 logs it so "loadLinks never ran" is visible without
        // inferring it from absent lines.
        var loadLinksAttempts = 0
        // (v1.0.13 P2-A) Universal data adapter: normalize Id JSON to
        // LoadData JSON BEFORE loadLinks (see normalizeLoadData) — the
        // Id-parse runs before any fresh load, so a fresh load returning
        // the SAME title-less data can no longer kill the play
        // (INVESTIGATION-4 §4.4 fallback gap).
        val normalized = normalizeLoadData(data, playTitle)
        if (normalized != null) {
            EngineLogger.log("bridge", "loadData normalized shape=id source=$dataSource titlePresent=${playTitle.isNotBlank()}")
        }
        // Data that is not JSON at all (a plain URL) is never passed to
        // loadLinks as a raw URL (v1.0.5 E1) — it goes to the fresh-load
        // path below instead. When no fresh-load path exists (no page URL
        // at all) the direct attempt stays as the last resort.
        val skipDirectLoadLinks = normalized == null && !isJsonObject(data) && pageUrl.isNotBlank()
        if (skipDirectLoadLinks) {
            EngineLogger.log("bridge", "loadData non-JSON data — skipping direct loadLinks, using fresh-load path shape=${loadDataShape(data)} source=$dataSource")
        }
        val dataForLinks = normalized ?: data
        try {
            withTimeout(timeoutSec * 1000L) {
                // (v0.2.9) Fix C: loadLinks now returns its cause.
                if (!skipDirectLoadLinks) {
                    loadLinksAttempts++
                    val linksResult = APIRepository(api).loadLinks(dataForLinks, false, { s -> foundSubs += s }) { l -> foundLinks += l }
                    if (linksResult.ok) {
                        loadLinksOk = true
                    } else {
                        partial = foundLinks.isEmpty()
                        linksError = linksResult.error
                        linksResult.error?.let { EngineLogger.log("bridge", "resolveLinks loadLinks failed: $it") }
                        if (isDeserializationError(linksError)) logDataShapeOnFailure(dataForLinks, dataSource)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            partial = true
            timedOut = true
        }
        // (v1.0.22 D ITEM 10 / spec §4) One bounded retry of the
        // DIRECT loadLinks attempt when it timed out with zero links:
        // a provider's loadLinks is several sequential network stages
        // (Netflix net52.cc: cached base resolution + player.php +
        // extractor resolution — The Mentalist S7 E1) and on a slow
        // link it can legitimately exceed one 30 s window while still
        // progressing. The retry gets ONE fresh withTimeout(timeoutSec)
        // window, the SAME data and callbacks, sequentially — never a
        // duplicate concurrent request. It runs only when timeoutSec
        // <= 30, so the worst case (30 + 30 = 60 s) still fits the JS
        // 'play' wait (90 s) and the JS 'links' waits (60 s). On
        // retry success the timeout flags are cleared so callers
        // report the links, not a stale timeout; on a second timeout
        // the honest timeout stands. No Thread.sleep, no unlimited
        // waiting, no invented data.
        if (timedOut && foundLinks.isEmpty() && !loadLinksOk && timeoutSec <= 30) {
            EngineLogger.log("bridge", "resolveLinks direct loadLinks timed out after ${timeoutSec}s — one bounded retry with a fresh ${timeoutSec}s window")
            timedOut = false
            partial = false
            try {
                withTimeout(timeoutSec * 1000L) {
                    if (!skipDirectLoadLinks) {
                        loadLinksAttempts++
                        val retryResult = APIRepository(api).loadLinks(dataForLinks, false, { s -> foundSubs += s }) { l -> foundLinks += l }
                        if (retryResult.ok) {
                            loadLinksOk = true
                            linksError = null
                        } else {
                            partial = foundLinks.isEmpty()
                            linksError = retryResult.error
                            retryResult.error?.let { EngineLogger.log("bridge", "resolveLinks retry loadLinks failed: $it") }
                            if (isDeserializationError(linksError)) logDataShapeOnFailure(dataForLinks, dataSource)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                partial = true
                timedOut = true
            }
        }
        // (v1.0.6 D1) The provider's own LoadData JSON can be missing a
        // required field (Disney: MissingKotlinParameterException "title") —
        // the provider's load() produced it seconds earlier, so stale data
        // is not the cause. Fall back ONCE to a fresh load(url) ->
        // loadDataOf(resp) (for a series: the FIRST episode's data, which
        // normally carries a title) and retry loadLinks with that data.
        // (v1.0.13 P2-A) The fallback also runs when the direct loadLinks
        // was skipped for non-JSON data (skipDirectLoadLinks) — the fresh
        // load IS the path for that data.
        if ((linksError != null && isDeserializationError(linksError) && !timedOut) || (skipDirectLoadLinks && !timedOut)) {
            if (pageUrl.isNotBlank()) {
                var freshData = try {
                    val loaded = APIRepository(api).load(pageUrl)
                    val resp = (loaded as? Resource.Success<LoadResponse>)?.value
                    if (resp != null) loadDataOf(resp) else null
                } catch (e: Exception) {
                    null
                }
                if (freshData != null && freshData.isNotBlank() && freshData != data) {
                    if (skipDirectLoadLinks) {
                        EngineLogger.log("bridge", "resolveLinks non-JSON data — retrying loadLinks with fresh load() data")
                    } else {
                        EngineLogger.log("bridge", "resolveLinks deserialization failure — retrying loadLinks with fresh load() data")
                    }
                    // (v1.0.13 P2-A) The fresh data can be Id JSON too —
                    // normalize it before the retry, same as the direct data.
                    val freshNormalized = normalizeLoadData(freshData, playTitle)
                    if (freshNormalized != null) {
                        EngineLogger.log("bridge", "loadData normalized shape=id source=fresh titlePresent=${playTitle.isNotBlank()}")
                        freshData = freshNormalized
                    }
                    val retryLinks = mutableListOf<ExtractorLink>()
                    val retrySubs = mutableListOf<SubtitleFile>()
                    try {
                        withTimeout(timeoutSec * 1000L) {
                            loadLinksAttempts++
                            val retryResult = APIRepository(api).loadLinks(freshData, false, { s -> retrySubs += s }) { l -> retryLinks += l }
                            if (retryResult.ok) {
                                foundLinks.clear()
                                foundLinks += retryLinks
                                foundSubs.clear()
                                foundSubs += retrySubs
                                partial = false
                                linksError = null
                                loadLinksOk = true
                            } else {
                                retryResult.error?.let { EngineLogger.log("bridge", "resolveLinks fallback loadLinks failed: $it") }
                                if (isDeserializationError(retryResult.error)) logDataShapeOnFailure(freshData, "fresh")
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        partial = true
                        timedOut = true
                    }
                }
            }
        }
        // (v1.0.14 C11) MovieBox shape: the direct attempt was skipped
        // for non-JSON data and the fresh-load fallback could not produce
        // different data (same dataUrl), so NO loadLinks attempt has run
        // at all — zero attempts by construction, hence links=0. Attempt
        // ONCE with the raw dataUrl itself (the V1.0.12 behaviour): the
        // provider's loadLinks decides whether a raw URL works. The raw
        // URL is passed as the loadLinks INPUT, never fabricated into
        // LoadData JSON (the v1.0.5 E1 rule is kept). If the provider also
        // returns nothing for it, the log then shows the failure and the
        // provider owns it.
        if (skipDirectLoadLinks && !loadLinksOk && foundLinks.isEmpty() && !timedOut) {
            EngineLogger.log("bridge", "resolveLinks raw-dataUrl attempt — fresh load produced no new data, trying loadLinks(dataUrl)")
            try {
                withTimeout(timeoutSec * 1000L) {
                    loadLinksAttempts++
                    val rawResult = APIRepository(api).loadLinks(dataForLinks, false, { s -> foundSubs += s }) { l -> foundLinks += l }
                    if (rawResult.ok) {
                        loadLinksOk = true
                    } else {
                        linksError = rawResult.error
                        rawResult.error?.let { EngineLogger.log("bridge", "resolveLinks raw loadLinks failed: $it") }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                partial = true
                timedOut = true
            }
        }
        // (v1.0.13 P2-C P3) LoadLinks Completed — only when a loadLinks call
        // actually returned ok (direct or fallback).
        if (loadLinksOk) {
            providerDiag("P3", "LoadLinks Completed", "links=${foundLinks.size} partial=$partial", providerName)
        }
        // (v1.0.13 P2-C P4) Extractor Completed — the extraction result as
        // assembled (links + subtitles), whatever the outcome.
        // (v1.0.22 D ITEM 10 / spec §5) P4 now also carries the
        // extraction duration and the failure category, so "timed out
        // after N ms" is distinguishable from "completed with zero
        // links" without reading the log around it.
        // (v1.0.23 D) A timeout with no error string is the
        // TimeoutCancellationException path (APIRepository rethrows it,
        // so linksError was never set and P4 printed errorClass=-).
        // Record the honest cause: a cold mirror probe gets its own
        // class, anything else the generic timeout class. This runs
        // AFTER every attempt (direct + bounded retry + fallbacks), so
        // a retry that returned a real error keeps that error, and a
        // retry that succeeded clears it — the timeout / error /
        // no_links categories stay distinct (playback-pipeline rule).
        if (timedOut && linksError == null) {
            linksError = if (coldProbe) "ProviderMirrorProbeTimeout: provider mirror probe timed out (no cached API base)"
            else "TimeoutCancellationException: loadLinks timed out after ${timeoutSec}s"
        }
        val resolveDurationMs = System.currentTimeMillis() - resolveStartMs
        val failCategory = when {
            loadLinksOk -> "ok"
            timedOut && coldProbe -> "mirror_probe_timeout"
            timedOut -> "timeout"
            linksError != null -> "error"
            foundLinks.isEmpty() -> "no_links"
            else -> "unknown"
        }
        val failErrorClass = linksError?.substringBefore(':') ?: "-"
        providerDiag("P4", "Extractor Completed", "links=${foundLinks.size} subs=${foundSubs.size} loadLinksAttempts=$loadLinksAttempts durationMs=$resolveDurationMs category=$failCategory errorClass=$failErrorClass", providerName)
        // (v1.0.17 P15-01) Link-resolution verdict for the play path.
        if (providerName.isNotBlank()) {
            val upd = JSONObject().put("linkResolution", foundLinks.isNotEmpty()).put("dataValidation", true)
            if (foundLinks.isEmpty()) upd.put("lastError", linksError ?: "no_links")
            recordHealthByName(providerName, upd)
        }
        return ResolvedLinks(api, foundLinks, foundSubs, partial, timedOut, coldProbeTimeout = timedOut && coldProbe && foundLinks.isEmpty() && !loadLinksOk) to null
    }

    /** (v1.0.6 D1) loadLinks errors that mean the provided `data` JSON
     * could not be deserialized (its shape, not the network): the error
     * string is "<SimpleName>: <message>" from APIRepository.loadLinks. */
    private fun isDeserializationError(msg: String?): Boolean {
        if (msg == null) return false
        return msg.contains("MissingKotlinParameterException") ||
            msg.contains("JsonMappingException") ||
            msg.contains("JsonParseException") ||
            msg.contains("MismatchedInputException") ||
            msg.contains("InvalidDefinitionException") ||
            msg.contains("JsonProcessingException")
    }

    // (v1.0.13 P2-A) Universal data adapter helpers. The provider's loadLinks
    // parses `data` as LoadData (id + title, both required), but some
    // providers emit Id JSON ({"id":"..."}) with no title — and loadLinks
    // only ever USES the id (INVESTIGATION-4 §4.1). Never let a missing
    // UNUSED field fail link resolution.
    /** Shape label for a data string: "loaddata" (has a non-null title),
     * "id" (has a non-null id but no title), "other" (anything else). */
    private fun loadDataShape(data: String): String {
        val obj = try {
            JSONObject(data)
        } catch (e: Exception) {
            return "other" // not a JSON object (plain URL, malformed JSON)
        }
        return when {
            !obj.isNull("title") -> "loaddata"
            !obj.isNull("id") -> "id"
            else -> "other"
        }
    }

    /** True when `data` parses as a JSON object — tells a plain URL apart
     * from LoadData/Id JSON. (A try-parse, not a brace-literal test: the
     * gate counts braces naively, string literals included.) */
    private fun isJsonObject(data: String): Boolean {
        return try {
            JSONObject(data)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Normalize a data string to LoadData JSON, or null to leave it as-is:
     * LoadData JSON passes through; Id JSON gets "title" injected from the
     * play params (when the play title is non-blank — a blank title is
     * never injected, see below); non-JSON data (a plain URL) and JSON with BOTH
     * id and title missing are left alone — a raw URL is never passed to
     * loadLinks as a fake LoadData, and an id is never invented.
     */
    private fun normalizeLoadData(data: String, title: String): String? {
        val obj = try {
            JSONObject(data)
        } catch (e: Exception) {
            return null // not JSON at all (plain URL) or malformed: fresh-load path
        }
        if (!obj.isNull("title")) return null // already LoadData shape
        if (obj.isNull("id")) return null // neither id nor title: no id to use
        // (v1.0.22 D ITEM 10 / spec §2) Do NOT inject a BLANK title:
        // {"id": "...", "title": ""} is still invalid for providers
        // whose LoadData requires a non-blank title, and it hides the
        // real "title missing" state. Return null so the original
        // provider data flows to the deserialization-error fallback
        // path instead of a fabricated title.
        if (title.isBlank()) return null
        val normalized = JSONObject()
        for (key in obj.keys()) normalized.put(key, obj.get(key))
        normalized.put("title", title)
        return normalized.toString()
    }

    /** (v1.0.13 P2-A) First ~200 chars of a data string for the failure log —
     * ONLY when it is a JSON object with no token-like content (no "?", no
     * "::", no "in="). Anything else returns null: the log then carries just
     * the shape label, never a URL with a signed query. */
    private fun dataShapeSnippet(data: String): String? {
        try {
            JSONObject(data)
        } catch (e: Exception) {
            return null // not a JSON object: never snippet a URL
        }
        if (data.contains("?") || data.contains("::") || data.contains("in=")) return null
        return data.take(200)
    }

    /** (v1.0.13 P2-A) Log the data shape on a deserialization failure, plus
     * which source supplied the data (episodeData vs data vs fresh load). */
    private fun logDataShapeOnFailure(data: String, source: String) {
        val snippet = dataShapeSnippet(data)
        EngineLogger.log(
            "bridge",
            "loadData deserialization failed shape=${loadDataShape(data)} source=$source" +
                if (snippet != null) " data=$snippet" else " data=<redacted>",
        )
    }

    // (v1.0.13 P2-B) Redact signed-token query values before logging a URL:
    // `in=<redacted>` — never log a live token (INVESTIGATION-4 §3.5).
    private fun redactUrlForLog(url: String?): String {
        if (url.isNullOrBlank()) return "none"
        return url.replace(Regex("([?&]in=)[^&]*"), "$1<redacted>")
    }

    /** (v1.0.13 P2-B) Cause chain of a playback error, token-redacted, at most 5 deep. */
    private fun causeChainForLog(error: Throwable): String {
        val parts = mutableListOf<String>()
        var c = error.cause
        var depth = 0
        while (c != null && depth < 5) {
            parts += "${c.javaClass.simpleName}: ${redactUrlForLog(c.message)}"
            c = c.cause
            depth++
        }
        return if (parts.isEmpty()) "none" else parts.joinToString(" <- ")
    }

    /** (v1.0.13 P2-B) Age in ms of a ::(<epoch-seconds>):: token segment in
     * a URL, or null when the URL carries no such token. Never logs the token. */
    private fun tokenAgeMs(url: String?): Long? {
        if (url.isNullOrBlank()) return null
        val epochSec = Regex("[?&]in=[^&]*::(\\d{9,11})::").find(url)
            ?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("::(\\d{9,11})::").find(url)
                ?.groupValues?.get(1)?.toLongOrNull()
            ?: return null
        return System.currentTimeMillis() - epochSec * 1000L
    }

    /** (v1.0.13 P2-B) "tokenAgeMs=<ms>" when the URL carries an epoch token, else "". */
    private fun tokenAgeSuffix(url: String?): String {
        val age = tokenAgeMs(url) ?: return ""
        return " tokenAgeMs=$age"
    }

    // (v1.0.13 P3-A) Manifest-validation cache, keyed by the EXACT URL
    // instance: a re-resolve mints a new token, hence a new key, so a
    // cached failure can never poison a fresh URL. BOTH passes and
    // failures are cached — a retry that re-probes the same URL must not
    // re-fetch (and re-burn) it. Bounded: cleared past 64 entries.
    private val manifestValidationCache = ConcurrentHashMap<String, ManifestValidation>()
    // (v1.0.13 P3-A) Largest manifest the validator will read (2 MB):
    // a real HLS/DASH manifest is kilobytes (Castle TV's is 67 KB).
    private val maxManifestBytes = 2 * 1024 * 1024

    /** (v1.0.13 P3-A) Outcome of validating one manifest link. `ok` is
     * true only when the REAL parser validated the full manifest;
     * `reason` names the failure (token-redacted) for the probe log. */
    private data class ManifestValidation(
        val ok: Boolean,
        val manifestType: String, // "HLS" / "DASH" / "direct"
        val isMultivariant: Boolean,
        val childrenChecked: Int,
        val reason: String? = null,
    )

    /** (v1.0.13 P3-A) One child-playlist check outcome: how many children
     * were fetched, and the first failure (token-redacted), if any. */
    private data class ChildCheckResult(
        val checked: Int,
        val failure: String?,
    )

    /** (v1.0.13 P3-A) One manifest fetch: HTTP status, content type, full
     * body text, and whether the body exceeded [maxManifestBytes]. */
    private data class ManifestFetch(
        val status: Int,
        val contentType: String,
        val body: String,
        val tooLarge: Boolean,
    )

    // (v1.0.13 P3-C) Last link whose headers were built, for the
    // per-provider isolation check in validateManifestLink.
    private var lastHeaderSource: String? = null
    private var lastHeaders: Map<String, String> = emptyMap()

    /** (v1.0.13 P2-C) Provider-side diagnostics P1–P7, same field shape as
     * the L1–L12 player events (diag <code> <name> session=... engine=...
     * ts=...). NOTE: P1–P6 fire during resolve/rank/probe, before the new
     * play's session id is fixed at play time, so on the INITIAL play they
     * carry the previous session's counter; P7 (after playActive) carries
     * the new one. Correlate P1–P6 by ts. */
    private fun providerDiag(code: String, name: String, note: String = "", providerName: String = "") {
        EngineLogger.log(
            "player",
            "diag $code $name session=$playSessionCounter engine=exoplayer " +
                "ts=${System.currentTimeMillis()}" +
                if (note.isNotEmpty()) " $note" else "",
        )
        // (v1.0.17 P15-01/P15-08) Feed the Part H record from the play
        // path. Only VERIFIED stream validation counts (P6): fail-open /
        // signed-direct notes say UNVERIFIED and set nothing. P2/P4/P5
        // only move lastStage — their verdicts are recorded where the
        // outcome is known (resolveLinks / P3).
        if (providerName.isNotBlank()) {
            val update = JSONObject().put("lastStage", code)
            when (code) {
                "P1" -> update.put("load", true)
                "P3" -> update.put("linkResolution", true)
                "P6" -> if (note.startsWith("VERIFIED")) update.put("streamValidation", true)
                "P7" -> update.put("playerPrep", true)
            }
            recordHealthByName(providerName, update)
        }
    }

    /**
     * (v1.0.18 P17-07, G-11) The timeout parser lied: "Instant" fell
     * through to the 30 s default (the opposite of instant) and
     * "Unlimited" also became 30 s. Exact table: Instant -> 1 s,
     * Unlimited -> 300 s (the documented cap, never 30), N s -> N.
     */
    private fun parseSeconds(raw: String): Int = when {
        raw.contains("Instant", ignoreCase = true) -> 1
        raw.contains("Unlimited", ignoreCase = true) -> 300
        else -> Regex("""(\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 30
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
     * Smart-Streams ranking (design §4.6, v1.0.4 FINAL order 17:33):
     * language 1st, quality 2nd, speed 3rd. The language tier dominates
     * the score (audio1 +10000, audio2 +5000, audio3 +2500); quality match adds +100;
     * then MEASURED server-only TTFB ascending. Unmeasured links rank
     * after measured ones at the same score. Nothing is invented.
     * (v1.0.5 B1/B4) Unplayable links are filtered / penalised BEFORE the
     * pick, so the PARSING_CONTAINER_UNSUPPORTED stub can no longer win
     * the Auto pick: .txt URLs are dropped outright, Unknown-quality
     * links lose 50 and non-manifest (non-M3U8/non-DASH) links lose 50,
     * below the +100 quality tier — a playable alternative always wins.
     * The FINAL order itself is unchanged.
     */
    private suspend fun rankLinks(links: List<ExtractorLink>, providerName: String = ""): List<RankedLink> {
        val resPref = store.getSettingString("res", "Auto")
        // (v1.0.18 P17-05) srcPrio "speed": TTFB-first WITHIN the
        // language tier (a Hindi link never loses to a faster Tamil
        // one — the language tier still dominates, only the quality
        // tier is dropped). "quality" (default) keeps the old order.
        val srcPrioSpeed = store.getSettingString("srcPrio", "quality") == "speed"
        val a1 = audioCodeOf(store.getSettingString("audio1", "English"))
        val a2 = audioCodeOf(store.getSettingString("audio2", "বাংলা"))
        // (v1.0.19 REQ1) Third audio language; "none" maps to null
        // via audioCodeOf, so an unset third slot contributes nothing
        // and ranking with only audio1/audio2 set is unchanged.
        val a3 = audioCodeOf(store.getSettingString("audio3", "none"))
        val playable = links.filter { link ->
            if (link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.MAGNET) return@filter false
            // (v1.0.5 B1) A .txt URL is a playlist stub / error marker, not
            // a stream (AllMovieLand's .txt returns the 2-byte body "11"
            // without its token). Drop it before ranking.
            val path = link.url.substringBefore('?').substringBefore('#')
            if (path.endsWith(".txt", ignoreCase = true)) {
                EngineLogger.log("player", "rankLinks dropped .txt link ${link.source} ${redactUrlForLog(link.url)}")
                return@filter false
            }
            // ExtractorLinkType has only VIDEO / M3U8 / DASH besides the
            // two dropped above, so every surviving type is a playable
            // container — there is no further type to drop.
            true
        }
        val scored = playable.map { link ->
            var score = 0
            val ql = qualityLabel(link.quality)
            val audio = audioFromName(link.name)
            if (audio != null && audio == a1) score += 10000
            else if (audio != null && a2 != null && audio == a2) score += 5000
            else if (audio != null && a3 != null && audio == a3) score += 2500
            if (resPref != "Auto" && ql.isNotEmpty() && ql == resPref) score += 100
            // (v1.0.5 B4) Penalise, never hide: an Unknown-quality link
            // (the V1.0.4 erroring pick had no quality at all) and a
            // non-manifest link each lose 50 when a playable alternative
            // exists. Below the quality tier, above the TTFB tiebreak.
            if (ql.isEmpty()) score -= 50
            if (link.type != ExtractorLinkType.M3U8 && link.type != ExtractorLinkType.DASH) score -= 50
            link to (score to audio)
        }
        // Measure TTFB for the top 8 by score only (keeps the call fast).
        val top = scored.sortedByDescending { it.second.first }.take(8).map { it.first }
        val ttfb = measureTtfb(top)
        val ranked = scored.map { (link, scoreAudio) ->
            RankedLink(link, scoreAudio.first, ttfb[link], scoreAudio.second)
        }.sortedWith(
            if (srcPrioSpeed) {
                compareByDescending<RankedLink> {
                    // Language tier only: a1 (10000+) > a2 (5000+) > none.
                    when {
                        it.score >= 10000 -> 2
                        it.score >= 5000 -> 1
                        else -> 0
                    }
                }
                    .thenBy { it.ttfbMs ?: Long.MAX_VALUE }
                    .thenByDescending { it.score }
            } else {
                compareByDescending<RankedLink> { it.score }
                    .thenBy { it.ttfbMs ?: Long.MAX_VALUE }
            },
        )
        // (v1.0.13 P2-C P5) Stream Resolved.
        if (ranked.isNotEmpty()) {
            providerDiag("P5", "Stream Resolved", "links=${ranked.size}", providerName)
        }
        return ranked
    }

    private suspend fun measureTtfb(links: List<ExtractorLink>): Map<ExtractorLink, Long?> {
        return coroutineScope {
            links.map { link ->
                async {
                    // (v1.0.13 P3-B) Mechanism (b) — the signed URL is NEVER
                    // fetched before play by TTFB or the 2KB probe: either
                    // fetch could burn a single-use token (INVESTIGATION-4
                    // §5.3(d)). The validator (probePlayable ->
                    // validateManifestLink) performs the SINGLE pre-play
                    // fetch of the picked URL, right before play, and its
                    // result is cached per URL instance. An unmeasured
                    // signed link ranks after measured ones at the same
                    // score (ttfbMs=null) — honestly, not invented.
                    if (ZaynHeaderManager.isSignedUrl(link.url)) {
                        EngineLogger.log("player", "ttfb skipped signed URL ${link.source} url=${redactUrlForLog(link.url)}")
                        link to null
                    } else {
                        val ms = try {
                            withTimeout(3000) {
                                val req = Request.Builder().url(link.url)
                                    .apply { ZaynHeaderManager.headersFor(link).forEach { (k, v) -> header(k, v) } }
                                    .header("Range", "bytes=0-0").build()
                                com.lagradost.cloudstream3.app.baseClient.newCall(req).execute().use {
                                    it.body?.close()
                                    // (v1.0.4) Server-only TTFB: response-received
                                    // minus request-sent excludes DNS + TCP + TLS
                                    // setup, which dominated the old wall-clock
                                    // number (~1 s on a fresh mobile connection)
                                    // and made every server look equally slow. A
                                    // genuinely slow server still reports high.
                                    (it.receivedResponseAtMillis - it.sentRequestAtMillis)
                                        .coerceAtLeast(0)
                                }
                            }
                        } catch (e: Exception) {
                            null
                        }
                        link to ms
                    }
                }
            }.awaitAll().toMap()
        }
    }

    /**
     * (v1.0.13 P3-A) Probe = full-manifest validation for manifest links.
     * The v1.0.7 2KB head check could not see a malformed body past byte
     * 2048 or a bad child playlist, and ExoPlayer rejected links the probe
     * passed (INVESTIGATION-4 §3.2). The picked link must now pass the
     * real validator (validateManifestLink) before it is played; the head
     * check survives only as a pre-filter inside the validator. A manifest
     * that fails validation is skipped and the P2 retry policy moves to
     * the next ranked link / other engine — it is NEVER sent to the player.
     * Fail-OPEN only when validation itself is unavailable (transport
     * error/timeout): no verdict exists then, and ExoPlayer + the retry
     * are the backstop. Non-manifest (direct video) links keep the old
     * cheap ranged probe — there is no manifest to parse.
     * (v1.0.13 P3-B) Signed URLs are never burned by a TTFB/2KB pre-fetch:
     * the validator's single full-manifest fetch is the ONLY pre-play
     * fetch of that URL, and its result is cached per URL instance, so a
     * retry that re-probes the same URL does not fetch (or burn) it again.
     */
    private suspend fun probePlayable(link: ExtractorLink, providerName: String = ""): Boolean {
        val isManifest = link.type == ExtractorLinkType.M3U8 ||
            link.type == ExtractorLinkType.DASH
        if (isManifest) {
            val validated = validateManifestLink(link)
            if (validated == null) {
                // Validation unavailable (transport error/timeout) — no
                // verdict; fail open and let ExoPlayer decide.
                EngineLogger.log("player", "probe ok (validation unavailable, fail-open) ${link.source} url=${redactUrlForLog(link.url)}${tokenAgeSuffix(link.url)}")
                providerDiag("P6", "Stream Validated", "UNVERIFIED-by-code validation-unavailable source=${link.source}", providerName)
                return true
            }
            if (!validated.ok) {
                lastProbeSkipReason = validated.reason
                EngineLogger.log("player", "probe skipped ${link.source} url=${redactUrlForLog(link.url)} (${validated.reason})")
                return false
            }
            EngineLogger.log("player", "probe ok ${link.source} url=${redactUrlForLog(link.url)}${tokenAgeSuffix(link.url)}")
            // (v1.0.13 P3-A) P6 VERIFIED — the real parser validated this
            // manifest (type, multivariant yes/no, children checked).
            providerDiag("P6", "Stream Validated", "VERIFIED type=${validated.manifestType} multivariant=${validated.isMultivariant} childrenChecked=${validated.childrenChecked} source=${link.source}", providerName)
            return true
        }
        if (ZaynHeaderManager.isSignedUrl(link.url)) {
            // (v1.0.13 P3-B) A signed direct-video URL gets NO pre-play
            // fetch at all (no TTFB, no probe): the token is spent only by
            // the player. There is no manifest to validate, so P6 stays
            // UNVERIFIED-by-code for this link.
            EngineLogger.log("player", "probe skipped signed URL (no pre-play fetch) ${link.source} url=${redactUrlForLog(link.url)}")
            providerDiag("P6", "Stream Validated", "UNVERIFIED-by-code signed-direct source=${link.source}", providerName)
            return true
        }
        return try {
            withTimeout(3000) {
                val req = Request.Builder().url(link.url)
                    .apply { ZaynHeaderManager.headersFor(link).forEach { (k, v) -> header(k, v) } }
                    .header("Range", "bytes=0-0").build()
                com.lagradost.cloudstream3.app.baseClient.newCall(req).execute().use { resp ->
                    val head = ByteArray(2048)
                    var headLen = 0
                    resp.body?.byteStream()?.use { stream ->
                        while (headLen < head.size) {
                            val r = stream.read(head, headLen, head.size - headLen)
                            if (r == -1) break
                            headLen += r
                        }
                    }
                    val ct = (resp.header("Content-Type") ?: "").lowercase()
                    val totalLen: Long? = if (resp.code == 206) {
                        resp.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                    } else {
                        resp.header("Content-Length")?.toLongOrNull()
                    }
                    val reason = when {
                        ct.startsWith("text/html") -> "content-type text/html"
                        ct.startsWith("text/plain") -> "content-type text/plain"
                        totalLen != null && totalLen in 1..2 -> "stub body ($totalLen bytes)"
                        else -> null
                    }
                    if (reason != null) {
                        lastProbeSkipReason = reason
                        EngineLogger.log("player", "probe skipped ${link.source} url=${redactUrlForLog(link.url)} ($reason)")
                        false
                    } else {
                        EngineLogger.log("player", "probe ok ${link.source} url=${redactUrlForLog(link.url)}${tokenAgeSuffix(link.url)}")
                        providerDiag("P6", "Stream Validated", "UNVERIFIED-by-code direct-video source=${link.source}", providerName)
                        true
                    }
                }
            }
        } catch (e: Exception) {
            EngineLogger.log("player", "probe failed (fail-open) ${link.source}: ${redactUrlForLog(e.message)}")
            true
        }
    }

    /**
     * (v1.0.13 P3-A) Full-manifest validator for the PICKED link. Fetches
     * the FULL manifest (no Range) and validates it with a REAL parser:
     * the vendored HlsPlaylistParser (the Media3 HLS parser port already
     * in this codebase, used by M3u8Helper — no new parser library) for
     * HLS, Media3's DashManifestParser (media3-exoplayer-dash, already a
     * dependency) for DASH. Returns null ONLY when validation itself was
     * unavailable (transport error/timeout) — the caller fails open
     * then; any other outcome is a verdict (ok or not) and is cached per
     * URL instance. A parse failure means the link is unplayable: the
     * parser's message is logged (token-redacted) and the link is
     * skipped — never sent to the player.
     */
    private suspend fun validateManifestLink(link: ExtractorLink): ManifestValidation? {
        val url = link.url
        manifestValidationCache[url]?.let { cached ->
            EngineLogger.log("player", "manifest validation cached ok=${cached.ok} source=${link.source} url=${redactUrlForLog(url)}")
            return cached
        }
        val headers = ZaynHeaderManager.headersFor(link)
        // (v1.0.13 P3-C) Per-provider isolation check against the last
        // link's headers (only across providers — same-provider links
        // legitimately share headers). A violation is logged, never
        // blocking: the headers sent are still built only from this link.
        if (lastHeaderSource != null && lastHeaderSource != link.source &&
            !ZaynHeaderManager.isIsolated(headers, lastHeaders)
        ) {
            EngineLogger.log("player", "header isolation WARNING source=${link.source} shares Referer/headers with $lastHeaderSource — headers still built only from this link")
        }
        lastHeaderSource = link.source
        lastHeaders = headers
        val result = try {
            validateManifestFetch(link, headers)
        } catch (e: TimeoutCancellationException) {
            EngineLogger.log("player", "manifest validation timeout source=${link.source} url=${redactUrlForLog(url)}")
            null
        } catch (e: Exception) {
            EngineLogger.log("player", "manifest validation unavailable source=${link.source}: ${redactUrlForLog(e.message)}")
            null
        }
        if (result != null) {
            if (manifestValidationCache.size >= 64) manifestValidationCache.clear()
            manifestValidationCache[url] = result
        }
        return result
    }

    /**
     * (v1.0.13 P3-A) Fetch the full manifest and run the verdict checks
     * in order: size cap, HTTP status, HTML error page, head pre-filter,
     * then the real parser. Throws on transport error (caller fails
     * open); every other outcome is a ManifestValidation verdict.
     * No Range header is sent here — the validator reads the FULL
     * manifest, and a ranged fetch would be a second pre-play fetch
     * that could burn a single-use token (P3-B).
     */
    private suspend fun validateManifestFetch(
        link: ExtractorLink,
        headers: Map<String, String>,
    ): ManifestValidation {
        val url = link.url
        val typeName = if (link.type == ExtractorLinkType.DASH) "DASH" else "HLS"
        val fetch = fetchManifestText(link, url, headers)
        if (fetch.tooLarge) {
            return ManifestValidation(false, typeName, false, 0, "manifest exceeds 2 MB")
        }
        if (fetch.status != 200) {
            return ManifestValidation(false, typeName, false, 0, "manifest HTTP ${fetch.status} contentType=${fetch.contentType}")
        }
        val head = fetch.body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        // (v1.0.13 P3-A, v1.0.14 C6) HTML error pages must NEVER reach the
        // player — but prefer the BODY over the header: a CDN serving a
        // genuine manifest as text/html (misconfigured origin) must not
        // be rejected on the header alone when the body starts #EXTM3U
        // (or contains <MPD). The head pre-filter below still rejects a
        // body that is actually HTML, so this cannot let an error page
        // through. Both the content-type and the body head are logged.
        val bodyLooksManifest = if (link.type == ExtractorLinkType.DASH) fetch.body.contains("<MPD") else head.startsWith("#EXTM3U")
        if ((fetch.contentType.startsWith("text/html") && !bodyLooksManifest) ||
            head.startsWith("<html", ignoreCase = true) ||
            head.startsWith("<!doctype html", ignoreCase = true)
        ) {
            return ManifestValidation(false, typeName, false, 0, "HTML error page status=${fetch.status} contentType=${fetch.contentType} head=${redactUrlForLog(head.take(120))}")
        }
        // The 2KB head check survives ONLY as a cheap pre-filter: a body
        // that fails it is rejected before the real parser runs; a body
        // that passes it is still fully parsed below.
        val headBytes = fetch.body.toByteArray(Charsets.UTF_8)
        if (!isValidManifest(link.type, headBytes, minOf(headBytes.size, 2048))) {
            return ManifestValidation(false, typeName, false, 0, "not a valid M3U8/DASH manifest")
        }
        return when (link.type) {
            ExtractorLinkType.DASH -> validateDash(link, url, fetch.body)
            else -> validateHls(link, url, fetch.body, headers)
        }
    }

    /**
     * (v1.0.13 P3-A) Fetch one manifest URL in full (no Range), with the
     * link's headers from the header manager and Cache-Control: no-store
     * for signed URLs (P3-B). The body is capped at [maxManifestBytes].
     */
    private suspend fun fetchManifestText(
        link: ExtractorLink,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long = 10000,
    ): ManifestFetch {
        return withTimeout(timeoutMs) {
            val req = Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .apply {
                    // (v1.0.13 P3-B) Signed URLs: never serve a manifest
                    // fetch from cache, and never store one — a cacheable
                    // error response must not be replayed to the player.
                    ZaynHeaderManager.cacheControlFor(url)?.let { header("Cache-Control", it) }
                }
                .build()
            com.lagradost.cloudstream3.app.baseClient.newCall(req).execute().use { resp ->
                val ct = (resp.header("Content-Type") ?: "").lowercase()
                val buf = ByteArray(maxManifestBytes + 1)
                var len = 0
                resp.body?.byteStream()?.use { stream ->
                    while (len < buf.size) {
                        val r = stream.read(buf, len, buf.size - len)
                        if (r == -1) break
                        len += r
                    }
                }
                val tooLarge = len > maxManifestBytes
                ManifestFetch(
                    resp.code,
                    ct,
                    buf.copyOf(minOf(len, maxManifestBytes)).decodeToString(),
                    tooLarge,
                )
            }
        }
    }

    // (v1.0.13 P7 fix-forward D-1, v1.0.14 C4) EXTINF duration shape.
    // Media3's own vendored parser accepts a missing comma and whitespace
    // before the comma (HlsPlaylistParser.kt:108 REGEX_MEDIA_DURATION =
    // "#EXTINF:([\\d\\.]+)\\b" — no comma required), so D-1 must not
    // reject those shapes: "#EXTINF:10" and "#EXTINF:10 ," are valid.
    // Still rejected: non-numeric durations ("#EXTINF:abc," — the shape
    // D-1 was built for), negative durations, a space after the colon,
    // and multi-dot numbers — none of which the Media3 regex accepts.
    private val extinfLineRegex = Regex("^#EXTINF:\\d+(\\.\\d+)?\\s*(,|$)")

    /**
     * (v1.0.13 P7 fix-forward D-1, v1.0.14 C4) MINIMAL media-playlist
     * structural check: every `#EXTINF:` line must be
     * `#EXTINF:<number>` with an optional comma — integer and float
     * durations are both valid (`#EXTINF:10,`, `#EXTINF:10.0,`,
     * `#EXTINF:10` and `#EXTINF:10 ,` pass per Media3's own
     * REGEX_MEDIA_DURATION; `#EXTINF:abc,` fails). No other structural
     * rule is checked here — no segment count, no ENDLIST, no
     * duration-range check. HlsPlaylistParser.parse returns null for
     * non-multivariant input (there is no media-playlist parse entry
     * point in the vendored parser), so this line check is the coverage
     * for media playlists. Used by the validateHls media branch and by
     * checkChildPlaylists for media children. Returns the failure reason
     * (naming the offending line), or null when every EXTINF line is
     * well-formed.
     */
    private fun malformedExtinfLine(text: String): String? {
        val bad = text.lines().firstOrNull { line ->
            val t = line.trim()
            t.startsWith("#EXTINF:") && !extinfLineRegex.containsMatchIn(t)
        } ?: return null
        return "media playlist has malformed EXTINF line: ${bad.trim()}"
    }

    /**
     * (v1.0.13 P3-A) HLS validation with the REAL parser. A media
     * playlist must carry segments; a multivariant playlist is parsed by
     * HlsPlaylistParser (throws ParserException on a malformed tag — e.g.
     * #EXT-X-STREAM-INF with no following line — exactly the shapes
     * Media3 rejects as PARSING_MANIFEST_MALFORMED) and its variant +
     * rendition child playlists are then probed too (L2).
     */
    private suspend fun validateHls(
        link: ExtractorLink,
        url: String,
        body: String,
        headers: Map<String, String>,
    ): ManifestValidation {
        val text = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        // (v1.0.14 CAUSE-2/C1) Trim each line before matching, exactly as
        // the D-2 child check already does: a master playlist whose
        // #EXT-X-STREAM-INF lines carry leading whitespace was
        // misclassified here as a segment-less MEDIA playlist and falsely
        // rejected with "media playlist has no segments" — while the same
        // body passed the child check. Classification must agree.
        // (v1.0.14 C5) I-frame-only is NOT multivariant here: the parser
        // gate (HlsPlaylistParser.parse, HP:1719-1725) only enters the
        // multivariant branch on TAG_STREAM_INF ("#EXT-X-STREAM-INF"),
        // so an I-frame-only master was called multivariant by this
        // check and non-multivariant by the parser. It now falls to the
        // media branch and fails the segment check honestly (no segments,
        // no playable variants).
        val isMultivariant = text.lines().any {
            it.trim().startsWith("#EXT-X-STREAM-INF")
        }
        if (!isMultivariant) {
            if (!text.contains("#EXTINF") && !text.contains("#EXT-X-TARGETDURATION")) {
                // (v1.0.14 CAUSE-2/C1) Manifest fingerprint: the 04:10 log
                // proved the verdict but not what the CDN actually returned
                // (#EXTM3U head passed, yet no segments). Byte length,
                // line count, first tag lines, and the three presence
                // booleans decide TRUE-empty vs misclassified-master
                // without re-fetching an expired token. The validator is
                // RIGHT to reject a segment-less body (RFC 8216 media
                // playlists must carry #EXTINF); this line proves which
                // shape it was.
                val tagLines = text.lines().filter { it.trim().startsWith("#") }.take(10)
                EngineLogger.log(
                    "player",
                    "validateHls no segments source=${link.source} bytes=${text.length} lines=${text.lines().size} streamInf=${text.lines().any { it.contains("#EXT-X-STREAM-INF") }} extinf=${text.contains("#EXTINF")} targetDuration=${text.contains("#EXT-X-TARGETDURATION")} tags=[${redactUrlForLog(tagLines.joinToString(" | "))}]",
                )
                return ManifestValidation(false, "HLS", false, 0, "media playlist has no segments")
            }
            // (v1.0.13 P7 fix-forward D-1) The presence check above cannot
            // see a malformed EXTINF line late in the playlist — run the
            // minimal structural check before passing the link.
            malformedExtinfLine(text)?.let { reason ->
                return ManifestValidation(false, "HLS", false, 0, reason)
            }
            return ManifestValidation(true, "HLS", false, 0)
        }
        val parsed = try {
            HlsPlaylistParser.parse(url, body)
        } catch (e: Exception) {
            // The parser's message names the failing tag/line — log it
            // (token-redacted) so the next failure is self-diagnosing.
            return ManifestValidation(false, "HLS", true, 0, "parser: ${redactUrlForLog(e.message)}")
        }
        if (parsed == null) {
            return ManifestValidation(false, "HLS", true, 0, "parser returned no multivariant playlist")
        }
        if (parsed.variants.isEmpty()) {
            return ManifestValidation(false, "HLS", true, 0, "multivariant playlist has no variants")
        }
        // Child playlists: variant URLs + audio/subtitle/video rendition
        // URLs, with the top-level token re-attached (L2) — relative
        // resolution drops the query, so a child fetched without the
        // token would 404 on a CDN that requires it.
        val childUrls = LinkedHashSet<String>()
        parsed.variants.forEach { childUrls += reattachToken(url, it.url.toString()) }
        (parsed.audios + parsed.subtitles + parsed.videos).forEach { rendition ->
            rendition.url?.let { childUrls += reattachToken(url, it.toString()) }
        }
        val checked = checkChildPlaylists(link, headers, childUrls.toList())
        if (checked.failure != null) {
            return ManifestValidation(false, "HLS", true, checked.checked, checked.failure)
        }
        return ManifestValidation(true, "HLS", true, checked.checked)
    }

    /**
     * (v1.0.13 P3-A) DASH validation with Media3's real parser
     * (media3-exoplayer-dash, already a dependency — no new library):
     * DashManifestParser throws ParserException on a malformed MPD,
     * exactly the error ExoPlayer would raise at play time.
     */
    @OptIn(UnstableApi::class)
    private fun validateDash(link: ExtractorLink, url: String, body: String): ManifestValidation {
        return try {
            DashManifestParser().parse(
                Uri.parse(url),
                ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
            )
            ManifestValidation(true, "DASH", false, 0)
        } catch (e: Exception) {
            ManifestValidation(false, "DASH", false, 0, "parser: ${redactUrlForLog(e.message)}")
        }
    }

    /**
     * (v1.0.13 P3-A/L2) Re-attach the top-level URL's query (the signed
     * token) to a resolved child URL that carries none. RFC 3986 drops
     * the query on relative resolution, so a child resolved from a
     * signed master URL loses `?in=...` unless it is put back here —
     * before fetch AND before play (ExoPlayer resolves the same way, so
     * the played manifest's children need the token too; that is a
     * Phase 4 MediaSource concern, logged here for the next phase).
     * NOTE (v1.0.13 P4-A): ZaynHeaderManager.reattachToken is the SAME
     * rule, verbatim, for ExoPlayer's own child/segment requests at
     * play time — the two MUST stay identical.
     */
    private fun reattachToken(topUrl: String, childUrl: String): String {
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

    /**
     * (v1.0.13 P3-A/L2) Probe every child playlist of a multivariant
     * manifest (fetched in parallel, 5 s each, at most 8 — a master
     * playlist rarely declares more). A child that 404s, returns HTML,
     * is not an M3U8 manifest, or has no segments makes the whole link
     * unplayable: the link is skipped and the P2 retry moves on. (P7
     * fix-forward D-2) A child that declares variants is ALSO run through
     * HlsPlaylistParser, and a media child through the minimal EXTINF
     * check (D-1) — the status/head/segment checks alone let a malformed
     * child through to ExoPlayer. The child URI is logged token-redacted,
     * with its status.
     */
    private suspend fun checkChildPlaylists(
        link: ExtractorLink,
        headers: Map<String, String>,
        childUrls: List<String>,
    ): ChildCheckResult = coroutineScope {
        val toCheck = childUrls.take(8)
        if (childUrls.size > toCheck.size) {
            EngineLogger.log("player", "manifest children capped source=${link.source} checked=${toCheck.size} total=${childUrls.size}")
        }
        val results = toCheck.map { childUrl ->
            async {
                try {
                    val fetch = fetchManifestText(link, childUrl, headers, timeoutMs = 5000)
                    // (v1.0.14 C3) A child that is itself a multivariant
                    // master declares STREAM-INF — it must reach the D-2
                    // parser branch below, not die on the segment-presence
                    // check first (masters have no segments by nature).
                    // The no-segments arm is skipped for such children.
                    val childDeclaresVariants = fetch.body.lines().any {
                        it.trim().startsWith("#EXT-X-STREAM-INF") ||
                            it.trim().startsWith("#EXT-X-I-FRAME-STREAM-INF")
                    }
                    val basicFailure = when {
                        fetch.status != 200 ->
                            "child ${redactUrlForLog(childUrl)} HTTP ${fetch.status} contentType=${fetch.contentType}"
                        fetch.contentType.startsWith("text/html") ->
                            "child ${redactUrlForLog(childUrl)} HTML error page status=${fetch.status}"
                        !fetch.body.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U") ->
                            "child ${redactUrlForLog(childUrl)} not an M3U8 manifest"
                        !childDeclaresVariants && !fetch.body.contains("#EXTINF") && !fetch.body.contains("#EXT-X-TARGETDURATION") ->
                            run {
                                // (v1.0.14 CAUSE-2/C1) Manifest fingerprint,
                                // same shape as the top level (see
                                // validateHls note): length, line count,
                                // first tag lines, presence booleans.
                                val tagLines = fetch.body.lines().filter { it.trim().startsWith("#") }.take(10)
                                EngineLogger.log(
                                    "player",
                                    "child no segments source=${link.source} child=${redactUrlForLog(childUrl)} bytes=${fetch.body.length} lines=${fetch.body.lines().size} streamInf=${fetch.body.lines().any { it.contains("#EXT-X-STREAM-INF") }} extinf=${fetch.body.contains("#EXTINF")} targetDuration=${fetch.body.contains("#EXT-X-TARGETDURATION")} tags=[${redactUrlForLog(tagLines.joinToString(" | "))}]",
                                )
                                "child ${redactUrlForLog(childUrl)} media playlist has no segments"
                            }
                        else -> null
                    }
                    if (basicFailure != null) return@async basicFailure
                    // (v1.0.13 P7 fix-forward D-2) The checks above never
                    // parse the child body — a master + malformed child
                    // passed validation while ExoPlayer rejected it. Parse
                    // children that declare variants with the real parser;
                    // a throw fails the child (parser message, redacted).
                    // Media children get the same minimal structural check
                    // as the validateHls media branch (D-1).
                    val body = fetch.body
                    if (body.lines().any {
                            it.trim().startsWith("#EXT-X-STREAM-INF") ||
                                it.trim().startsWith("#EXT-X-I-FRAME-STREAM-INF")
                        }
                    ) {
                        try {
                            HlsPlaylistParser.parse(childUrl, body)
                        } catch (e: Exception) {
                            return@async "child ${redactUrlForLog(childUrl)} parser: ${redactUrlForLog(e.message)}"
                        }
                        // parse() returns null only for non-multivariant
                        // input, which the STREAM-INF check above excludes;
                        // a null here is not a failure.
                        null
                    } else {
                        malformedExtinfLine(body)?.let { reason ->
                            "child ${redactUrlForLog(childUrl)} $reason"
                        }
                    }
                } catch (e: Exception) {
                    // (v1.0.14 C2) Fail-OPEN on transient child failures:
                    // a 5 s timeout / transport error on one child (often
                    // an unselected rendition) must not kill a playable
                    // link — ExoPlayer only fetches the variant it plays.
                    // Verdict failures (HTTP/HTML/parser/D-1 above) stay
                    // fail-closed. A real cancellation is never swallowed.
                    when {
                        e is TimeoutCancellationException -> {
                            EngineLogger.log("player", "child unchecked transient (timeout) source=${link.source} child=${redactUrlForLog(childUrl)}")
                            null
                        }
                        e is kotlinx.coroutines.CancellationException -> throw e
                        e is java.io.IOException -> {
                            EngineLogger.log("player", "child unchecked transient (transport) source=${link.source} child=${redactUrlForLog(childUrl)} reason=${redactUrlForLog(e.message)}")
                            null
                        }
                        else -> "child ${redactUrlForLog(childUrl)} fetch failed: ${redactUrlForLog(e.message)}"
                    }
                }
            }
        }.awaitAll()
        ChildCheckResult(toCheck.size, results.firstOrNull { it != null })
    }

    /**
     * (v1.0.7) Validate the head of a manifest body: an M3U8 playlist
     * starts with #EXTM3U; a DASH manifest contains <MPD (after the XML
     * declaration). A leading BOM / whitespace is tolerated. Anything
     * else (empty body, JSON error, HTML, truncated playlist) is not a
     * valid manifest.
     */
    private fun isValidManifest(type: ExtractorLinkType, head: ByteArray, headLen: Int): Boolean {
        val text = head.copyOf(headLen).decodeToString()
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return when (type) {
            ExtractorLinkType.M3U8 -> text.startsWith("#EXTM3U")
            ExtractorLinkType.DASH -> text.contains("<MPD")
            else -> true
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
        val (resolved, reason) = resolveLinks(params)
        if (resolved == null) {
            return if (reason == "no_data") err("no_data", "This title has no playable data")
            else err("provider_not_found", "Provider not found")
        }
        if (resolved.links.isEmpty()) {
            // (v1.0.23 D) Cold mirror probe: the provider was still
            // finding its server when the play budget ran out — say so
            // instead of the generic timeout, so a retry that would
            // fail identically is not presented as "try again".
            return if (resolved.coldProbeTimeout) err("mirror_probe_timeout", "The provider is still finding its server — try again in a moment")
            else if (resolved.timedOut) err("timeout", "Loading the links timed out — try again")
            else err("no_links", "No playable links found")
        }
        val ranked = rankLinks(resolved.links, params.optString("providerName", ""))
        if (ranked.isEmpty()) {
            // (v0.2.11) C-8: links existed but all were torrent/magnet.
            return err("torrent_only", "This provider only has torrent links, which are not supported")
        }
        val arr = JSONArray()
        for ((i, r) in ranked.withIndex()) arr.put(linkJson(r, i))
        val subs = JSONArray()
        for (s in resolved.subtitles) {
            subs.put(JSONObject().put("lang", s.lang).put("url", s.url).put("source", "provider"))
        }
        return ok(JSONObject().put("links", arr).put("subtitles", subs).put("partial", resolved.partial))
    }

    // (v1.0.12 §7) Serialized entry point: one play() at a time, and a
    // repeated bridge request id returns the cached reply. The bridge id is
    // threaded through call() from MainActivity; nextEp calls play() with a
    // null id (no bridge id exists for an internal re-play).
    private suspend fun play(params: JSONObject, requestId: String? = null): JSONObject {
        // (v1.0.14 P4 rank 4) Bounded one-use dedup (see field comment):
        // same id + same params within 60 s returns the cached reply
        // ONCE; the cache is then cleared.
        if (requestId != null && requestId == lastPlayRequestId && lastPlayResult != null &&
            System.currentTimeMillis() - lastPlayAtMs <= 60_000 &&
            params.toString().hashCode() == lastPlayParamsHash
        ) {
            val cached = lastPlayResult!!
            lastPlayRequestId = null
            lastPlayResult = null
            EngineLogger.log("player", "play dedup request=$requestId — returning cached reply (one-use, consumed)")
            return cached
        }
        val result = playMutex.withLock { playInternal(params, requestId) }
        if (requestId != null) {
            lastPlayRequestId = requestId
            lastPlayResult = result
            lastPlayParamsHash = params.toString().hashCode()
            lastPlayAtMs = System.currentTimeMillis()
        }
        return result
    }

    // (v1.0.12 §7) One log line on every early return, so the next log can
    // classify a play that died before playActive (the c31–c36 residue).
    private fun reject(code: String, message: String, requestId: String?, detail: String? = null): JSONObject {
        EngineLogger.log("player", "play rejected $code request=${requestId ?: "?"}${if (detail != null) " detail=$detail" else ""}")
        return err(code, message, detail)
    }

    private suspend fun playInternal(params: JSONObject, requestId: String? = null): JSONObject {
        // (v1.0.23) No engine guard: ExoPlayer is the only player, so a
        // persisted engine value naming the removed player is never read
        // (the setting no longer exists — EngineStore rejects it as
        // unknown_setting). Routing happens in playActive() below; link
        // resolution, probe, ranking and auto-retry above it are
        // engine-independent and untouched.
        lastProbeSkipReason = null
        val mode = params.optString("mode", store.getSettingString("mode", "auto"))
        val titleForLog = params.optString("title", "")
        val linkIndexForLog = if (params.has("linkIndex") && !params.isNull("linkIndex")) params.optInt("linkIndex", -1) else -1
        EngineLogger.log("player", "play request ${requestId ?: "?"} title=$titleForLog linkIndex=$linkIndexForLog mode=$mode")
        // (v1.0.20 P21-B3) First-frame timing anchor: request start +
        // link-resolve duration; noteFirstFrame() completes the pair.
        val playT0 = System.currentTimeMillis()
        lastPlayT0Ms = playT0
        lastPlaySession = requestId ?: "play-$playT0"
        lastPlayTitle = titleForLog
        lastPlayResolveMs = -1L
        val (resolved, reason) = resolveLinks(params)
        lastPlayResolveMs = System.currentTimeMillis() - playT0
        if (resolved == null) {
            return if (reason == "no_data") reject("no_data", "This title has no playable data", requestId)
            else reject("provider_not_found", "Provider not found", requestId)
        }
        if (resolved.links.isEmpty()) {
            // (v1.0.23 D) Cold mirror probe — distinct cause, see links().
            return if (resolved.coldProbeTimeout) reject("mirror_probe_timeout", "The provider is still finding its server — try again in a moment", requestId)
            else if (resolved.timedOut) reject("timeout", "Loading the links timed out — try again", requestId)
            else reject("no_links", "No playable links found", requestId)
        }
        val ranked = rankLinks(resolved.links, params.optString("providerName", ""))
        if (ranked.isEmpty()) return reject("torrent_only", "This provider only has torrent links, which are not supported", requestId)
        val linkIndex = if (params.has("linkIndex") && !params.isNull("linkIndex")) params.optInt("linkIndex", -1) else -1
        if (mode == "manual" && linkIndex < 0) {
            val arr = JSONArray()
            for ((i, r) in ranked.withIndex()) arr.put(linkJson(r, i))
            return ok(JSONObject().put("links", arr).put("manual", true).put("subtitles", subtitlesJson(resolved.subtitles)))
        }
        val explicitIndex = linkIndex in ranked.indices
        var pickedIdx = if (explicitIndex) linkIndex else -1
        if (!explicitIndex) {
            // (v1.0.5 B1) Probe the top candidates BEFORE playing (Auto
            // only — an explicit server pick is the user's choice; a bad
            // explicit pick is handled by the auto-retry instead). The
            // first candidate that looks playable wins; at most 3 probes.
            val probeLimit = if (ranked.size < 3) ranked.size else 3
            for (i in 0 until probeLimit) {
                val c = ranked[i]
                if (c.link is DrmExtractorLink) continue
                if (probePlayable(c.link, params.optString("providerName", ""))) {
                    pickedIdx = i
                    break
                }
                EngineLogger.log("player", "play skipped unplayable pick ${c.link.source} ${redactUrlForLog(c.link.url)}")
            }
            if (pickedIdx < 0) {
                // (v1.0.14 CAUSE-2) Say WHY: the validator's verdict (e.g.
                // "media playlist has no segments") rides in detail, so
                // the toast names the cause instead of the generic line.
                // engineErrText appends detail verbatim (SEEK-TRACE §4b).
                return if (lastProbeSkipReason != null) {
                    reject("playback_error", "No playable links found", requestId, lastProbeSkipReason)
                } else {
                    reject("playback_error", "No playable link found — the best links look like web pages, not video", requestId)
                }
            }
        }
        val picked = ranked[pickedIdx]
        val link = picked.link
        if (link is DrmExtractorLink) {
            return reject("playback_error", "This stream uses DRM, which is not supported", requestId)
        }
        // (v1.0.12 §7) Same title + same explicit link while that session is
        // already loaded: return ok with the existing session instead of
        // re-preparing. Auto re-plays (linkIndex -1) and other-server picks
        // never match, and an ended session (hasActiveMedia false) replays.
        val title = params.optString("title", "")
        val ap0 = activePlay
        if (explicitIndex && ap0 != null && ap0.title == title && ap0.index == pickedIdx &&
            player.hasActiveMedia()
        ) {
            EngineLogger.log("player", "play dedup same title+link session=${ap0.session} request=${requestId ?: "?"} — returning existing session")
            val existing = ap0.ranked[ap0.index]
            return ok(
                JSONObject()
                    .put("playing", true)
                    .put(
                        "link",
                        JSONObject()
                            .put("source", existing.link.source)
                            .put("qualityLabel", qualityLabel(existing.link.quality))
                            .put("audio", existing.audio ?: JSONObject.NULL),
                    )
                    .put("reused", true),
            )
        }
        // (v1.0.18 P17-09) Reuse is content-keyed (url + provider +
        // episode), never bare-title.
        val contentKey = library.progressKey(
            params.optString("url", ""),
            params.optString("providerName", ""),
            params.optString("ep", ""),
        )
        val reuse = store.getSettingBoolean("reuse", true)
        var reused = false
        if (reuse && title.isNotBlank()) {
            val last = lastLinkEntry(title, contentKey)
            if (last != null) {
                val at = last.optLong("atMs", 0)
                val durH = parseHours(store.getSettingString("reuseDur", "24h"))
                if (last.optInt("linkIndex", -1) == pickedIdx &&
                    System.currentTimeMillis() - at < durH * 3600_000L
                ) reused = true
            }
        }
        // (v1.0.14 G1c) An explicit startAtSec (even 0) from the UI is
        // authoritative — JS always sends it now, so a "start at 0"
        // decision can no longer fall through to a stored position. The
        // fallback below only fires for callers that omit the key, and it
        // is content-keyed too (url + provider + episode), never bare-title.
        // (v1.0.18 P17-08) resume=false forces startAtSec 0 even for an
        // explicit startAtSec: the setting is the user's word, and the
        // resume prompt is not shown when resume is off, so no legitimate
        // caller sends a resume position in that state. (V1.0.14 G1c's
        // explicit-startAtSec authority applies while resume is ON.)
        val startAtSec = when {
            !store.getSettingBoolean("resume", true) -> 0
            params.has("startAtSec") -> params.optInt("startAtSec", 0)
            title.isNotBlank() ->
                library.progressGet(
                    title,
                    params.optString("url", ""),
                    params.optString("providerName", ""),
                    params.optString("ep", ""),
                ).optInt("posSec", 0)
            else -> 0
        }
        // (v1.0.4) Never seek past the saved end of THIS title: a stale
        // resume (e.g. another title's 23 min) used to seek past the end,
        // which ended playback at once ("starts at 23 min then cuts").
        val savedDurSec = library.progressGet(
            title,
            params.optString("url", ""),
            params.optString("providerName", ""),
            params.optString("ep", ""),
        ).optInt("durSec", 0)
        val safeStartSec = if (startAtSec > 0 && savedDurSec > 0 && startAtSec >= savedDurSec - 30) 0 else startAtSec
        // (v1.0.18 P17-03) subLang "Off" used to fall through to "bn"
        // (audioCodeOf("Off") returns null -> ?: "bn"), so subtitles
        // still showed. Off now suppresses every subtitle input.
        val subLangOff = store.getSettingString("subLang", "বাংলা") == "Off"
        val subLangCode = if (subLangOff) null else audioCodeOf(store.getSettingString("subLang", "বাংলা")) ?: "bn"
        // (v1.0.18 P17-02) subFilter: only subtitles in the chosen language
        // may be picked — the preferred-language pick AND the fallback.
        val filterCode = store.getSettingString("subFilter", "All").let { if (it == "All") null else audioCodeOf(it) }
        // (v1.0.22 B) In-player subtitle pick (JS curSubSel, via playParamsFor) beats the Settings pick: a provider URL rides as the subtitle, Off suppresses every subtitle input (including a downloaded text). No pick -> Settings behaviour unchanged.
        val subUrlOverride = if (params.has("subtitleUrl") && !params.isNull("subtitleUrl")) params.optString("subtitleUrl") else null
        val subOffOverride = params.optBoolean("subtitleOff", false)
        val providerSub = if (subOffOverride || subUrlOverride != null) null else resolved.subtitles.firstOrNull { (it.langTag == subLangCode || it.lang == subLangCode) && (filterCode == null || it.langTag == filterCode || it.lang == filterCode) }
            ?: if (subOffOverride || subUrlOverride != null) null else resolved.subtitles.firstOrNull { filterCode == null || it.langTag == filterCode || it.lang == filterCode }
        val subtitleText = if (subOffOverride) null else if (params.has("subtitleText") && !params.isNull("subtitleText")) params.optString("subtitleText") else null
        val subtitleLang = if (subOffOverride) null else if (params.has("subtitleLang")) params.optString("subtitleLang") else subLangCode
        val subtitleUrl = subUrlOverride ?: providerSub?.url
        val playLink = ZaynPlayer.PlayLink(
            url = link.url,
            source = link.source,
            qualityLabel = qualityLabel(link.quality),
            audio = picked.audio,
            referer = link.referer,
            headers = ZaynHeaderManager.headersFor(link),
            type = link.type,
        )
        // (v1.0.13 P2-B T1) Log PlayLink.url (token-redacted) at construction:
        // the V1.0.12 log showed the probe validating one token and the player
        // preparing another (INVESTIGATION-4 §3.6 T1) — with the URL logged at
        // construction, buildMediaItem and prepare, a second play path (if one
        // exists) becomes visible.
        EngineLogger.log("player", "playLink constructed url=${redactUrlForLog(link.url)} source=${link.source}${tokenAgeSuffix(link.url)}")
        // (v1.0.12 §7/§8) The session id is fixed BEFORE playActive so the
        // players' diagnostics carry it, and so the session the retry guard
        // reads is the one that just started.
        playSessionCounter++
        val session = playSessionCounter
        player.diagSessionId = session
        playActive(playLink, safeStartSec * 1000L, subtitleText, subtitleLang, subtitleUrl, cacheSizeBytes(), bufferLevel(), preferredAudioLang(picked.audio))
        // (v1.0.13 P2-C P7) Player Selected.
        providerDiag("P7", "Player Selected", "engine=exoplayer", params.optString("providerName", ""))
        // (v1.0.5 B2/B3) Arm the auto-retry session (attempt 1 of 3) and
        // name the picked link (URL + type + quality) in the log.
        // (v1.0.13 P6-C) A new play clears the dead-session latch from any
        // earlier failed session — the new session owns the controls now.
        playbackFailed = false
        activePlay = ActivePlay(ranked, pickedIdx, 1, session, safeStartSec, subtitleText, subtitleLang, subtitleUrl, title, params)
        EngineLogger.log("player", "play ${link.source} ${qualityLabel(link.quality)} type=${link.type} url=${redactUrlForLog(link.url)} session=$session${tokenAgeSuffix(link.url)}")
        if (title.isNotBlank()) {
            putLastLink(title, contentKey, pickedIdx)
            // (v1.0.16 P11-01) Playing NEVER auto-adds to the Library:
            // the master prompt's rule is explicit-bookmark ONLY. The old
            // library.markWatching call here is deleted; Continue Watching
            // is built from the progress store instead (JS buildCW).
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
                .put("reused", reused)
                .put("subtitles", subtitlesJson(resolved.subtitles)),
        )
    }

    /**
     * (v1.0.18 P17-08, G-11) The reuse-duration parser ignored the
     * unit: "7d" parsed as 7 HOURS. Exact table: "7d" -> 168 hours,
     * "24h" -> 24, "12h" -> 12, a bare number is hours.
     */
    private fun parseHours(raw: String): Long {
        val num = Regex("""(\d+)""").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: return 24L
        return if (raw.contains('d')) num * 24L else num
    }

    /**
     * (v1.0.18 P17-09, G-11) The cache-size parser ignored the unit:
     * "1 GB" became 1 MB. Exact table: "1 GB" -> 1073741824 bytes,
     * "250 MB" -> 250 MB. The RESULT is clamped to 16–2048 MB (the old
     * code clamped the raw number, so "1 GB" would have clamped to 1).
     */
    private fun cacheSizeBytes(): Long {
        val raw = store.getSettingString("bufDisk", "250 MB")
        val num = Regex("""(\d+)""").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 250L
        val mb = if (raw.contains("GB", ignoreCase = true)) num * 1024L else num
        return mb.coerceIn(16, 2048) * 1024L * 1024L
    }

    /**
     * (v1.0.18 P17-02) Subtitle style as one JSON object: the
     * ExoPlayer HTML overlay reads the values from Settings.
     * (v1.0.23) The removed player's sub-* options are gone with it.
     */
    private fun subtitleStyleJson(): JSONObject = JSONObject()
        .put("subColor", store.getSettingString("subColor", "সাদা"))
        .put("subStyle", store.getSettingString("subStyle", "outline"))
        .put("subSize", store.getSettingString("subSize", "medium"))
        .put("subPosV", store.getSettingString("subPosV", "bottom"))
        .put("subPosH", store.getSettingString("subPosH", "center"))

    /**
     * (v1.0.18 P17-09) The reuse/last-link key is the CONTENT key
     * (url + provider + episode), never the bare title: two different
     * titles, and two episodes of one series, must not share a reuse
     * window. One-time legacy adoption: a row stored under the bare
     * title in V1.0.17 moves to the content key on first read, and the
     * title-keyed row is removed so it can never be read again.
     */
    private fun lastLinkEntry(title: String, contentKey: String): JSONObject? {
        store.mapEntry(EngineStore.KEY_LAST_LINK, contentKey)?.let { return it }
        if (title.isBlank() || title == contentKey) return null
        val legacy = store.mapEntry(EngineStore.KEY_LAST_LINK, title) ?: return null
        store.putMapEntry(EngineStore.KEY_LAST_LINK, contentKey, legacy)
        store.removeMapEntry(EngineStore.KEY_LAST_LINK, title)
        return legacy
    }

    private fun putLastLink(title: String, contentKey: String, linkIndex: Int) {
        store.putMapEntry(
            EngineStore.KEY_LAST_LINK,
            contentKey,
            JSONObject().put("linkIndex", linkIndex).put("atMs", System.currentTimeMillis()),
        )
        if (title.isNotBlank() && title != contentKey) {
            store.removeMapEntry(EngineStore.KEY_LAST_LINK, title)
        }
    }

    /** (v1.0.4) Buffering level (Settings > Playback > Buffering). */
    private fun bufferLevel(): String = store.getSettingString("buffer", "normal")

    /**
     * (v1.0.4) NO-SWITCH rule: ExoPlayer's own track selector used to pick
     * the manifest's default audio (e.g. Tamil) mid-play even though the
     * picked link was Hindi. Pin the selector to the picked link's
     * language, else the preferred-audio setting.
     */
    private fun preferredAudioLang(linkAudio: String?): String? {
        if (linkAudio != null) return linkAudio
        return audioCodeOf(store.getSettingString("audio1", "English"))
    }

    private suspend fun playerControl(params: JSONObject, requestId: String? = null): JSONObject {
        val action = params.optString("action", "")
        // (v1.0.7) Log the action on every call: the V1.0.6 log recorded
        // playerControl ids only, so whether the 4x "first frame rendered"
        // re-fires were seeks or surface re-attachment was unknowable.
        // (v1.0.14 SEEK-TRACE §4a.2) Log the seek value so the log can
        // correlate a thrown seek with its target.
        EngineLogger.log("player", "playerControl $action" + if (action == "seek") " value=${params.optDouble("value")}" else "")
        // (v1.0.13 P6-C) Dead-session latch (INVESTIGATION-4 §7.3): after a
        // fatal error the session is over — seek/resume/toggle/rate would
        // silently no-op on the dead player, so refuse them with
        // playback_error until the next play() clears the flag.
        // Volume/mute stay live (harmless on a dead session).
        if (playbackFailed && (action == "seek" || action == "resume" || action == "toggle" || action == "rate")) {
            EngineLogger.log("player", "playerControl $action rejected — dead session (playback_error)")
            return err("playback_error", "Video could not be played")
        }
        return when (action) {
            "toggle", "pause", "resume", "seek", "rate", "volume", "mute" -> {
                val value = if (params.has("value") && !params.isNull("value")) params.optDouble("value") else null
                val state = player.control(action, value)
                ok(JSONObject().put("state", state))
            }
            "setSubtitle" -> {
                val text = if (params.has("text") && !params.isNull("text")) params.optString("text") else null
                player.setSubtitleText(text, params.optString("lang", ""))
                ok(JSONObject().put("state", activeStateSnapshot()))
            }
            // (v1.0.22 B) In-player audio/subtitle picks, applied to the CURRENT stream (no re-play). setAudio/setSubtitleTrack select a manifest track by id (or language); setSubtitleUrl attaches a provider subtitle URL; setSubtitleOff clears every subtitle. A pick the KNOWN inventory contradicts is refused (invalid_input) instead of silently keeping the old track; with no inventory yet the pick is accepted and the JS confirm loop (playerTracks re-query) verifies the selected track.
            "setAudio" -> {
                val ok2 = player.setAudioTrack(params.optString("trackId", ""), params.optString("lang", ""))
                if (ok2) ok(JSONObject().put("state", activeStateSnapshot())) else err("invalid_input", "That audio track is not available")
            }
            "setSubtitleTrack" -> {
                val ok2 = player.setSubtitleTrack(params.optString("trackId", ""), params.optString("lang", ""))
                if (ok2) ok(JSONObject().put("state", activeStateSnapshot())) else err("invalid_input", "That subtitle track is not available")
            }
            "setSubtitleUrl" -> {
                val url = params.optString("url", "")
                if (url.isBlank()) return err("invalid_input", "No subtitle URL")
                player.setSubtitleUrl(url, params.optString("lang", ""))
                ok(JSONObject().put("state", activeStateSnapshot()))
            }
            "setSubtitleOff" -> {
                player.setSubtitleOff()
                ok(JSONObject().put("state", activeStateSnapshot()))
            }
            "nextEp", "prevEp" -> {
                // Episode switch is driven by play with the episode's data.
                // (v1.0.14 P4 rank 10) The outer bridge id is passed in as
                // a derived id ("<id>/nextEp") so the internal re-play is
                // logged + deduped against the outer call, not as an
                // unrelated anonymous play.
                val epData = params.optString("episodeData", "")
                if (epData.isBlank()) return err("invalid_input", "No next episode")
                val playParams = JSONObject(params.toString())
                playParams.put("data", epData)
                play(playParams, if (requestId != null) "$requestId/$action" else null)
            }
            else -> err("invalid_input", "Unknown player action")
        }
    }

    // (v1.0.22 C ITEM 7) Aspect ratio for the player: the page's CSS
    // classes only restyle the DOM poster/img — the native TextureView
    // needs the player to stretch/crop/fit the frame itself. The page
    // also re-reports the stage rect (playerStage) sized to the new
    // frame, so the view and the player agree on the target aspect.
    private fun playerAspect(params: JSONObject): JSONObject {
        val ratio = params.optString("ratio", "fit").ifBlank { "fit" }
        val ok = player.setAspectRatio(ratio)
        return if (ok) ok(JSONObject().put("ratio", ratio))
        else err("invalid_input", "Unknown aspect ratio")
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
        val (resolved, reason) = resolveLinks(params)
        if (resolved == null) {
            return if (reason == "no_data") err("no_data", "This title has no playable data")
            else err("provider_not_found", "Provider not found")
        }
        val ranked = rankLinks(resolved.links, params.optString("providerName", ""))
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
        // (v0.2.11) C-16: playback-end calls librarySet with the bookmark
        // id only ({id, status}); resolve by id instead of dropping it.
        val libId = params.optString("id", "")
        if (libId.isNotBlank()) {
            return ok(library.librarySetById(libId, params.optString("status", "")))
        }
        return ok(
            library.librarySet(
                params.optString("url", ""),
                params.optString("providerName", ""),
                params.optString("status", ""),
                params.optString("title", ""),
                params.optString("type", "movie"),
                params.optString("poster", ""),
                params.optString("year", ""),
                params.optString("syn", ""),
            ),
        )
    }

    // ---- E22–E25: downloads ----

    private suspend fun downloadStart(params: JSONObject): JSONObject {
        val title = params.optString("title", "")
        val url = params.optString("url", "")
        if (title.isBlank() || url.isBlank()) return err("invalid_input", "Missing title or link")
        val (resolved, reason) = resolveLinks(params)
        if (resolved == null) {
            return if (reason == "no_data") err("no_data", "This title has no playable data")
            else err("provider_not_found", "Provider not found")
        }
        val ranked = rankLinks(resolved.links, params.optString("providerName", ""))
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
        // (v1.0.18 P16-13) Quota: refuse BEFORE any byte is written when
        // free space is below the 100 MB floor (a download that fills the
        // disk mid-write corrupts the file AND the device). The error
        // names the floor so the UI can say it plainly.
        val freeBytes = downloads.freeBytes()
        if (freeBytes in 0 until 100L * 1024L * 1024L) {
            return err("storage_full", "Not enough free space to download (need 100 MB free)")
        }
        // (v1.0.16 P10-13) Stronger download id: SHA-256 of title + page
        // URL + episode data (when a series episode is chosen), first 16
        // hex chars, keeping the "dl-" prefix. The old 32-bit hashCode id
        // could collide across different title+URL pairs and silently
        // overwrite the other download's entry/file.
        val idInput = title + "|" + url + "|" + params.optString("episodeData", "")
        val idDigest = MessageDigest.getInstance("SHA-256").digest(idInput.toByteArray(Charsets.UTF_8))
        val idHex = idDigest.joinToString("") { "%02x".format(it) }.take(16)
        val id = "dl-$idHex"
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
        val entry = downloads.downloadEntry(id)
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
        // (v1.0.12 §8) Attribute the download play to the current session
        // (no new session is armed for a download open).
        player.diagSessionId = playSessionCounter
        playActive(link, 0, null, null, null, cacheSizeBytes())
        // (v1.0.16 P10-01) The old reply ({launched:true} only) made the
        // JS handler wait for a `playing` flag that was never sent, so the
        // tap did nothing visible while playback had already started
        // behind the Downloads screen. Reply playing:true + the title so
        // the UI can navigate to the player and name the title.
        return ok(
            JSONObject()
                .put("launched", true)
                .put("playing", true)
                .put("title", entry?.optString("title", "") ?: ""),
        )
    }

    // ---- E31–E34: accounts ----

    private fun accountLoginUrl(provider: String): JSONObject {
        if (provider !in ZaynAccounts.PROVIDERS) return err("invalid_input", "Unknown account")
        // Key-gated: no client key exists in the repo for any provider.
        EngineLogger.log("accounts", "loginUrl $provider gated (no key)")
        return err("auth_failed", "Login is not available yet")
    }

    // ---- E35–E38: backup / cache / log ----

    // (v1.0.18 P16-04) Format 0.3.0 adds provHealthDetail; tokens are
    // still never exported. The JSON is built by ZaynBackup so the
    // manual save and the scheduled worker write the identical file.
    private fun backupExport(): JSONObject {
        val json = ZaynBackup.exportJson(activity, store)
        return JSONObject().put("json", json.toString(2)).put("fileName", ZaynBackup.FILE_NAME)
    }

    /**
     * (v1.0.18 P16-04) The NATIVE backup save: JS calls backupSave,
     * the file is written natively into the chosen backupFolder, and
     * the toast fires ONLY on a confirmed write (path + bytes named).
     * The old blob-click path could never save in this WebView.
     */
    private fun backupSave(): JSONObject {
        val json = ZaynBackup.exportJson(activity, store)
        val folder = store.getSettingString("backupFolder", "/Zayn Play/Backup")
        return try {
            val file = ZaynBackup.writeToFolder(activity, folder, json)
            store.setSetting("lastBackup", file.name)
            ok(
                JSONObject()
                    .put("fileName", file.name)
                    .put("path", file.absolutePath)
                    .put("folder", ZaynBackup.folderDisplayPath(activity, folder))
                    .put("bytes", file.length()),
            )
        } catch (e: Exception) {
            EngineLogger.log("backup", "backupSave failed: ${e.javaClass.simpleName}: ${e.message}")
            err("backup_failed", "Could not write the backup file")
        }
    }

    /**
     * (v1.0.18 P16-04) Import, rewritten: library/progress/downloads
     * MERGE by id (newest updatedAt wins; nothing on the device is
     * lost) instead of the old wholesale replace; downloads and BOTH
     * health maps are actually restored (0.2.0 import ignored them);
     * unknown (removed-schema) keys are skipped + counted, NOT an
     * error; an INVALID VALUE for a known key fails the whole import
     * BEFORE anything is applied, naming the keys. Accepts 0.3.0 and
     * 0.2.0, plus the legacy 0.1.7 shape.
     */
    private suspend fun backupImport(jsonText: String): JSONObject {
        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return err("invalid_backup", "That file is not a Zayn Play backup")
        }
        val format = json.optString("format", "")
        if (format != ZaynBackup.FORMAT && format != "zaynplay-backup-0.2.0" && !json.has("installed")) {
            return err("invalid_backup", "That file is not a Zayn Play backup")
        }
        // Validate settings FIRST: nothing is applied until the whole
        // file's known keys check out.
        val settingsObj = if (json.has("settings")) json.optJSONObject("settings") else null
        val invalidKeys = JSONArray()
        var unknownKeys = 0
        if (settingsObj != null) {
            val keys = settingsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (store.validateSetting(k, settingsObj.opt(k))) {
                    null -> Unit
                    "unknown_setting" -> unknownKeys++
                    else -> invalidKeys.put(k)
                }
            }
        }
        if (invalidKeys.length() > 0) {
            return err(
                "invalid_backup",
                "Backup has invalid values for: ${(0 until invalidKeys.length()).joinToString(", ") { invalidKeys.optString(it) }}",
            )
        }
        var restoredRepos = 0
        val reposArr = if (json.has("repos")) json.optJSONArray("repos") else null
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
        if (format != ZaynBackup.FORMAT && format != "zaynplay-backup-0.2.0") {
            val legacyProvOn = json.optJSONObject("provOn")
            if (legacyProvOn != null) store.writeJsonMap("zayn/provOn", legacyProvOn)
            return ok(JSONObject().put("restored", true).put("reposInstalled", restoredRepos))
        }
        var settingsApplied = 0
        if (settingsObj != null) {
            val keys = settingsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (store.setSetting(k, settingsObj.opt(k)) == null) settingsApplied++
            }
        }
        val provOnMerged = mergeProviderMap("zayn/provOn", json.optJSONObject("provOn"))
        val provAutoOffMerged = mergeProviderMap("zayn/provAutoOff", json.optJSONObject("provAutoOff"))
        val provHealthMerged = mergeProviderMap("zayn/provHealth", json.optJSONObject("provHealth"))
        val provHealthDetailMerged = mergeProviderMap("zayn/provHealthDetail", json.optJSONObject("provHealthDetail"))
        val libraryMerged = mergeRowMap(EngineStore.KEY_LIBRARY, json.optJSONObject("library"))
        val progressMerged = mergeRowMap(EngineStore.KEY_PROGRESS, json.optJSONObject("progress"))
        val downloadsMerged = mergeRowMap(EngineStore.KEY_DOWNLOADS, json.optJSONObject("downloads"))
        return ok(
            JSONObject()
                .put("restored", true)
                .put("reposInstalled", restoredRepos)
                .put("settingsApplied", settingsApplied)
                .put("settingsSkippedUnknown", unknownKeys)
                .put("provOnMerged", provOnMerged)
                .put("provAutoOffMerged", provAutoOffMerged)
                .put("provHealthMerged", provHealthMerged)
                .put("provHealthDetailMerged", provHealthDetailMerged)
                .put("libraryMerged", libraryMerged)
                .put("progressMerged", progressMerged)
                .put("downloadsMerged", downloadsMerged),
        )
    }

    /**
     * Merge one row map (library / progress / downloads) by id: the
     * backup row wins only when it is newer (updatedAt); a row the
     * backup does not have stays untouched. Returns merged count.
     */
    private fun mergeRowMap(key: String, incoming: JSONObject?): Int {
        if (incoming == null) return 0
        var merged = 0
        val keys = incoming.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val newEntry = incoming.optJSONObject(k) ?: continue
            val oldEntry = store.mapEntry(key, k)
            val newAt = newEntry.optLong("updatedAt", 0L)
            val oldAt = oldEntry?.optLong("updatedAt", 0L) ?: 0L
            if (oldEntry == null || newAt >= oldAt) {
                store.putMapEntry(key, k, newEntry)
                merged++
            }
        }
        return merged
    }

    /**
     * Merge one provider map (provOn / provAutoOff / provHealth /
     * provHealthDetail): backup keys overlay the existing map, keys
     * the backup does not have stay. Returns merged count.
     */
    private fun mergeProviderMap(key: String, incoming: JSONObject?): Int {
        if (incoming == null) return 0
        val merged = JSONObject(store.readJsonMap(key).toString())
        var count = 0
        val keys = incoming.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            merged.put(k, incoming.opt(k))
            count++
        }
        store.writeJsonMap(key, merged)
        return count
    }

    /** Size of a file or directory tree (bytes). Shared by cacheClear / cacheSize. */
    private fun dirSize(f: File): Long {
        if (!f.exists()) return 0
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { dirSize(it) } ?: 0
    }

    private fun deleteContents(f: File) {
        f.listFiles()?.forEach {
            try {
                if (it.isDirectory) it.deleteRecursively() else it.delete()
            } catch (e: Exception) {
            }
        }
    }

    /**
     * (v1.0.18 P16-06) cacheClear now clears the WebView cache too
     * (via MainActivity's UI-thread callback) and the poster prefetch
     * cache, and reports the REAL freed MB measured before/after —
     * never the old hardcoded 248 MB. WebView.clearCache(true) runs
     * async on the UI thread, so its bytes cannot be re-measured
     * synchronously afterwards: they are counted from the pre-clear
     * measurement (webViewBytesBefore) and reported separately as
     * webViewMB too. freedMB therefore covers cacheDir + poster +
     * WebView, all measured, none invented.
     */
    private suspend fun cacheClear(): JSONObject {
        val cacheDir = activity.cacheDir
        val extCache = activity.externalCacheDir
        val webViewDir = File(activity.applicationInfo.dataDir, "app_webview")
        val posterBytesBefore = posterCache.sizeBytes()
        val webViewBytesBefore = dirSize(webViewDir)
        val before = dirSize(cacheDir) + (extCache?.let { dirSize(it) } ?: 0) + posterBytesBefore
        // Release the player first (its SimpleCache lives in cacheDir).
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            player.clearCache { cont.resume(Unit) }
        }
        posterCache.clear()
        clearWebViewCache?.invoke()
        deleteContents(cacheDir)
        if (extCache != null) deleteContents(extCache)
        val after = dirSize(cacheDir) + (extCache?.let { dirSize(it) } ?: 0) + posterCache.sizeBytes()
        // WebView bytes (pre-clear measurement — the async clear has
        // already been requested above) join the re-measured delta.
        val freedMB = ((before - after).coerceAtLeast(0) + webViewBytesBefore) / 1048576.0
        EngineLogger.log("cache", "cleared ${"%.1f".format(freedMB)} MB (webView ${"%.1f".format(webViewBytesBefore / 1048576.0)} MB via WebView.clearCache)")
        return JSONObject()
            .put("freedMB", freedMB)
            .put("webViewCleared", clearWebViewCache != null)
            .put("webViewMB", webViewBytesBefore / 1048576.0)
            .put("posterBytes", posterBytesBefore)
    }

    /** (v1.0.18 P16-06) Real cache sizes, for the Settings cache page. */
    private fun cacheSize(): JSONObject {
        val webViewDir = File(activity.applicationInfo.dataDir, "app_webview")
        val cacheBytes = dirSize(activity.cacheDir) +
            (activity.externalCacheDir?.let { dirSize(it) } ?: 0)
        return JSONObject()
            .put("cacheMB", cacheBytes / 1048576.0)
            .put("webViewMB", dirSize(webViewDir) / 1048576.0)
            .put("posterMB", posterCache.sizeBytes() / 1048576.0)
            .put("posterBytes", posterCache.sizeBytes())
    }

    /** (v1.0.18 P17-23) Persist custom sites (Room) from the clone page. */
    private fun customSitesSet(params: JSONObject): JSONObject {
        val sites = params.optJSONObject("sites")
            ?: return err("invalid_input", "Missing sites")
        store.setCustomSites(sites)
        return ok(JSONObject().put("sites", store.customSitesMap()))
    }

    /** (v1.0.18 P17-21) Real settings reset: settings only, never the
        library/progress/downloads. The confirm lives in JS. */
    private fun settingsReset(): JSONObject {
        val reset = store.resetSettingsToDefaults()
        return JSONObject().put("reset", reset)
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

    private fun episodeJson(e: Episode, dub: String? = null): JSONObject = JSONObject()
        .put("data", e.data)
        .put("name", e.name ?: JSONObject.NULL)
        .put("season", e.season ?: JSONObject.NULL)
        .put("episode", e.episode ?: JSONObject.NULL)
        .put("posterUrl", e.posterUrl ?: JSONObject.NULL)
        .put("score", e.score?.toDouble(10) ?: JSONObject.NULL)
        .put("description", e.description ?: JSONObject.NULL)
        .put("date", e.date ?: JSONObject.NULL)
        .put("runTime", e.runTime ?: JSONObject.NULL)
        // (v1.0.16 P12-07) Anime dub status, carried per episode instead
        // of flattened away: "subbed" | "dubbed" | absent (unknown).
        .put("dub", dub ?: JSONObject.NULL)

    private fun detailsJson(resp: LoadResponse): JSONObject {
        val episodes = JSONArray()
        val seasonNames = JSONArray()
        var data: Any? = JSONObject.NULL
        when (resp) {
            is MovieLoadResponse -> data = resp.dataUrl
            is LiveStreamLoadResponse -> data = resp.dataUrl
            is TvSeriesLoadResponse -> {
                data = resp.url
                for (e in resp.episodes) episodes.put(episodeJson(e))
                // (v1.0.16 P12-01) Season display names, when the provider
                // supplies them (MainAPI TvSeriesLoadResponse.seasonNames).
                for (s in resp.seasonNames ?: emptyList()) {
                    seasonNames.put(
                        JSONObject()
                            .put("season", s.season)
                            .put("name", s.name ?: JSONObject.NULL)
                            .put("displaySeason", s.displaySeason ?: JSONObject.NULL),
                    )
                }
            }
            is AnimeLoadResponse -> {
                data = resp.url
                for ((dubStatus, list) in resp.episodes) {
                    val dub = when (dubStatus.name) {
                        "Dubbed" -> "dubbed"
                        "Subbed" -> "subbed"
                        else -> null
                    }
                    for (e in list) episodes.put(episodeJson(e, dub))
                }
            }
            else -> data = resp.url
        }
        val recs = JSONArray()
        for (r in resp.recommendations ?: emptyList()) recs.put(searchItemJson(r))
        val cast = JSONArray()
        // (v1.0.16 P12-06) Cast photos + roles were dropped by the old
        // names-only bridge; carry them (null image = initials placeholder
        // in the UI, never a broken image or an invented face).
        for (a in resp.actors ?: emptyList()) {
            cast.put(
                JSONObject()
                    .put("name", a.actor.name)
                    .put("image", a.actor.image ?: JSONObject.NULL)
                    .put("role", a.roleString ?: a.role?.name ?: JSONObject.NULL),
            )
        }
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
            .put("seasonNames", seasonNames)
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

    /** (v0.2.11) C-5: up to the first 3 episodes' data for the test. */
    private fun loadDataCandidates(resp: LoadResponse): List<String> = when (resp) {
        is MovieLoadResponse -> listOfNotNull(resp.dataUrl)
        is LiveStreamLoadResponse -> listOfNotNull(resp.dataUrl)
        is TvSeriesLoadResponse -> resp.episodes.take(3).mapNotNull { it.data }
        is AnimeLoadResponse -> resp.episodes.values.take(3).mapNotNull { it.firstOrNull()?.data }
        else -> listOfNotNull(resp.url)
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

    /** (v0.2.11) C-10: ON providers as (repoId, name) pairs, same order. */
    private fun onProviderPairsOrdered(): List<Pair<String, String>> {
        val pairs = store.providerOnPairs()
        val order = store.getSettingStringList("providerOrder", emptyList())
        val names = pairs.map { it.second }.toSet()
        val orderedNames = order.filter { it in names } + (names - order.toSet()).sorted()
        return orderedNames.mapNotNull { n -> pairs.firstOrNull { it.second == n } }
    }

    companion object {
        fun repoId(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "repo-" + hex.take(16)
        }

        /**
         * (v0.2.11) Target 15 — NO repos by default: the baked catalogue
         * is REMOVED (it superseded the 2026-11-25 "take ALL repos"
         * approach and the v0.2.1/v0.2.10 catalogue work). A fresh
         * install has zero repos; repos exist only after the user adds
         * one manually by shortcode/URL (repoAdd -> parseRepoUrl).
         * findRepo() still resolves installed repos by URL and by hash
         * id; catalogueIdOf() now always returns null.
         */
        private val CATALOGUE_REPOS: List<Pair<String, String>> = listOf()
        private val CATALOGUE_URL_BY_ID: Map<String, String> = CATALOGUE_REPOS.toMap()
        private val CATALOGUE_ID_BY_URL: Map<String, String> =
            CATALOGUE_REPOS.associate { (id, url) -> url to id }
    }

    private fun ok(data: JSONObject): JSONObject =
        JSONObject().put("ok", true).put("data", data)

    private fun err(code: String, message: String, detail: String? = null): JSONObject {
        // (v1.0.2) Optional raw detail (e.g. the exception behind a
        // load_failed) so the UI can name it verbatim — the toggle toast
        // appends e.detail, exactly like the Test row already does.
        val obj = JSONObject().put("ok", false).put("code", code).put("message", message)
        if (detail != null) obj.put("detail", detail)
        return obj
    }
}
