package app.netlify.zaynplay.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.File
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
            pushState()
        }

        // (v1.0.6 M2) Settles whether a video track is selected and frames
        // reach the surface: if onVideoSizeChanged never fires, no video
        // track was selected; if onRenderedFirstFrame fires, frames arrive
        // and the V1.0.5 audio-only picture was the covering .player-info.
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            EngineLogger.log("player", "video size ${videoSize.width}x${videoSize.height}")
        }

        override fun onRenderedFirstFrame() {
            EngineLogger.log("player", "first frame rendered")
        }

        override fun onPlayerError(error: PlaybackException) {
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
        textureView?.let { exo.setVideoTextureView(it) }
        player = exo
        builtBufferLevel = pendingBufferLevel
        EngineLogger.log("player", "ExoPlayer created")
    }

    /** Attach (or detach with null) the video surface. Main thread. */
    fun setSurface(view: TextureView?) {
        mainHandler.post {
            textureView = view
            player?.setVideoTextureView(view)
        }
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
        mainHandler.post {
            // (v1.0.4) A changed Buffering level rebuilds the player here
            // (LoadControl is build-time); the new play starts fresh
            // anyway, so nothing in progress is disturbed.
            pendingBufferLevel = bufferLevel
            if (builtBufferLevel != null && builtBufferLevel != bufferLevel) {
                releaseNow()
            }
            ensurePlayer(cacheSizeBytes)
            val p = player ?: return@post
            currentLink = link
            currentSubtitleText = subtitleText
            currentSubtitleLang = subtitleLang
            lastQuality = link.qualityLabel
            lastSource = link.source
            lastAudio = link.audio
            val headers = buildMap<String, String> {
                if (link.referer.isNotBlank()) put("referer", link.referer)
                putAll(link.headers)
            }
            val dataFactory = if (link.isLocalFile) {
                DefaultDataSource.Factory(context)
            } else {
                OkHttpDataSource.Factory(app.baseClient)
                    .setUserAgent(USER_AGENT)
                    .setDefaultRequestProperties(headers)
                    .let { DefaultDataSource.Factory(context, it) }
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
            p.prepare()
            if (startAtMs > 0) p.seekTo(startAtMs)
            p.play()
            lastPlaying = true
            pushState()
            // (v1.0.5 B3) Name the picked link in the play log line (URL +
            // type + quality) so the next failure names the link — V1.0.4's
            // PARSING_CONTAINER_UNSUPPORTED named no link at all.
            EngineLogger.log("player", "play ${link.source} ${link.qualityLabel} type=${link.type} url=${link.url}")
            // (v1.0.6 M2) Log the selected video track at play time: a null
            // videoFormat here (or 0 track groups) means ExoPlayer has no
            // video track for this link yet — read with the listener lines.
            val vf = p.videoFormat
            EngineLogger.log("player", "play videoFormat=${vf?.sampleMimeType ?: "none"} ${vf?.width ?: 0}x${vf?.height ?: 0} trackGroups=${p.currentTracks.groups.size}")
        }
    }

    private fun buildMediaItem(
        link: PlayLink,
        mime: String?,
        subtitleText: String?,
        subtitleLang: String?,
        subtitleUrl: String?,
    ): MediaItem {
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
        mainHandler.post {
            val p = player
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

    private fun pushState() {
        onEvent("playerState", stateSnapshot())
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
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        try {
            cache?.release()
        } catch (e: Exception) {
        }
        cache = null
    }
}
