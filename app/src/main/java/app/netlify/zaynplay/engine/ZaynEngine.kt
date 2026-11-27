package app.netlify.zaynplay.engine

import android.app.Activity
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // (v1.0.10) The libmpv player, owned by MainActivity like ZaynPlayer.
    // Both players share the one TextureView; only the active engine plays.
    private val mpvPlayer: ZaynMpvPlayer,
    private val downloads: ZaynDownloads,
    private val bridgeScope: CoroutineScope,
    private val onEvent: (String, JSONObject) -> Unit = { _, _ -> },
    private val onStageRect: (JSONObject) -> Unit,
) {
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
        val safeStartSec: Int,
        val subtitleText: String?,
        val subtitleLang: String?,
        val providerSubUrl: String?,
        val title: String,
        // (v1.0.6 R1) The original play() params, so a retry can re-resolve
        // links (fresh loadLinks) when the ranked list is exhausted.
        val params: JSONObject,
        // (v1.0.13 P2-B) The other engine has already been tried once for a
        // MALFORMED manifest in this session — never try it twice.
        var malformedEngineSwitched: Boolean = false,
        // (v1.0.13 P4-B) A fresh re-resolve has already been spent once
        // for a MANIFEST_AUTH failure in this session — never spend it twice.
        var authReresolved: Boolean = false,
        // (v1.0.13 P6-B) The other engine has already been tried once for a
        // DECODE_ERROR / UNSUPPORTED_FORMAT failure in this session — never
        // try it twice.
        var decodeEngineSwitched: Boolean = false,
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

    // (v1.0.13 P2-B) One-shot engine override for the MALFORMED retry
    // (retryOtherEngine): set just before the retry's playActive call and
    // cleared right after it returns. Only that one play is affected.
    private var oneShotEngineOverride: String? = null

    // (v1.0.12 §7) Duplicate-play guard: one play() at a time (a new user
    // play supersedes the old one — the old one's activePlay is replaced
    // anyway), plus request-id dedup (a repeated bridge id returns the
    // cached reply) so the c31–c36 residue can never recur.
    private val playMutex = Mutex()
    private var lastPlayRequestId: String? = null
    private var lastPlayResult: JSONObject? = null
    // (v1.0.12 §6 step 2) The engine of the last playActive — MainActivity
    // uses it to decide whether a stage-open/close may touch mpv's surface.
    private var lastActiveEngine: String = "exoplayer"

    /** (v1.0.12 §6 step 2) True when libmpv played last. */
    fun isMpvActive(): Boolean = lastActiveEngine == "libmpv"

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
                // (v1.0.13 P2-B) MALFORMED: never re-resolve the same provider
                // for the same URL shape — the old loop re-probed and re-played
                // the same class of failure 3x with fresh tokens and no new
                // information (INVESTIGATION-4 §3.7). Spend the attempts on
                // DIFFERENT links/engines instead: next ranked link if one
                // exists, else the other engine once, else fail. The
                // 3-attempt budget (maxPlayAttempts) still caps it all.
                if (ap.index + 1 < ap.ranked.size) {
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=next-link attempts=${ap.attempts} session=${ap.session}")
                    bridgeScope.launch { retryNextLink(ap) }
                    true
                } else if (!ap.malformedEngineSwitched) {
                    ap.malformedEngineSwitched = true
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=other-engine attempts=${ap.attempts} session=${ap.session}")
                    bridgeScope.launch { retryOtherEngine(ap) }
                    true
                } else {
                    playbackFailed = true
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=fail attempts=${ap.attempts} session=${ap.session}")
                    EngineLogger.log("player", "error ${error.errorCodeName} — no retry left (MALFORMED: no next link, other engine already tried, attempts=${ap.attempts} ranked.size=${ap.ranked.size})")
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
                // (v1.0.13 P6-B) DECODE_ERROR / UNSUPPORTED_FORMAT: the
                // failure is in THIS engine's decoder stack, so the retry is
                // the OTHER engine once (a different decoder stack) — never
                // the same engine on the same link, and never a re-resolve
                // (a fresh token cannot fix a decoder). Then fail.
                if (!ap.decodeEngineSwitched) {
                    ap.decodeEngineSwitched = true
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=other-engine attempts=${ap.attempts} session=${ap.session}")
                    bridgeScope.launch { retryOtherEngine(ap, "DECODE") }
                    true
                } else {
                    playbackFailed = true
                    EngineLogger.log("player", "recovery errorClass=$errorClass action=fail attempts=${ap.attempts} session=${ap.session}")
                    EngineLogger.log("player", "error ${error.errorCodeName} — no retry left (DECODE: other engine already tried, attempts=${ap.attempts} ranked.size=${ap.ranked.size})")
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

    // (v1.0.10) Engine routing: every player call goes to the active
    // engine (store setting "engine", default exoplayer). The ExoPlayer
    // path is byte-identical for engine=exoplayer. If libmpv was selected
    // but its native instance could not be created, fall back to ExoPlayer
    // for this play instead of failing (mpvPlayer.ensureCreated() false).
    private fun isMpv(): Boolean =
        store.getSettingString("engine", "exoplayer") == "libmpv"

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
        // (v1.0.12 §6 step 1) Switch-time DETACH first, both ways: the idle
        // engine must release its hold on the shared TextureView's
        // BufferQueue before the new engine connects — a paused decoder
        // keeps the queue connected, which is what made mpv's
        // eglCreateWindowSurface fail (INVESTIGATION-2 §2). F3's pause is
        // kept; detach is added (F3's pause-only rule is superseded here).
        // (v1.0.13 P2-B) One-shot engine override for the MALFORMED retry
        // (retryOtherEngine): this play — and only this play — runs on the
        // other engine. The override is read here, synchronously, and cleared
        // by the caller right after this call returns (the mpv branch only
        // posts a delayed play after this decision, so clearing is safe).
        val engineForThisPlay = oneShotEngineOverride ?: if (isMpv()) "libmpv" else "exoplayer"
        if (engineForThisPlay == "libmpv" && !mpvPlayer.isVoFailed() && mpvPlayer.ensureCreated()) {
            lastActiveEngine = "libmpv"
            // (v1.0.12 §7) Stop the previous engine outright (not just
            // pause) before starting the new one — one ticker only.
            player.pauseForBackground()
            player.stopPlayback()
            player.detachSurfaceForSwitch()
            EngineLogger.log("player", "player: exo surface detached for mpv switch")
            // 500 ms settle allowance (TUNABLE, not a measured value) for
            // Media3's async decoder disconnect: the detach is posted to
            // the main handler ahead of this delayed play, so FIFO ordering
            // holds, but the decoder itself disconnects asynchronously on
            // its playback thread. The §6 step-4 VO retry covers a
            // too-short delay. No delay is needed if the surfaces were
            // per-engine (they are shared — Arm 2).
            Handler(Looper.getMainLooper()).postDelayed({
                mpvPlayer.play(link, startAtMs, subtitleText, subtitleLang, subtitleUrl, cacheSizeBytes, bufferLevel, preferredAudioLang)
            }, 500)
        } else {
            // (v1.0.12 §6 step 6) Exclusion tripwire: a VO-init failure that
            // survived detach+retry (mpvPlayer.isVoFailed()) routes the play
            // to ExoPlayer instead of offering audio-only libmpv as video.
            if (engineForThisPlay == "libmpv") {
                if (mpvPlayer.isVoFailed()) {
                    EngineLogger.log("player", "mpv: VO failed after detach+retry — falling back to ExoPlayer, libmpv excluded for this session")
                } else {
                    EngineLogger.log("player", "mpv unavailable — falling back to ExoPlayer for this play")
                }
            } else if (oneShotEngineOverride != null) {
                EngineLogger.log("player", "player: one-shot engine override to ExoPlayer for this retry")
            }
            lastActiveEngine = "exoplayer"
            mpvPlayer.pauseForBackground()
            mpvPlayer.stop()
            mpvPlayer.detachSurfaceForSwitch()
            EngineLogger.log("player", "player: mpv surface detached for exo switch")
            // MANDATORY on the way back: ensurePlayer early-returns when
            // the player exists, so without this the ExoPlayer play would
            // run with no surface after a switch-time detach.
            player.reattachTextureView()
            player.play(link, startAtMs, subtitleText, subtitleLang, subtitleUrl, cacheSizeBytes, bufferLevel, preferredAudioLang)
        }
    }

    private fun activeStateSnapshot(): JSONObject =
        if (isMpv()) mpvPlayer.stateSnapshot() else player.stateSnapshot()

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
            store.putMapEntry(
                EngineStore.KEY_LAST_LINK,
                ap.title,
                JSONObject().put("linkIndex", next).put("atMs", System.currentTimeMillis()),
            )
        }
        // (v1.0.12 §8) Retries keep the session id.
        player.diagSessionId = ap.session
        mpvPlayer.setDiagSessionId(ap.session)
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
        val ranked = rankLinks(resolved.links)
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
            if (probePlayable(c.link)) {
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
            if (!probePlayable(c.link)) {
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

    /**
     * (v1.0.13 P2-B) MALFORMED retry, second resort: replay the SAME picked
     * link on the OTHER engine once (libmpv's ffmpeg HLS parser is far more
     * lenient than Media3's — if mpv plays it, the manifest is Media3-strict
     * malformed rather than unfetchable; INVESTIGATION-4 §3.8). The attempt
     * is counted by playRetryLink (the file's single attempts increment), so the
     * 3-attempt budget still caps it all. Never re-resolves the provider.
     * (v1.0.13 P6-B) The same one-shot other-engine retry serves
     * DECODE_ERROR / UNSUPPORTED_FORMAT (label "DECODE"): the failure is in
     * this engine's decoder stack, so the other engine is the retry.
     */
    private suspend fun retryOtherEngine(ap: ActivePlay, label: String = "MALFORMED") {
        // A newer play() replaces the session — a late error must not
        // restart an old title.
        if (activePlay !== ap) return
        val other = if (isMpv()) "exoplayer" else "libmpv"
        EngineLogger.log("player", "retry $label — trying other engine $other once (attempts=${ap.attempts} session=${ap.session})")
        if (other == "libmpv" && (mpvPlayer.isVoFailed() || !mpvPlayer.ensureCreated())) {
            failPlaySession(ap, "retry MALFORMED — other engine libmpv unavailable, no retry left (attempts=${ap.attempts} session=${ap.session})")
            return
        }
        val candidate = ap.ranked[ap.index]
        // One-shot override: playRetryLink -> playActive reads it
        // synchronously, and it is cleared right after that call returns.
        oneShotEngineOverride = other
        try {
            playRetryLink(ap, ap.ranked, ap.index, candidate)
        } finally {
            oneShotEngineOverride = null
        }
    }

    fun markReady() {
        engineReady = true
    }

    // ---- entry point (called from MainActivity's IO scope) ----

    suspend fun call(method: String, params: JSONObject, requestId: String? = null): JSONObject {
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
                "play" -> play(params, requestId)
                "playerControl" -> playerControl(params)
                "playerState" -> ok(activeStateSnapshot())
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
                "appUpdateDownload" -> ok(
                    updater.downloadUpdate(
                        params.optString("url", ""),
                        params.optBoolean("autoInstall", true),
                    ),
                )
                "appUpdateInstall" -> ok(updater.installDownloadedApk())
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
            EngineLogger.log("bridge", "$method threw ${e.javaClass.simpleName}: ${e.message}")
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
        // shows ALL providers, every language. The v0.2.9 Fix G provLang
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
        return ok(
            JSONObject()
                .put("repoId", repoId)
                .put("providerName", providerName)
                .put("load", load)
                .put("content", content)
                .put("feed", feed)
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
                val probe = firstContentItem(api)
                healthy = probe.item != null
                transportFailed = probe.transportFailed
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
        } catch (e: CancellationException) {
            // (v0.2.11) A P7: a timeout is a timeout, never a network
            // error — rethrow like providerTest/firstContentItem do.
            throw e
        } catch (e: Exception) {
            EngineLogger.log("bridge", "homeFeed threw ${e.javaClass.simpleName}: ${e.message}")
            return err("network_error", "Could not load the home feed")
        }
        val sections = (feed as? Resource.Success<List<HomePageResponse?>>)?.value
            ?: return err("network_error", "Could not load the home feed")
        val arr = JSONArray()
        var hasNext = false
        var totalItems = 0
        for (resp in sections) {
            if (resp == null) continue
            hasNext = hasNext || resp.hasNext
            for (list in resp.items) {
                val items = JSONArray()
                for (item in list.list) {
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
        if (totalItems == 0) return err("no_content", "No homepage responses")
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
                EngineLogger.log("bridge", "search threw ${e.javaClass.simpleName}: ${e.message}")
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
        try {
            withTimeout(60_000) {
                for (chunk in ordered.chunked(8)) {
                    val chunkOut = coroutineScope {
                        chunk.map { (repoId, name) ->
                            async<Triple<String, SearchResponseList?, String?>> {
                                val api = resolveApi(name, null, repoId)
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
            EngineLogger.log("bridge", "details threw ${e.javaClass.simpleName}: ${e.message}")
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
    )

    // (v0.2.11) C-6/C-7: resolveLinks now returns its failure REASON
    // ("provider_not_found" vs "no_data") instead of a bare null, and the
    // timeout comes from the user's stored Stream-timeout setting (the UI
    // sends timeoutSec when it has one; the setting default is 30 s).
    // A timeout with zero links is flagged timedOut so callers report
    // "timeout" instead of misreporting it as "no_links" (S-5).
    private suspend fun resolveLinks(params: JSONObject): Pair<ResolvedLinks?, String?> {
        val providerName = params.optString("providerName", "")
        val api = resolveApi(providerName, null) ?: return null to "provider_not_found"
        // (v1.0.13 P2-C P1) Provider Loaded.
        providerDiag("P1", "Provider Loaded", "provider=$providerName")
        val data = resolveLinkData(params, api) ?: return null to "no_data"
        // (v1.0.13 P2-C P2) LoadData Completed.
        providerDiag("P2", "LoadData Completed", "shape=${loadDataShape(data)}")
        val dataSource = when {
            params.optString("episodeData", "").isNotBlank() -> "episodeData"
            params.optString("data", "").isNotBlank() -> "data"
            else -> "fresh"
        }
        val settingSec = parseSeconds(store.getSettingString("timeout", "30s"))
        val timeoutSec = params.optInt("timeoutSec", settingSec).coerceIn(1, 120)
        val foundLinks = mutableListOf<ExtractorLink>()
        val foundSubs = mutableListOf<SubtitleFile>()
        var partial = false
        var timedOut = false
        var linksError: String? = null
        var loadLinksOk = false
        // (v1.0.13 P2-A) Universal data adapter: normalize Id JSON to
        // LoadData JSON BEFORE loadLinks (see normalizeLoadData) — the
        // Id-parse runs before any fresh load, so a fresh load returning
        // the SAME title-less data can no longer kill the play
        // (INVESTIGATION-4 §4.4 fallback gap).
        val playTitle = params.optString("title", "")
        val normalized = normalizeLoadData(data, playTitle)
        if (normalized != null) {
            EngineLogger.log("bridge", "loadData normalized shape=id source=$dataSource titlePresent=${playTitle.isNotBlank()}")
        }
        val pageUrl = params.optString("episodeUrl", "").ifBlank { params.optString("url", "") }
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
        // (v1.0.13 P2-C P3) LoadLinks Completed — only when a loadLinks call
        // actually returned ok (direct or fallback).
        if (loadLinksOk) {
            providerDiag("P3", "LoadLinks Completed", "links=${foundLinks.size} partial=$partial")
        }
        // (v1.0.13 P2-C P4) Extractor Completed — the extraction result as
        // assembled (links + subtitles), whatever the outcome.
        providerDiag("P4", "Extractor Completed", "links=${foundLinks.size} subs=${foundSubs.size}")
        return ResolvedLinks(api, foundLinks, foundSubs, partial, timedOut) to null
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
     * play params (or ""); non-JSON data (a plain URL) and JSON with BOTH
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
    private fun providerDiag(code: String, name: String, note: String = "") {
        EngineLogger.log(
            "player",
            "diag $code $name session=$playSessionCounter engine=${if (isMpv()) "libmpv" else "exoplayer"} " +
                "ts=${System.currentTimeMillis()}" +
                if (note.isNotEmpty()) " $note" else "",
        )
    }

    private fun parseSeconds(raw: String): Int =
        Regex("(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 30

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
     * the score (audio1 +10000, audio2 +5000); quality match adds +100;
     * then MEASURED server-only TTFB ascending. Unmeasured links rank
     * after measured ones at the same score. Nothing is invented.
     * (v1.0.5 B1/B4) Unplayable links are filtered / penalised BEFORE the
     * pick, so the PARSING_CONTAINER_UNSUPPORTED stub can no longer win
     * the Auto pick: .txt URLs are dropped outright, Unknown-quality
     * links lose 50 and non-manifest (non-M3U8/non-DASH) links lose 50,
     * below the +100 quality tier — a playable alternative always wins.
     * The FINAL order itself is unchanged.
     */
    private suspend fun rankLinks(links: List<ExtractorLink>): List<RankedLink> {
        val resPref = store.getSettingString("res", "Auto")
        val a1 = audioCodeOf(store.getSettingString("audio1", "English"))
        val a2 = audioCodeOf(store.getSettingString("audio2", "বাংলা"))
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
            compareByDescending<RankedLink> { it.score }
                .thenBy { it.ttfbMs ?: Long.MAX_VALUE },
        )
        // (v1.0.13 P2-C P5) Stream Resolved.
        if (ranked.isNotEmpty()) {
            providerDiag("P5", "Stream Resolved", "links=${ranked.size}")
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
    private suspend fun probePlayable(link: ExtractorLink): Boolean {
        val isManifest = link.type == ExtractorLinkType.M3U8 ||
            link.type == ExtractorLinkType.DASH
        if (isManifest) {
            val validated = validateManifestLink(link)
            if (validated == null) {
                // Validation unavailable (transport error/timeout) — no
                // verdict; fail open and let ExoPlayer decide.
                EngineLogger.log("player", "probe ok (validation unavailable, fail-open) ${link.source} url=${redactUrlForLog(link.url)}${tokenAgeSuffix(link.url)}")
                providerDiag("P6", "Stream Validated", "UNVERIFIED-by-code validation-unavailable source=${link.source}")
                return true
            }
            if (!validated.ok) {
                EngineLogger.log("player", "probe skipped ${link.source} url=${redactUrlForLog(link.url)} (${validated.reason})")
                return false
            }
            EngineLogger.log("player", "probe ok ${link.source} url=${redactUrlForLog(link.url)}${tokenAgeSuffix(link.url)}")
            // (v1.0.13 P3-A) P6 VERIFIED — the real parser validated this
            // manifest (type, multivariant yes/no, children checked).
            providerDiag("P6", "Stream Validated", "VERIFIED type=${validated.manifestType} multivariant=${validated.isMultivariant} childrenChecked=${validated.childrenChecked} source=${link.source}")
            return true
        }
        if (ZaynHeaderManager.isSignedUrl(link.url)) {
            // (v1.0.13 P3-B) A signed direct-video URL gets NO pre-play
            // fetch at all (no TTFB, no probe): the token is spent only by
            // the player. There is no manifest to validate, so P6 stays
            // UNVERIFIED-by-code for this link.
            EngineLogger.log("player", "probe skipped signed URL (no pre-play fetch) ${link.source} url=${redactUrlForLog(link.url)}")
            providerDiag("P6", "Stream Validated", "UNVERIFIED-by-code signed-direct source=${link.source}")
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
                        EngineLogger.log("player", "probe skipped ${link.source} url=${redactUrlForLog(link.url)} ($reason)")
                        false
                    } else {
                        EngineLogger.log("player", "probe ok ${link.source} url=${redactUrlForLog(link.url)}${tokenAgeSuffix(link.url)}")
                        providerDiag("P6", "Stream Validated", "UNVERIFIED-by-code direct-video source=${link.source}")
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
        // (v1.0.13 P3-A) HTML error pages must NEVER reach the player:
        // a text/html body (or an HTML document body) is a validation
        // failure, logged with status + content type.
        if (fetch.contentType.startsWith("text/html") ||
            head.startsWith("<html", ignoreCase = true) ||
            head.startsWith("<!doctype html", ignoreCase = true)
        ) {
            return ManifestValidation(false, typeName, false, 0, "HTML error page status=${fetch.status} contentType=${fetch.contentType}")
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

    // (v1.0.13 P7 fix-forward D-1) EXTINF duration shape: digits with an
    // optional decimal part, followed by a comma.
    private val extinfLineRegex = Regex("^#EXTINF:\\d+(\\.\\d+)?,")

    /**
     * (v1.0.13 P7 fix-forward D-1) MINIMAL media-playlist structural
     * check: every `#EXTINF:` line must be `#EXTINF:<number>,` — integer
     * and float durations are both valid (`#EXTINF:10,` and
     * `#EXTINF:10.0,` pass; `#EXTINF:abc,` fails). No other structural
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
        val isMultivariant = text.lines().any {
            it.startsWith("#EXT-X-STREAM-INF") || it.startsWith("#EXT-X-I-FRAME-STREAM-INF")
        }
        if (!isMultivariant) {
            if (!text.contains("#EXTINF") && !text.contains("#EXT-X-TARGETDURATION")) {
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
                    val basicFailure = when {
                        fetch.status != 200 ->
                            "child ${redactUrlForLog(childUrl)} HTTP ${fetch.status} contentType=${fetch.contentType}"
                        fetch.contentType.startsWith("text/html") ->
                            "child ${redactUrlForLog(childUrl)} HTML error page status=${fetch.status}"
                        !fetch.body.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U") ->
                            "child ${redactUrlForLog(childUrl)} not an M3U8 manifest"
                        !fetch.body.contains("#EXTINF") && !fetch.body.contains("#EXT-X-TARGETDURATION") ->
                            "child ${redactUrlForLog(childUrl)} media playlist has no segments"
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
                    "child ${redactUrlForLog(childUrl)} fetch failed: ${redactUrlForLog(e.message)}"
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
            return if (resolved.timedOut) err("timeout", "Loading the links timed out — try again")
            else err("no_links", "No playable links found")
        }
        val ranked = rankLinks(resolved.links)
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
        val result = playMutex.withLock { playInternal(params, requestId) }
        if (requestId != null) {
            lastPlayRequestId = requestId
            lastPlayResult = result
        }
        return result
    }

    // (v1.0.12 §7) One log line on every early return, so the next log can
    // classify a play that died before playActive (the c31–c36 residue).
    private fun reject(code: String, message: String, requestId: String?): JSONObject {
        EngineLogger.log("player", "play rejected $code request=${requestId ?: "?"}")
        return err(code, message)
    }

    private suspend fun playInternal(params: JSONObject, requestId: String? = null): JSONObject {
        // (v1.0.10) The libmpv guard is GONE: libmpv is bundled now, so a
        // persisted engine=libmpv is valid. Routing happens in playActive()
        // below; link resolution, probe, ranking and auto-retry above it are
        // engine-independent and untouched.
        if (requestId != null && requestId == lastPlayRequestId && lastPlayResult != null) {
            EngineLogger.log("player", "play dedup request=$requestId — returning cached reply")
            return lastPlayResult!!
        }
        val mode = params.optString("mode", store.getSettingString("mode", "auto"))
        val titleForLog = params.optString("title", "")
        val linkIndexForLog = if (params.has("linkIndex") && !params.isNull("linkIndex")) params.optInt("linkIndex", -1) else -1
        EngineLogger.log("player", "play request ${requestId ?: "?"} title=$titleForLog linkIndex=$linkIndexForLog mode=$mode")
        val (resolved, reason) = resolveLinks(params)
        if (resolved == null) {
            return if (reason == "no_data") reject("no_data", "This title has no playable data", requestId)
            else reject("provider_not_found", "Provider not found", requestId)
        }
        if (resolved.links.isEmpty()) {
            return if (resolved.timedOut) reject("timeout", "Loading the links timed out — try again", requestId)
            else reject("no_links", "No playable links found", requestId)
        }
        val ranked = rankLinks(resolved.links)
        if (ranked.isEmpty()) return reject("torrent_only", "This provider only has torrent links, which are not supported", requestId)
        val linkIndex = if (params.has("linkIndex") && !params.isNull("linkIndex")) params.optInt("linkIndex", -1) else -1
        if (mode == "manual" && linkIndex < 0) {
            val arr = JSONArray()
            for ((i, r) in ranked.withIndex()) arr.put(linkJson(r, i))
            return ok(JSONObject().put("links", arr).put("manual", true))
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
                if (probePlayable(c.link)) {
                    pickedIdx = i
                    break
                }
                EngineLogger.log("player", "play skipped unplayable pick ${c.link.source} ${redactUrlForLog(c.link.url)}")
            }
            if (pickedIdx < 0) {
                return reject("playback_error", "No playable link found — the best links look like web pages, not video", requestId)
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
            (player.hasActiveMedia() || mpvPlayer.hasActiveMedia())
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
        val reuse = store.getSettingBoolean("reuse", true)
        var reused = false
        if (reuse && title.isNotBlank()) {
            val last = store.mapEntry(EngineStore.KEY_LAST_LINK, title)
            if (last != null) {
                val at = last.optLong("atMs", 0)
                val durH = parseHours(store.getSettingString("reuseDur", "24h"))
                if (last.optInt("linkIndex", -1) == pickedIdx &&
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
        // (v1.0.4) Never seek past the saved end of THIS title: a stale
        // resume (e.g. another title's 23 min) used to seek past the end,
        // which ended playback at once ("starts at 23 min then cuts").
        val savedDurSec = library.progressGet(title).optInt("durSec", 0)
        val safeStartSec = if (startAtSec > 0 && savedDurSec > 0 && startAtSec >= savedDurSec - 30) 0 else startAtSec
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
        mpvPlayer.setDiagSessionId(session)
        playActive(playLink, safeStartSec * 1000L, subtitleText, subtitleLang, providerSub?.url, cacheSizeBytes(), bufferLevel(), preferredAudioLang(picked.audio))
        // (v1.0.13 P2-C P7) Player Selected.
        providerDiag("P7", "Player Selected", "engine=$lastActiveEngine")
        // (v1.0.5 B2/B3) Arm the auto-retry session (attempt 1 of 3) and
        // name the picked link (URL + type + quality) in the log.
        // (v1.0.13 P6-C) A new play clears the dead-session latch from any
        // earlier failed session — the new session owns the controls now.
        playbackFailed = false
        activePlay = ActivePlay(ranked, pickedIdx, 1, session, safeStartSec, subtitleText, subtitleLang, providerSub?.url, title, params)
        EngineLogger.log("player", "play ${link.source} ${qualityLabel(link.quality)} type=${link.type} url=${redactUrlForLog(link.url)} session=$session${tokenAgeSuffix(link.url)}")
        if (title.isNotBlank()) {
            store.putMapEntry(
                EngineStore.KEY_LAST_LINK,
                title,
                JSONObject().put("linkIndex", pickedIdx).put("atMs", System.currentTimeMillis()),
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

    private suspend fun playerControl(params: JSONObject): JSONObject {
        val action = params.optString("action", "")
        // (v1.0.7) Log the action on every call: the V1.0.6 log recorded
        // playerControl ids only, so whether the 4x "first frame rendered"
        // re-fires were seeks or surface re-attachment was unknowable.
        EngineLogger.log("player", "playerControl $action")
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
                val state = if (isMpv()) mpvPlayer.control(action, value) else player.control(action, value)
                ok(JSONObject().put("state", state))
            }
            "setSubtitle" -> {
                val text = if (params.has("text") && !params.isNull("text")) params.optString("text") else null
                if (isMpv()) mpvPlayer.setSubtitleText(text, params.optString("lang", "")) else player.setSubtitleText(text, params.optString("lang", ""))
                ok(JSONObject().put("state", activeStateSnapshot()))
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
        val (resolved, reason) = resolveLinks(params)
        if (resolved == null) {
            return if (reason == "no_data") err("no_data", "This title has no playable data")
            else err("provider_not_found", "Provider not found")
        }
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
        // (v1.0.12 §8) Attribute the download play to the current session
        // (no new session is armed for a download open).
        player.diagSessionId = playSessionCounter
        mpvPlayer.setDiagSessionId(playSessionCounter)
        playActive(link, 0, null, null, null, cacheSizeBytes())
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
