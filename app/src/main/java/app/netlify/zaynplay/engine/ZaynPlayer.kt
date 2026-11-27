package app.netlify.zaynplay.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.decoder.DecoderInitializationException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DrmSession.DrmSessionException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONObject

/**
 * Phase C player (engine-design.md §5.5, §4.3 E13–E15).
 *
 * One headless ExoPlayer (Media3 1.4.1) owned by MainActivity. It renders
 * into a plain [TextureView] — there is no media3-ui PlayerView and no
 * native controller: every control stays HTML and drives [control].
 * TextureView (not SurfaceView) is deliberate — see MainActivity: the
 * video must composite BELOW the WebView so the HTML controls stay
 * tappable, which only a TextureView does.
 * The video surface is positioned over the WebView's player stage by
 * MainActivity from the rect the page reports (P0 overlay).
 *
 * All ExoPlayer calls run on the main thread (posted via [mainHandler]);
 * state snapshots come from volatile cached fields updated by the
 * listener + 1 s ticker, so the bridge can read them from any thread.
 * Nothing here invents numbers: duration/position come from ExoPlayer,
 * quality/audio labels come from the picked link.
 */
class ZaynPlayer(
    private val context: Context,
    private val onEvent: (type: String, data: JSONObject) -> Unit,
) {
    data class PlayLink(
        val url: String,
        val source: String,
        val qualityLabel: String,
        val audio: String?,
        val referer: String,
        val headers: Map<String, String>,
        val type: ExtractorLinkType,
        val isLocalFile: Boolean = false,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var textureView: TextureView? = null
    private var cache: SimpleCache? = null

    @Volatile private var lastPlaying = false
    @Volatile private var lastBuffering = false
    @Volatile private var lastPosMs = 0L
    @Volatile private var lastDurMs = 0L
    @Volatile private var lastBufferedMs = 0L
    @Volatile private var lastRate = 1f
    @Volatile private var lastQuality = ""
    @Volatile private var lastSource = ""
    @Volatile private var lastAudio: String? = null
    @Volatile private var lastVolume = 1f
    @Volatile private var muted = false
    @Volatile private var preMuteVolume = 1f

    private var currentLink: PlayLink? = null
    private var currentSubtitleText: String? = null
    private var currentSubtitleLang: String? = null
    private var tickerRunning = false

    // (v1.0.12 §3) Surface-lifecycle state machine (prompt §3): the 9 states
    // with one log line per transition (transitionTo). Texture identity
    // (identityHashCode) + player.surfaceSize are tracked; the Surface
    // object itself is Media3-internal and unobservable from app code
    // (audit §A Q2), so it is never tracked. Registration
    // (setVideoTextureView) precedes texture availability: SURFACE_ATTACHED
    // may transition to SURFACE_AVAILABLE when the texture appears.
    enum class SurfaceLifecycle {
        WAITING_FOR_SURFACE, SURFACE_AVAILABLE, PLAYER_INITIALIZED,
        SURFACE_ATTACHED, PREPARED, PLAYING, SURFACE_DESTROYED,
        RECOVERING, RELEASED
    }
    private var surfaceState = SurfaceLifecycle.WAITING_FOR_SURFACE
    // (v1.0.12 §8) Diagnostics session id (set by ZaynEngine per play) +
    // activity state (set by MainActivity onResume/onPause).
    var diagSessionId: Int = 0
    var activityState: String = "unknown"
    private var surfaceDetachedForSwitch = false
    private var surfaceAvailableEmitted = false
    private var diagVisibleLogged = false
    private var surfaceRecoveriesThisPlay = 0
    // (v1.0.12 §10) Volume/seek coalescing for control(): only the latest
    // pending value is applied, by at most one posted runnable.
    private var pendingVolume: Float? = null
    private var pendingSeekSec: Double? = null
    private var controlCoalescePosted = false
    private var lastPushStateMs: Long = 0L

    private fun transitionTo(next: SurfaceLifecycle, reason: String) {
        val prev = surfaceState
        if (prev == next) return
        surfaceState = next
        EngineLogger.log(
            "player",
            "surface $prev -> $next $reason " +
                "texId=${System.identityHashCode(textureView?.surfaceTexture)} " +
                "surfaceSize=${surfaceSizeText()}",
        )
    }

    // (v1.0.12 §8) Twelve-event first-frame diagnostics. Every event carries
    // timestamp + session id + engine + texture id + surface size + video
    // size + player state + activity state. An event that cannot be truly
    // verified is logged UNVERIFIED, never as verified (prompt §8) — in
    // particular L10/L12 (displayed video) can only be proven by an
    // on-device sighting.
    private fun diag(code: String, name: String, note: String = "") {
        val p = player
        val vf = p?.videoFormat
        val state = when {
            p == null -> "no-player"
            p.isPlaying -> "playing"
            else -> "state=${p.playbackState}"
        }
        EngineLogger.log(
            "player",
            "diag $code $name session=$diagSessionId engine=exo " +
                "ts=${System.currentTimeMillis()} " +
                "texId=${System.identityHashCode(textureView?.surfaceTexture)} " +
                "surfaceSize=${surfaceSizeText()} " +
                "videoSize=${vf?.width ?: 0}x${vf?.height ?: 0} " +
                "state=$state activity=$activityState" +
                if (note.isNotEmpty()) " $note" else "",
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

    /**
     * (v1.0.13 P4-B, extended P6-A) Playback failure classes — the full
     * Part E §13 set (13 types; P4-B's five kept first, in the same order,
     * so the P4-B log lines and routing are untouched). The class is logged
     * as `errorClass=<TYPE>` in onPlayerError and in the engine retry log,
     * and routes recovery in ZaynEngine.onPlaybackError.
     * MANIFEST_MALFORMED: the parser rejected the manifest (Media3
     * PARSING_MANIFEST_MALFORMED, or an unsupported manifest feature).
     * MANIFEST_AUTH: the manifest/child fetch was refused with HTTP
     * 401/403 (token/Referer rejected). MANIFEST_HTTP: any other
     * non-200 manifest/child fetch (status from
     * HttpDataSource.InvalidResponseCodeException when available).
     * NETWORK: timeout-excluded transport failure (DNS/connect). OTHER:
     * the UNKNOWN fallback — no signal matched (see classifyPlaybackError).
     * NETWORK_TIMEOUT: the server took too long (IO_NETWORK_CONNECTION_
     * TIMEOUT code, or a SocketTimeoutException in the cause chain).
     * DECODE_ERROR: the decoder failed (DECODING_FAILED /
     * DECODER_INIT_FAILED / DECODER_QUERY_FAILED, or a
     * DecoderInitializationException cause). DRM_ERROR: DRM failure
     * (DRM_CONTENT_ERROR / DRM_LICENSE_EXPIRED, or a DrmSessionException
     * cause). SOURCE_NOT_FOUND: the URL is gone (HTTP 404, or
     * IO_FILE_NOT_FOUND). LINK_EXPIRED: the signed link expired (HTTP
     * 410, or AUTHENTICATION_EXPIRED). UNSUPPORTED_FORMAT: the container
     * or codec is unsupported (PARSING_CONTAINER_MALFORMED /
     * UNSUPPORTED, DECODING_FORMAT_UNSUPPORTED /
     * DECODING_FORMAT_EXCEEDS_CAPABILITIES). PLAYBACK_INTERRUPTED: the
     * load position fell behind the live window (BEHIND_LIVE_WINDOW).
     * STALLED: reserved — Media3 1.4.1 has NO error code for a stall
     * (no ERROR_CODE_TIMEOUT exists), so a stall surfaces upstream as
     * NETWORK_TIMEOUT; STALLED is never returned by classifyPlaybackError
     * and exists so the §13 set is named in full for a future
     * stall detector (e.g. ticker-based no-progress) to return.
     */
    enum class PlaybackErrorClass {
        MANIFEST_MALFORMED, MANIFEST_HTTP, MANIFEST_AUTH, NETWORK, OTHER, NETWORK_TIMEOUT, DECODE_ERROR, DRM_ERROR, SOURCE_NOT_FOUND, LINK_EXPIRED, UNSUPPORTED_FORMAT, PLAYBACK_INTERRUPTED, STALLED
    }

    /** (v1.0.13 P4-B, extended P6-A) Classify one PlaybackException. Code-based
     * first; the HTTP status is read from the cause chain only when Media3
     * raised it (never parsed out of a message string). Precedence is
     * deliberate: a manifest parse failure is named even when its cause
     * chain also carries a transport type, and a timeout is named before
     * the generic network class. */
    fun classifyPlaybackError(error: PlaybackException): PlaybackErrorClass {
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) {
            return PlaybackErrorClass.MANIFEST_MALFORMED
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) {
            return PlaybackErrorClass.MANIFEST_MALFORMED
        }
        var httpStatus: Int? = null
        var networkCause = false
        var timeoutCause = false
        var decodeCause = false
        var drmCause = false
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth < 10) {
            when (cause) {
                is HttpDataSource.InvalidResponseCodeException ->
                    if (httpStatus == null) httpStatus = cause.responseCode
                is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                    networkCause = true
                is SocketTimeoutException -> timeoutCause = true
                is DecoderInitializationException -> decodeCause = true
                is DrmSessionException -> drmCause = true
            }
            cause = cause.cause
            depth++
        }
        if (httpStatus == 401 || httpStatus == 403) return PlaybackErrorClass.MANIFEST_AUTH
        if (httpStatus == 404) return PlaybackErrorClass.SOURCE_NOT_FOUND
        if (httpStatus == 410) return PlaybackErrorClass.LINK_EXPIRED
        if (httpStatus != null ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        ) return PlaybackErrorClass.MANIFEST_HTTP
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT || timeoutCause) {
            return PlaybackErrorClass.NETWORK_TIMEOUT
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR ||
            error.errorCode == PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED || drmCause
        ) return PlaybackErrorClass.DRM_ERROR
        if (error.errorCode == PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED) {
            return PlaybackErrorClass.LINK_EXPIRED
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            return PlaybackErrorClass.SOURCE_NOT_FOUND
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED || decodeCause
        ) return PlaybackErrorClass.DECODE_ERROR
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
        ) return PlaybackErrorClass.UNSUPPORTED_FORMAT
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            return PlaybackErrorClass.PLAYBACK_INTERRUPTED
        }
        if (networkCause ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        ) return PlaybackErrorClass.NETWORK
        // (v1.0.13 P6-A) No signal matched — OTHER is the UNKNOWN fallback.
        // Log what WAS available once, so the next failure is
        // self-diagnosing instead of a bare class name.
        EngineLogger.log(
            "player",
            "classifyPlaybackError OTHER (unknown): errorCodeName=${error.errorCodeName} errorCode=${error.errorCode} message=${redactUrlForLog(error.message)} cause=${causeChainForLog(error)}",
        )
        return PlaybackErrorClass.OTHER
    }

    // (v1.0.12 §3/§4) Only a settled landscape size counts as
    // SURFACE_AVAILABLE: ignore 0x0 / 3x3 and the mid-rotation overshoot
    // (portrait-aspect transient, e.g. 2398x5308 while rotating to
    // landscape — the player is fullscreen landscape, so the settled
    // surface is landscape, w>=h).
    private fun isSettledSurfaceSize(w: Int, h: Int): Boolean = w > 3 && h > 3 && w >= h

    /**
     * (v1.0.5 B2) Auto-retry hook, set by ZaynEngine. Invoked on the main
     * thread when ExoPlayer errors. Return true when a retry to the next
     * ranked link was started (the engine plays it asynchronously) — the
     * playerError event is then withheld until retries are exhausted, so
     * a bad pick becomes a brief retry instead of a dead player + toast.
     * Return false (or leave unset) for the old behaviour: log + event.
     */
    var onPlaybackError: ((PlaybackException) -> Boolean)? = null

    // (v1.0.4) Buffering level (Settings > Playback > Buffering). The
    // LoadControl is build-time, so a level change rebuilds the player
    // on the next play() — never mid-play.
    private var pendingBufferLevel: String = "normal"
    private var builtBufferLevel: String? = null

    /**
     * (v1.0.4) Load-control presets per Buffering level, least to most:
     * (minBufferMs, maxBufferMs, bufferForPlaybackMs,
     * bufferForPlaybackAfterRebufferMs). Very low starts almost at once;
     * Very high pre-buffers a full minute for the smoothest playback.
     */
    private fun loadControlFor(level: String): DefaultLoadControl {
        val (min, max, play, rebuf) = when (level) {
            "verylow" -> intArrayOf(2_000, 5_000, 250, 500)
            "low" -> intArrayOf(5_000, 15_000, 500, 1_000)
            "high" -> intArrayOf(30_000, 60_000, 2_500, 5_000)
            "veryhigh" -> intArrayOf(60_000, 120_000, 5_000, 8_000)
            else -> intArrayOf(15_000, 30_000, 1_500, 3_000) // normal
        }
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(min, max, play, rebuf)
            .build()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                lastPosMs = p.currentPosition.coerceAtLeast(0)
                lastDurMs = p.duration.takeIf { it > 0 } ?: lastDurMs
                lastBufferedMs = p.bufferedPosition.coerceAtLeast(0)
                lastPlaying = p.isPlaying
                pushState()
            }
            if (tickerRunning) mainHandler.postDelayed(this, 1000)
        }
    }

    // (v1.0.10, web-research lead 3) Log the SELECTED video decoder's
    // name + whether it is hardware or software, so the next log proves
    // or refutes the decoder as the cause. Heuristic: OMX.google.* /
    // c2.android.* / *.sw.* are software decoders; anything else (OMX.qcom,
    // c2.qcom, ...) is hardware. The 10-bit HEVC silent-failure cause is
    // already REFUTED for our links (avc1.640028 H.264 in the V1.0.9 log),
    // but the decoder identity itself was never logged.
    // NOTE: onVideoDecoderInitialized is an AnalyticsListener callback, NOT
    // a Player.Listener one — an override inside the Player.Listener failed
    // CI 46044585085 ("overrides nothing"). It lives on the analytics
    // listener, registered next to the Player.Listener in ensurePlayer.
    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializationDurationMs: Long,
        ) {
            val lower = decoderName.lowercase()
            val software = lower.startsWith("omx.google.") ||
                lower.startsWith("c2.android.") || ".sw." in lower
            val mime = player?.videoFormat?.sampleMimeType ?: "?"
            EngineLogger.log(
                "player",
                "video decoder $decoderName mime=$mime software=$software",
            )
            // (v1.0.12 §8 L7) Decoder Initialized.
            diag("L7", "Decoder Initialized")
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val p = player ?: return
            lastDurMs = p.duration.takeIf { it > 0 } ?: lastDurMs
            lastBufferedMs = p.bufferedPosition.coerceAtLeast(0)
            lastPosMs = p.currentPosition.coerceAtLeast(0)
            lastBuffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_ENDED) {
                lastPlaying = false
                onEvent("playerEnded", JSONObject().put("source", lastSource))
            }
            pushState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            lastPlaying = isPlaying
            if (isPlaying) startTicker() else stopTicker()
            // (v1.0.12 §8 L12) UNVERIFIED-by-code: isPlaying proves the
            // pipeline runs, NOT that a frame is displayed — only an
            // on-device sighting proves Playback Visible.
            if (isPlaying && !diagVisibleLogged) {
                diagVisibleLogged = true
                diag("L12", "Playback Visible", "UNVERIFIED-by-code — display proven only by on-device sighting")
            }
            pushState()
        }

        // (v1.0.9) CORRECTS the v1.0.7 comment, which was wrong about the
        // mechanism: in the sink path onRenderedFirstFrame fires from the
        // VideoSink.Listener.onFirstFrameRendered callback, gated only on
        // displaySurface != null — it means the sink processed a frame,
        // NOT that a frame reached the display. Media3's PlaceholderSurface
        // is NOT used on this device (shouldUsePlaceholderSurface is false:
        // SDK>=23, no tunneling, no secure-codec case), and with a VideoSink
        // the codec renders into the sink's input surface anyway. So it
        // proves decoding, NOT display. The V1.0.6 black screen had a
        // video track selected (854x480) and frames rendered, yet the
        // visible TextureView stayed black.
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // (v1.0.7) Log the actual video track alongside the size, so
            // the next log shows whether the track is a real video track
            // and whether it is DRM-protected.
            val vf = player?.videoFormat
            EngineLogger.log(
                "player",
                "video size ${videoSize.width}x${videoSize.height} " +
                    "videoFormat=${vf?.sampleMimeType ?: "none"} ${vf?.codecs ?: ""} " +
                    "${vf?.width ?: 0}x${vf?.height ?: 0} " +
                    "drmInitData=${vf?.drmInitData != null} " +
                    "texId=${System.identityHashCode(textureView?.surfaceTexture)}",
            )
            // (v1.0.12 §8 L6) Video Format Received.
            diag("L6", "Video Format Received")
        }

        // (v1.0.9) Media3 reports the output-surface size here whenever the
        // size changes — this is the MSG_SET_VIDEO_OUTPUT_RESOLUTION path:
        // a POSITIVE size proves the sink was told its output size.
        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            EngineLogger.log("player", "surface size ${width}x${height}")
            // (v1.0.12 §3/§8) Transient sizes are expected rotation churn,
            // not a fault — only the settled landscape size counts.
            if (!isSettledSurfaceSize(width, height)) {
                EngineLogger.log("player", "surface size ${width}x${height} transient — ignored for SURFACE_AVAILABLE")
                return
            }
            if (!surfaceAvailableEmitted) {
                surfaceAvailableEmitted = true
                if (surfaceState != SurfaceLifecycle.PREPARED &&
                    surfaceState != SurfaceLifecycle.PLAYING &&
                    surfaceState != SurfaceLifecycle.RELEASED
                ) {
                    transitionTo(SurfaceLifecycle.SURFACE_AVAILABLE, "settled size ${width}x${height}")
                }
                diag("L2", "Surface Available")
            }
            // (v1.0.12 §8 L11) Surface Updated — each settled size change.
            diag("L11", "Surface Updated")
        }

        override fun onRenderedFirstFrame() {
            // (v1.0.10 L2/L3) texId at first-frame time + tell MainActivity
            // to re-report the stage rect NOW, so the page-hole state is
            // captured AT the first frame, not only at stage-open.
            EngineLogger.log(
                "player",
                "first frame rendered texId=${System.identityHashCode(textureView?.surfaceTexture)}",
            )
            // (v1.0.12 §8 L8) First Frame Decoded — the sink processed a
            // frame; that proves decoding, NOT display (see the comment
            // above onRenderedFirstFrame).
            diag("L8", "First Frame Decoded", "decoded-not-displayed")
            onEvent("playerFirstFrame", JSONObject())
        }

        override fun onPlayerError(error: PlaybackException) {
            // (v1.0.13 P2-B) Log the failure in full: message + cause chain +
            // failing URI, token-redacted — the V1.0.12 log recorded only the
            // errorCodeName (INVESTIGATION-4 §3.5).
            // (v1.0.13 P4-B) errorClass is appended LAST so the P2 log
            // shape stays an exact substring (gate AJ asserts it).
            EngineLogger.log(
                "player",
                "player error ${error.errorCodeName} message=${redactUrlForLog(error.message)} cause=${causeChainForLog(error)} uri=${redactUrlForLog(currentLink?.url)} errorClass=${classifyPlaybackError(error)}",
            )
            // (v1.0.5 B2) Offer the engine the retry first: a started retry
            // suppresses the playerError toast/event until retries run out.
            val retrying = try {
                onPlaybackError?.invoke(error) ?: false
            } catch (e: Exception) {
                false
            }
            if (retrying) {
                EngineLogger.log("player", "error ${error.errorCodeName} — retrying next link")
                lastPlaying = false
                pushState()
                return
            }
            EngineLogger.log("player", "error ${error.errorCodeName}")
            lastPlaying = false
            onEvent(
                "playerError",
                JSONObject()
                    .put("code", "playback_error")
                    .put("message", "Video could not be played")
                    .put("retryable", true),
            )
            pushState()
        }
    }

    private fun ensurePlayer(cacheSizeBytes: Long) {
        if (player != null) return
        val httpFactory = OkHttpDataSource.Factory(app.baseClient)
            .setUserAgent(USER_AGENT)
        val upstream = DefaultDataSource.Factory(context, httpFactory)
        val cacheFactory: DefaultDataSource.Factory = try {
            val c = SimpleCache(
                File(context.cacheDir, "exoplayer"),
                LeastRecentlyUsedCacheEvictor(cacheSizeBytes),
                androidx.media3.database.StandaloneDatabaseProvider(context),
            )
            cache = c
            val cacheSource = CacheDataSource.Factory()
                .setCache(c)
                .setUpstreamDataSourceFactory(upstream)
            DefaultDataSource.Factory(context, cacheSource)
        } catch (e: Exception) {
            EngineLogger.log("player", "cache unavailable, direct source")
            upstream
        }
        val exo = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setLoadControl(loadControlFor(pendingBufferLevel))
            .build()
        exo.addListener(listener)
        exo.addAnalyticsListener(analyticsListener)
        player = exo
        transitionTo(SurfaceLifecycle.PLAYER_INITIALIZED, "ExoPlayer created")
        // (v1.0.12 §8 L1) Player Created.
        diag("L1", "Player Created")
        // (v1.0.9) Register Media3's view-managed surface API: its
        // componentListener reports the REAL view size to the sink
        // (MSG_SET_VIDEO_OUTPUT_RESOLUTION), which the raw-Surface path
        // could never do (it only ever reported -1x-1).
        textureView?.let {
            exo.setVideoTextureView(it)
            transitionTo(SurfaceLifecycle.SURFACE_ATTACHED, "setVideoTextureView")
            // (v1.0.12 §8 L4) Surface Attached.
            diag("L4", "Surface Attached")
        }
        // (v1.0.10 L2) Texture identity at attach time: the stale-Surface
        // suspect (INVESTIGATION-1 §6B) dies iff this id matches the id at
        // first frame and in postSurfaceCheck.
        EngineLogger.log("player", "texture id=${System.identityHashCode(textureView?.surfaceTexture)}")
        builtBufferLevel = pendingBufferLevel
        EngineLogger.log("player", "ExoPlayer created")
    }

    /** (v1.0.9) Store the TextureView and, if a player already exists,
     *  hand it to Media3 via setVideoTextureView at once; otherwise
     *  ensurePlayer applies it at creation. The V1.0.8 raw-Surface path
     *  (setVideoSurfaceFromTexture / clearVideoSurface) is RETIRED: a raw
     *  Surface can only ever report size (-1,-1) to the sink, so the sink
     *  never learned its output size (V1.0.8 black screen), and
     *  setVideoSurface removes Media3's componentListener. The V1.0.8
     *  comment that setVideoTextureView "is NOT used anywhere" was the bug:
     *  it is the ONLY API that delivers the surface SIZE. setSurface is
     *  retired. On release only, the view is detached via
     *  setVideoTextureView(null) in releaseNow. */
    fun setTextureView(view: TextureView?) {
        textureView = view
        if (view != null) player?.setVideoTextureView(view)
        if (view != null && player != null) {
            transitionTo(SurfaceLifecycle.SURFACE_ATTACHED, "setTextureView")
            diag("L4", "Surface Attached")
        }
    }

    /** (v1.0.9) The size ExoPlayerImpl tracks for the output surface —
     *  the proof line: positive (e.g. 2392x1080) means the sink was told
     *  its output size; -1x-1 means it never was. */
    fun surfaceSizeText(): String {
        val sz = player?.surfaceSize
        return "${sz?.width ?: -1}x${sz?.height ?: -1}"
    }

    /** (v1.0.12 §8) Current video-track size for the diagnostics lines. */
    fun videoSizeText(): String {
        val vf = player?.videoFormat
        return "${vf?.width ?: 0}x${vf?.height ?: 0}"
    }

    /**
     * (v1.0.13 P4-A) DataSource wrapper that re-attaches the PLAYING
     * manifest's signed query token (INVESTIGATION-4 §3.7 L2) to child
     * playlist / segment requests that lost it: ExoPlayer resolves
     * relative child URIs against the master URL, and RFC 3986 drops
     * the `?in=…` query on relative resolution — the same loss the P3
     * validator repairs for its own fetches (ZaynEngine.reattachToken).
     * The rule is identical (ZaynHeaderManager.reattachToken): a
     * request URI that already carries a query is never touched, and
     * a manifest URL with no query changes nothing. Scope is
     * STRUCTURAL: one wrapper instance is built per play() call for
     * that play's manifest URL, on the per-link DataSource.Factory —
     * one manifest's token can never reach another provider's or
     * another manifest's requests. The top-level manifest request
     * itself already carries the query, so it passes through
     * unchanged. Re-attachments are reported via [onReattached]
     * (token-redacted, at most once per child URI per play — the
     * caller dedups).
     */
    private class TokenReattachingDataSource(
        private val upstream: DataSource,
        private val manifestUrl: String,
        private val onReattached: (String) -> Unit,
    ) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val uriString = dataSpec.uri.toString()
            val rewritten = ZaynHeaderManager.reattachToken(manifestUrl, uriString)
            if (rewritten != uriString) {
                onReattached(rewritten)
                return upstream.open(dataSpec.withUri(Uri.parse(rewritten)))
            }
            return upstream.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            upstream.read(buffer, offset, length)

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() = upstream.close()
    }

    /** (v1.0.13 P4-A) Factory for [TokenReattachingDataSource]: one
     * instance per play, bound to that play's manifest URL. */
    private class TokenReattachingDataSourceFactory(
        private val upstream: DataSource.Factory,
        private val manifestUrl: String,
        private val onReattached: (String) -> Unit,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            TokenReattachingDataSource(upstream.createDataSource(), manifestUrl, onReattached)
    }

    /**
     * Play a picked link. Runs on the main thread; the bridge returns
     * at once and progress arrives via playerState events.
     * subtitleText = downloaded subtitle file text (E18); subtitleUrl =
     * a provider subtitle URL streamed directly (loadLinks callback).
     */
    fun play(
        link: PlayLink,
        startAtMs: Long,
        subtitleText: String?,
        subtitleLang: String?,
        subtitleUrl: String?,
        cacheSizeBytes: Long,
        bufferLevel: String = "normal",
        preferredAudioLang: String? = null,
    ) {
        // (v1.0.13 P2-B T1) Log every player.play() entry (token-redacted):
        // a second play path, if one exists, becomes visible here.
        EngineLogger.log("player", "player.play() entry url=${redactUrlForLog(link.url)} source=${link.source}")
        mainHandler.post {
            // (v1.0.4) A changed Buffering level rebuilds the player here
            // (LoadControl is build-time); the new play starts fresh
            // anyway, so nothing in progress is disturbed.
            pendingBufferLevel = bufferLevel
            if (builtBufferLevel != null && builtBufferLevel != bufferLevel) {
                releaseNow()
            }
            // (v1.0.12 §8/§9) Fresh per-play diagnostics + recovery budget.
            diagVisibleLogged = false
            surfaceAvailableEmitted = false
            surfaceRecoveriesThisPlay = 0
            ensurePlayer(cacheSizeBytes)
            // (v1.0.9) NO raw-Surface attach here: setVideoSurface calls
            // removeSurfaceCallbacks(), which would remove Media3's
            // componentListener and break the size notifications. The view
            // is attached via setVideoTextureView in ensurePlayer.
            val p = player ?: return@post
            currentLink = link
            currentSubtitleText = subtitleText
            currentSubtitleLang = subtitleLang
            lastQuality = link.qualityLabel
            lastSource = link.source
            lastAudio = link.audio
            // (v1.0.13 P3-C) Headers via the header manager: the same
            // per-link Referer + headers the probe/validator sends for
            // this link (deduped on "referer" — the snapshot already
            // contains it via getAllHeaders()).
            val headers = ZaynHeaderManager.headersForPlayLink(link)
            // (v1.0.13 P4-A header audit) Header keys (NAMES only, never
            // values — a Cookie/Authorization value must never be
            // logged) sent to this MediaSource: the extractor's headers
            // — Cookie included, when the extractor returns one — flow
            // via getAllHeaders() -> PlayLink.headers -> here, and
            // setDefaultRequestProperties applies them to the manifest,
            // child-playlist AND segment requests of this MediaSource
            // (one factory for the whole source). Verified, not rebuilt.
            EngineLogger.log("player", "play headers source=${link.source} keys=${headers.keys.sorted().joinToString(",")}")
            val dataFactory = if (link.isLocalFile) {
                DefaultDataSource.Factory(context)
            } else {
                val httpFactory = OkHttpDataSource.Factory(app.baseClient)
                    .setUserAgent(USER_AGENT)
                    .setDefaultRequestProperties(headers)
                // (v1.0.13 P4-A) Child-URI token preservation, signed
                // manifests only: the wrapper re-attaches this play's
                // manifest query to child/segment requests that lack
                // one (INVESTIGATION-4 §3.7 L2). Unsigned links (e.g.
                // Castle TV) keep the exact pre-P4 factory. Logged
                // token-redacted, at most once per child URI per play.
                val reattachedLogged = HashSet<String>()
                val playFactory: DataSource.Factory =
                    if (ZaynHeaderManager.isSignedUrl(link.url)) {
                        EngineLogger.log("player", "play child token re-attach enabled source=${link.source} url=${redactUrlForLog(link.url)}")
                        TokenReattachingDataSourceFactory(httpFactory, link.url) { rewritten ->
                            val redacted = redactUrlForLog(rewritten)
                            if (reattachedLogged.add(redacted)) {
                                EngineLogger.log("player", "child token re-attached uri=$redacted manifest=${redactUrlForLog(link.url)}")
                            }
                        }
                    } else {
                        httpFactory
                    }
                DefaultDataSource.Factory(context, playFactory)
            }
            val mime = when (link.type) {
                ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
                ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
                else -> null
            }
            val item = buildMediaItem(link, mime, subtitleText, subtitleLang, subtitleUrl)
            // Per-link factory: rebuild the source factory so the link's
            // headers/referer apply (the player-level factory has none).
            p.setMediaSource(
                DefaultMediaSourceFactory(dataFactory).createMediaSource(item),
            )
            // (v1.0.4) NO-SWITCH rule: pin ExoPlayer's audio-track selector
            // to the picked link's language, so a multi-audio manifest
            // cannot silently switch to another language (e.g. Tamil)
            // mid-play while the player shows the picked language.
            if (preferredAudioLang != null) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(preferredAudioLang)
                    .build()
            }
            // (v1.0.9) Belt-and-braces: make the framework create the
            // SurfaceTexture promptly, before prepare.
            textureView?.visibility = View.VISIBLE
            // (v1.0.12 §3) Prepare gate: wait (bounded — poll every 100 ms,
            // up to 1500 ms) for a valid, non-degenerate SurfaceTexture,
            // then prepare REGARDLESS and log whether the gate passed.
            // Media3 tolerates prepare-without-surface (session 1 proved
            // frames reach the texture later) — never block playback forever on the
            // gate. The post-prepare logs moved into prepareAndPlay().
            waitForSurfaceThenPrepare(p, link, startAtMs, 0)
        }
    }

    // (v1.0.12 §3) Bounded prepare gate (see play()). Runs on the main
    // thread; the wait is postDelayed polling, never a block.
    private fun waitForSurfaceThenPrepare(p: ExoPlayer, link: PlayLink, startAtMs: Long, waitedMs: Long) {
        val tv = textureView
        val st = tv?.surfaceTexture
        val ready = st != null && (tv?.width ?: 0) > 3 && (tv?.height ?: 0) > 3
        if (ready) {
            if (!surfaceAvailableEmitted) {
                surfaceAvailableEmitted = true
                if (surfaceState != SurfaceLifecycle.PREPARED &&
                    surfaceState != SurfaceLifecycle.PLAYING &&
                    surfaceState != SurfaceLifecycle.RELEASED
                ) {
                    transitionTo(SurfaceLifecycle.SURFACE_AVAILABLE, "prepare gate passed")
                }
                diag("L2", "Surface Available")
            }
            EngineLogger.log("player", "prepare gate passed waited=${waitedMs}ms texId=${System.identityHashCode(st)} view=${tv?.width ?: 0}x${tv?.height ?: 0}")
            diag("L3", "Surface Valid", "gate passed waited=${waitedMs}ms")
            prepareAndPlay(p, link, startAtMs)
        } else if (waitedMs < 1500) {
            mainHandler.postDelayed({ waitForSurfaceThenPrepare(p, link, startAtMs, waitedMs + 100) }, 100)
        } else {
            EngineLogger.log("player", "prepare gate timeout after 1500ms — preparing without surface (Media3 tolerates; frames reach the texture later)")
            diag("L3", "Surface Valid", "UNVERIFIED — gate timed out, surfaceTexture=${st != null} view=${tv?.width ?: 0}x${tv?.height ?: 0}")
            prepareAndPlay(p, link, startAtMs)
        }
    }

    private fun prepareAndPlay(p: ExoPlayer, link: PlayLink, startAtMs: Long) {
        // The player may have been released/rebuilt while the gate waited
        // (exit, or a new play) — never prepare a dead instance.
        if (player !== p) {
            EngineLogger.log("player", "prepare gate abandoned — player was released/rebuilt during wait")
            return
        }
        p.prepare()
        transitionTo(SurfaceLifecycle.PREPARED, "prepare()")
        diag("L5", "Player Prepared")
        if (startAtMs > 0) p.seekTo(startAtMs)
        p.play()
        transitionTo(SurfaceLifecycle.PLAYING, "play()")
        lastPlaying = true
        pushState()
        // (v1.0.5 B3) Name the picked link in the play log line (URL +
        // type + quality) so the next failure names the link — V1.0.4's
        // PARSING_CONTAINER_UNSUPPORTED named no link at all.
        EngineLogger.log("player", "play ${link.source} ${link.qualityLabel} type=${link.type} url=${redactUrlForLog(link.url)}")
        // (v1.0.13 P2-B T1) Log PlayLink.url (token-redacted) at prepare, plus
        // the token age at ExoPlayer fetch time (never the token itself).
        EngineLogger.log("player", "prepare url=${redactUrlForLog(link.url)} source=${link.source}${tokenAgeSuffix(link.url)}")
        // (v1.0.6 M2) Log the selected video track at play time: a null
        // videoFormat here (or 0 track groups) means ExoPlayer has no
        // video track for this link yet — read with the listener lines.
        val vf = p.videoFormat
        EngineLogger.log("player", "play videoFormat=${vf?.sampleMimeType ?: "none"} ${vf?.width ?: 0}x${vf?.height ?: 0} trackGroups=${p.currentTracks.groups.size}")
        // (v1.0.7) Log the surface state at play time — this line was
        // missing from the V1.0.6 log, so whether a SurfaceTexture was
        // even available at play time was unknowable.
        val tv = textureView
        val tvVis = when (tv?.visibility) {
            View.VISIBLE -> "VISIBLE"
            View.GONE -> "GONE"
            View.INVISIBLE -> "INVISIBLE"
            else -> "null"
        }
        EngineLogger.log(
            "player",
            "play surfaceTexture=${tv?.surfaceTexture != null} " +
                "surfaceSize=${p.surfaceSize.width}x${p.surfaceSize.height} " +
                "textureView=${tv?.width ?: 0}x${tv?.height ?: 0} visibility=$tvVis",
        )
    }

    private fun buildMediaItem(
        link: PlayLink,
        mime: String?,
        subtitleText: String?,
        subtitleLang: String?,
        subtitleUrl: String?,
    ): MediaItem {
        // (v1.0.13 P2-B T1) Log PlayLink.url (token-redacted) at buildMediaItem.
        EngineLogger.log("player", "buildMediaItem url=${redactUrlForLog(link.url)} source=${link.source}")
        val builder = MediaItem.Builder().setUri(Uri.parse(link.url))
        if (mime != null) builder.setMimeType(mime)
        if (subtitleText != null) {
            val subFile = writeSubtitleFile(subtitleText)
            if (subFile != null) {
                val subMime = if (subtitleText.trimStart().startsWith("WEBVTT")) {
                    MimeTypes.TEXT_VTT
                } else {
                    MimeTypes.APPLICATION_SUBRIP
                }
                builder.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subFile))
                            .setMimeType(subMime)
                            .setLanguage(subtitleLang ?: "und")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build(),
                    ),
                )
            }
        } else if (subtitleUrl != null) {
            val subMime = if (subtitleUrl.endsWith(".vtt", ignoreCase = true)) {
                MimeTypes.TEXT_VTT
            } else {
                MimeTypes.APPLICATION_SUBRIP
            }
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                        .setMimeType(subMime)
                        .setLanguage(subtitleLang ?: "und")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        return builder.build()
    }

    private fun writeSubtitleFile(text: String): File? {
        return try {
            val isVtt = text.trimStart().startsWith("WEBVTT")
            val f = File(context.cacheDir, if (isVtt) "zp-sub.vtt" else "zp-sub.srt")
            f.writeText(text)
            f
        } catch (e: Exception) {
            EngineLogger.log("player", "subtitle write failed")
            null
        }
    }

    /** Attach downloaded subtitle text to the current item, keeping position. */
    fun setSubtitleText(text: String?, lang: String?) {
        mainHandler.post {
            val p = player ?: return@post
            val link = currentLink ?: return@post
            currentSubtitleText = text
            currentSubtitleLang = lang
            val pos = p.currentPosition
            val wasPlaying = p.isPlaying
            val item = buildMediaItem(link, mimeOf(link), text, lang, null)
            // Media3 1.4.1 has no 3-arg setMediaItem(item, pos, reset): set the
            // item, then seek to the saved position before preparing.
            p.setMediaItem(item)
            p.seekTo(pos)
            p.prepare()
            if (wasPlaying) p.play()
        }
    }

    private fun mimeOf(link: PlayLink): String? = when (link.type) {
        ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
        ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
        else -> null
    }

    /** Player controls from the bridge. value is seconds / rate / 0..1 volume. */
    fun control(action: String, value: Double?): JSONObject {
        // (v1.0.13 P6-C) Dead-session guard (INVESTIGATION-4 §7.3): seek /
        // resume on a player with no loaded media is a silent no-op that
        // returns a stale cached snapshot — refuse it with an error
        // snapshot instead of the silent ok.
        if ((action == "seek" || action == "resume") && !hasActiveMedia()) {
            EngineLogger.log("player", "control $action rejected — no active media (playback_error)")
            return errorStateSnapshot()
        }
        // (v1.0.12 §10) Volume/seek coalescing (last-write-wins): keep only
        // the latest pending value and post at most one runnable — rapid
        // pointermove/input events collapse instead of queueing a runnable
        // each. No native delay: the JS 250 ms seek debounce / 100 ms
        // volume throttle is the single debounce point.
        if (action == "volume" || action == "seek") {
            if (action == "volume") pendingVolume = (value ?: 1.0).toFloat().coerceIn(0f, 1f)
            else pendingSeekSec = value ?: 0.0
            if (!controlCoalescePosted) {
                controlCoalescePosted = true
                mainHandler.post(controlCoalesceRunnable)
            }
            return stateSnapshot()
        }
        mainHandler.post {
            val p = player
            // (v1.0.13 P6-C) The media can die between control() and this
            // posted run — re-check before issuing seek/resume.
            if ((action == "seek" || action == "resume") && !hasActiveMedia()) {
                EngineLogger.log("player", "control $action rejected — no active media (playback_error)")
                return@post
            }
            when (action) {
                "pause" -> p?.pause()
                "resume" -> p?.play()
                "toggle" -> if (p?.isPlaying == true) p.pause() else p?.play()
                "seek" -> {
                    val target = ((value ?: 0.0) * 1000).toLong()
                    val dur = p?.duration ?: 0L
                    p?.seekTo(target.coerceIn(0, if (dur > 0) dur else target))
                }
                "rate" -> {
                    val r = (value ?: 1.0).toFloat().coerceIn(0.25f, 4f)
                    p?.setPlaybackSpeed(r)
                    lastRate = r
                }
                "volume" -> {
                    val v = (value ?: 1.0).toFloat().coerceIn(0f, 1f)
                    lastVolume = v
                    muted = v <= 0f
                    p?.volume = v
                }
                "mute" -> {
                    // value >= 1 mutes, value 0 unmutes; null toggles (old behaviour).
                    val wantMute = when (value) {
                        null -> !muted
                        else -> value >= 1.0
                    }
                    if (wantMute) {
                        if (!muted) preMuteVolume = lastVolume
                        muted = true
                        p?.volume = 0f
                    } else {
                        muted = false
                        p?.volume = preMuteVolume
                    }
                }
            }
            if (p != null) {
                lastPosMs = p.currentPosition.coerceAtLeast(0)
                lastDurMs = p.duration.takeIf { it > 0 } ?: lastDurMs
                lastPlaying = p.isPlaying
            }
            pushState()
        }
        // The posted block runs on main almost immediately; return the
        // last cached snapshot (the next playerState event carries the
        // fresh numbers). Never block the bridge thread on the main thread.
        return stateSnapshot()
    }

    fun stateSnapshot(): JSONObject = JSONObject()
        .put("playing", lastPlaying)
        .put("buffering", lastBuffering)
        .put("posSec", (lastPosMs / 1000).toInt())
        .put("durSec", (lastDurMs / 1000).toInt())
        .put("bufferedSec", (lastBufferedMs / 1000).toInt())
        .put("rate", lastRate.toDouble())
        .put("volume", lastVolume.toDouble())
        .put("muted", muted)
        .put("qualityLabel", lastQuality)
        .put("source", lastSource)
        .put("audio", lastAudio ?: JSONObject.NULL)

    /** (v1.0.13 P6-C) The state snapshot with a playback_error marker, so a
     * refused control is distinguishable from a stale ok snapshot. */
    private fun errorStateSnapshot(): JSONObject = stateSnapshot().put("error", "playback_error")

    private fun pushState() {
        lastPushStateMs = System.currentTimeMillis()
        onEvent("playerState", stateSnapshot())
    }

    // (v1.0.12 §10) Applies the latest coalesced volume/seek (see
    // control()). Volume-only controls skip pushState() when the last one
    // was <500 ms ago — the 1 s ticker delivers the fresh state anyway.
    private val controlCoalesceRunnable = object : Runnable {
        override fun run() {
            controlCoalescePosted = false
            val p = player
            var volumeOnly = false
            pendingVolume?.let { v ->
                pendingVolume = null
                volumeOnly = pendingSeekSec == null
                lastVolume = v
                muted = v <= 0f
                p?.volume = v
            }
            pendingSeekSec?.let { sec ->
                pendingSeekSec = null
                volumeOnly = false
                // (v1.0.13 P6-C) Drop a coalesced seek when the media died
                // after control() queued it.
                if (!hasActiveMedia()) {
                    EngineLogger.log("player", "control seek rejected — no active media (playback_error)")
                } else {
                    val target = (sec * 1000).toLong()
                    val dur = p?.duration ?: 0L
                    p?.seekTo(target.coerceIn(0, if (dur > 0) dur else target))
                }
            }
            if (p != null) {
                lastPosMs = p.currentPosition.coerceAtLeast(0)
                lastDurMs = p.duration.takeIf { it > 0 } ?: lastDurMs
                lastPlaying = p.isPlaying
            }
            if (volumeOnly && System.currentTimeMillis() - lastPushStateMs < 500) return
            pushState()
        }
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        mainHandler.post(ticker)
    }

    private fun stopTicker() {
        tickerRunning = false
        mainHandler.removeCallbacks(ticker)
    }

    /** Current link (for resume / next-episode), or null. */
    fun currentLink(): PlayLink? = currentLink

    fun pauseForBackground() {
        mainHandler.post { player?.pause() }
    }

    /** (v1.0.12 §6 step 1) Switch-time detach for the shared TextureView:
     *  pause, then release ExoPlayer's surface hold
     *  (setVideoTextureView(null)) so libmpv's EGL can connect to the
     *  BufferQueue. The re-attach on the way back is reattachTextureView()
     *  — without it a later ExoPlayer play would run surface-less
     *  (ensurePlayer early-returns). The "player: exo surface detached for
     *  mpv switch" line is logged by ZaynEngine.playActive when it issues
     *  the detach. */
    fun detachSurfaceForSwitch() {
        mainHandler.post {
            try { player?.pause() } catch (e: Exception) { }
            try { player?.setVideoTextureView(null) } catch (e: Exception) { }
            surfaceDetachedForSwitch = true
            transitionTo(SurfaceLifecycle.SURFACE_DESTROYED, "detach for mpv switch")
        }
    }

    /** (v1.0.12 §6 step 1) Re-attach after a switch-time detach. No-op when
     *  the surface was never detached: setVideoTextureView on an already
     *  attached player would needlessly re-register Media3's listener. */
    fun reattachTextureView() {
        mainHandler.post {
            if (!surfaceDetachedForSwitch) {
                EngineLogger.log("player", "player: exo surface re-attach skipped — already attached")
                return@post
            }
            val tv = textureView ?: return@post
            try { player?.setVideoTextureView(tv) } catch (e: Exception) { }
            surfaceDetachedForSwitch = false
            transitionTo(SurfaceLifecycle.SURFACE_ATTACHED, "re-attach after mpv switch")
            diag("L4", "Surface Attached", "re-attach after switch")
        }
    }

    /** (v1.0.12 §7) Stop the previous session outright (not just pause):
     *  stop + clear the media items so no stale ticker/pushState can
     *  interleave with the next session. */
    fun stopPlayback() {
        mainHandler.post {
            try {
                player?.stop()
                player?.clearMediaItems()
            } catch (e: Exception) { }
        }
    }

    /** (v1.0.12 §9) Bounded surface recovery: one detach+re-attach per
     *  play. Triggered by MainActivity.postSurfaceCheck when the texture
     *  is still null after stage-open. A second call in the same play is
     *  a no-op (never infinite). */
    fun recoverSurface() {
        mainHandler.post {
            if (surfaceRecoveriesThisPlay >= 1) {
                EngineLogger.log("player", "player: recovery surface re-attach skipped — already used this play")
                return@post
            }
            surfaceRecoveriesThisPlay++
            transitionTo(SurfaceLifecycle.RECOVERING, "surface recovery attempt 1/1")
            try { player?.setVideoTextureView(null) } catch (e: Exception) { }
            val tv = textureView ?: return@post
            try { player?.setVideoTextureView(tv) } catch (e: Exception) { }
            surfaceDetachedForSwitch = false
            transitionTo(SurfaceLifecycle.SURFACE_ATTACHED, "surface recovery re-attach")
            EngineLogger.log("player", "player: recovery surface re-attached (1/1 this play)")
        }
    }

    fun isPlaying(): Boolean = lastPlaying

    /** (v1.0.12 §7) True while media is loaded and not ended/idle — the
     *  dedup signal for a same-title/same-link replay. */
    fun hasActiveMedia(): Boolean {
        val p = player ?: return false
        return p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED
    }

    /** Release the player + its disk cache on the main thread, then call back. */
    fun clearCache(onDone: () -> Unit) {
        mainHandler.post {
            releaseNow()
            onDone()
        }
    }

    fun release() {
        mainHandler.post { releaseNow() }
    }

    private fun releaseNow() {
        stopTicker()
        try {
            // (v1.0.9) Detach the view-managed surface on release only.
            player?.setVideoTextureView(null)
        } catch (e: Exception) {
        }
        surfaceDetachedForSwitch = false
        if (surfaceState != SurfaceLifecycle.RELEASED) {
            transitionTo(SurfaceLifecycle.SURFACE_DESTROYED, "release")
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        transitionTo(SurfaceLifecycle.RELEASED, "release")
        try {
            cache?.release()
        } catch (e: Exception) {
        }
        cache = null
    }
}
