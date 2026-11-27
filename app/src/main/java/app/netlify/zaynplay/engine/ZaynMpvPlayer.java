package app.netlify.zaynplay.engine;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import com.lagradost.cloudstream3.MainAPIKt;
import dev.jdtech.mpv.MPVLib;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * (v1.0.10) libmpv player — the second internal engine, next to ExoPlayer.
 *
 * JAVA, not Kotlin, on purpose: MPVLib's Kotlin metadata is mv 2.2.0 and
 * kotlinc 1.9.24 rejects library metadata newer than 2.0.0 (the ksoup 0.2.6
 * / coroutines 1.11.0 lesson). javac never reads Kotlin metadata, so the
 * MPVLib calls live here, behind a Java interface ZaynEngine can call from
 * Kotlin without ever naming MPVLib itself.
 *
 * Interface mirrors ZaynPlayer exactly (same method names, same argument
 * order, same stateSnapshot keys) so ZaynEngine routes to the active engine
 * with no HTML or bridge change: same WebView controls, same glass pill,
 * same watermark, same gestures, same Server Loading flow.
 *
 * Engine facts (midasvo libmpv 1.4.0, verified against the published AAR):
 *  - MPVLib.create(context) returns the instance (null on failure).
 *  - Classic sync API only: setOptionString / init / attachSurface /
 *    command / setProperty* / getProperty* / observeProperty / destroy.
 *    The wrapper's suspend/flow APIs are NOT used (they need coroutines).
 *  - mpv 0.42 routes http(s) through libcurl, tls-verify defaults to yes,
 *    and the build has no CA bundle of its own: the system CA store
 *    (/system/etc/security/cacerts + /apex/com.android.conscrypt/cacerts)
 *    is concatenated into a PEM file in the cache dir and passed as
 *    tls-ca-file, the way mpv-android does it.
 *  - mpv renders straight to the attached Surface (vo=gpu); there is no
 *    Media3 surface-size reporting step in this path.
 *  - destroy() must never be called from an mpv event callback; release()
 *    runs on the main thread. Surface detach is synchronous (vo=null,
 *    then detachSurface) when the surface goes away.
 */
public class ZaynMpvPlayer {

    /** Callback into MainActivity/ZaynEngine, on the main thread. */
    public interface EventListener {
        void onEvent(String type, JSONObject data);
    }

    private final Context appContext;
    private final EventListener onEvent;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MPVLib mpv;
    private boolean created;
    private TextureView textureView;
    private Surface attachedSurface;
    private int attachedTexId;
    private boolean surfaceAttached;
    private int attachRetries;

    private ZaynPlayer.PlayLink currentLink;
    private String currentSubtitleText;
    private String currentSubtitleLang;

    private volatile boolean lastPlaying;
    private volatile boolean lastBuffering;
    private volatile long lastPosMs;
    private volatile long lastDurMs;
    private volatile long lastBufferedMs;
    private volatile float lastRate = 1f;
    private volatile String lastQuality = "";
    private volatile String lastSource = "";
    private volatile String lastAudio;
    private volatile float lastVolume = 1f;
    private volatile boolean muted;
    private volatile float preMuteVolume = 1f;

    private boolean tickerRunning;
    private boolean firstFrameLogged;
    private boolean startSeekDone;
    private long pendingStartAtMs;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            MPVLib m = mpv;
            if (m != null) {
                try {
                    Double pos = m.getPropertyDouble("time-pos");
                    if (pos != null) lastPosMs = Math.max(0L, (long) (pos * 1000));
                    Double dur = m.getPropertyDouble("duration");
                    if (dur != null && dur > 0) lastDurMs = (long) (dur * 1000);
                    Double cache = m.getPropertyDouble("demuxer-cache-duration");
                    if (cache != null) lastBufferedMs = Math.max(0L, (long) (cache * 1000));
                } catch (Exception ignored) {
                }
                pushState();
            }
            if (tickerRunning) mainHandler.postDelayed(this, 1000);
        }
    };

    // (v1.0.10) JSONObject.put throws checked JSONException, so every put
    // in this file must go through this helper (CI 46046097780: "unreported
    // exception JSONException" at 18 call sites). A put can only fail on a
    // null key / unsupported value, neither of which occurs here, so a
    // failure is silently ignored and the object is returned as-is.
    private static JSONObject put(JSONObject o, String key, Object value) {
        try { o.put(key, value); } catch (JSONException ignored) { }
        return o;
    }

    private final MPVLib.EventObserver observer = new MPVLib.EventObserver() {
        @Override
        public void event(int eventId) {
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG) {
                onVideoReconfig();
            } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
                onFileLoaded();
            }
        }

        @Override
        public void eventEndFile(int reason, int error) {
            if (reason == MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_EOF) {
                lastPlaying = false;
                postEvent("playerEnded", put(new JSONObject(), "source", lastSource));
                pushState();
            } else if (reason == MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR) {
                EngineLogger.INSTANCE.log("player", "mpv: end of file (error)");
                lastPlaying = false;
                JSONObject err = new JSONObject();
                put(err, "code", "playback_error");
                put(err, "message", "Video could not be played");
                put(err, "retryable", true);
                postEvent("playerError", err);
                pushState();
            }
        }

        @Override
        public void eventProperty(String property, double value) {
            if ("time-pos".equals(property)) {
                lastPosMs = Math.max(0L, (long) (value * 1000));
            } else if ("duration".equals(property)) {
                if (value > 0) lastDurMs = (long) (value * 1000);
            }
        }

        @Override
        public void eventProperty(String property, boolean value) {
            if ("pause".equals(property)) {
                lastPlaying = !value;
                if (!value) startTicker(); else stopTicker();
                pushState();
            } else if ("paused-for-cache".equals(property)) {
                lastBuffering = value;
                pushState();
            }
        }
    };

    public ZaynMpvPlayer(Context context, EventListener onEvent) {
        this.appContext = context.getApplicationContext();
        this.onEvent = onEvent;
    }

    /** Idempotent create. Returns false when libmpv could not start — the
     * caller (ZaynEngine.playActive) then falls back to ExoPlayer. */
    public synchronized boolean ensureCreated() {
        if (created) return mpv != null;
        created = true;
        try {
            MPVLib m = MPVLib.create(appContext);
            if (m == null) {
                EngineLogger.INSTANCE.log("player", "mpv: create returned null");
                return false;
            }
            String caFile = buildCaBundle();
            if (caFile != null) {
                m.setOptionString("tls-ca-file", caFile);
                m.setOptionString("tls-verify", "yes");
            } else {
                EngineLogger.INSTANCE.log("player", "mpv: no CA bundle — https may fail TLS verify");
            }
            m.setOptionString("gpu-context", "android");
            m.setOptionString("hwdec", "mediacodec");
            m.init();
            m.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
            m.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
            m.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            m.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            m.addObserver(observer);
            mpv = m;
            EngineLogger.INSTANCE.log("player", "mpv: created");
            return true;
        } catch (Throwable t) {
            EngineLogger.INSTANCE.log("player", "mpv: create failed " + t.getMessage());
            mpv = null;
            return false;
        }
    }

    public boolean isReady() {
        return mpv != null;
    }

    /** Concatenate the system CA store into one PEM file libcurl can use.
     * Returns the path, or null when no certificates could be read. */
    private String buildCaBundle() {
        try {
            File out = new File(appContext.getCacheDir(), "mpv-cacert.pem");
            if (out.exists() && out.length() > 0) return out.getAbsolutePath();
            String[] dirs = {
                "/system/etc/security/cacerts",
                "/apex/com.android.conscrypt/cacerts",
            };
            int count = 0;
            try (OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                for (String dirPath : dirs) {
                    File dir = new File(dirPath);
                    File[] files = dir.listFiles();
                    if (files == null) continue;
                    for (File f : files) {
                        if (!f.getName().endsWith(".0")) continue;
                        try (InputStream is = new java.io.FileInputStream(f)) {
                            int n;
                            while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                        } catch (Exception ignored) {
                        }
                        count++;
                    }
                }
            }
            if (count == 0 || out.length() == 0) {
                out.delete();
                return null;
            }
            EngineLogger.INSTANCE.log("player", "mpv: ca bundle " + count + " certs");
            return out.getAbsolutePath();
        } catch (Exception e) {
            EngineLogger.INSTANCE.log("player", "mpv: ca bundle failed " + e.getMessage());
            return null;
        }
    }

    /** Mirror of ZaynPlayer.setTextureView: store the view and attach the
     * surface once its SurfaceTexture exists (never a TextureView listener:
     * Media3's setVideoTextureView owns that listener for the ExoPlayer
     * path, and replacing it would break the sink's size reporting). */
    public void setTextureView(TextureView view) {
        textureView = view;
        if (view != null && mpv != null) attachSurfaceIfReady();
    }

    private void attachSurfaceIfReady() {
        MPVLib m = mpv;
        TextureView tv = textureView;
        if (m == null || tv == null) return;
        SurfaceTexture st = tv.getSurfaceTexture();
        if (st == null) {
            // Texture not created yet (the V1.0.7 lesson): retry briefly on
            // the main thread until it exists, then attach. loadfile is
            // already issued by then; mpv renders once the surface lands.
            if (attachRetries < 25) {
                attachRetries++;
                mainHandler.postDelayed(this::attachSurfaceIfReady, 200);
            }
            return;
        }
        attachRetries = 0;
        int texId = System.identityHashCode(st);
        if (surfaceAttached && texId != attachedTexId) {
            // Stale surface: the texture was recreated (e.g. rotation).
            // Detach synchronously first, then attach the fresh one.
            try {
                m.setPropertyString("vo", "null");
            } catch (Exception ignored) {
            }
            try {
                m.detachSurface();
            } catch (Exception ignored) {
            }
            releaseAttachedSurface();
            surfaceAttached = false;
        }
        if (surfaceAttached) return;
        try {
            attachedSurface = new Surface(st);
            m.attachSurface(attachedSurface);
            attachedTexId = texId;
            surfaceAttached = true;
            EngineLogger.INSTANCE.log("player",
                    "mpv: surface attached " + tv.getWidth() + "x" + tv.getHeight());
        } catch (Exception e) {
            EngineLogger.INSTANCE.log("player", "mpv: attach failed " + e.getMessage());
            releaseAttachedSurface();
        }
    }

    private void releaseAttachedSurface() {
        if (attachedSurface != null) {
            try {
                attachedSurface.release();
            } catch (Exception ignored) {
            }
            attachedSurface = null;
        }
    }

    public void play(
            ZaynPlayer.PlayLink link,
            long startAtMs,
            String subtitleText,
            String subtitleLang,
            String subtitleUrl,
            long cacheSizeBytes,
            String bufferLevel,
            String preferredAudioLang) {
        mainHandler.post(() -> {
            if (!ensureCreated()) return;
            MPVLib m = mpv;
            if (m == null) return;
            currentLink = link;
            currentSubtitleText = subtitleText;
            currentSubtitleLang = subtitleLang;
            lastQuality = link.getQualityLabel();
            lastSource = link.getSource();
            lastAudio = link.getAudio();
            pendingStartAtMs = startAtMs;
            startSeekDone = false;
            firstFrameLogged = false;
            try {
                // Headers the signed CDN links need (E4: without Referer /
                // User-Agent the Netflix/Prime CDNs refuse; Disney accepted
                // VLC without them, so this must be per-link, not assumed).
                StringBuilder fields = new StringBuilder();
                if (link.getReferer() != null && !link.getReferer().isEmpty()) {
                    fields.append("Referer: ").append(link.getReferer());
                }
                for (Map.Entry<String, String> e : link.getHeaders().entrySet()) {
                    if (fields.length() > 0) fields.append(",");
                    fields.append(e.getKey()).append(": ").append(e.getValue());
                }
                if (fields.length() > 0) {
                    m.setOptionString("http-header-fields", fields.toString());
                }
                m.setOptionString("user-agent", MainAPIKt.USER_AGENT);
                if (preferredAudioLang != null && !preferredAudioLang.isEmpty()) {
                    m.setOptionString("alang", preferredAudioLang);
                }
                try {
                    m.setOptionString("demuxer-max-bytes", String.valueOf(cacheSizeBytes));
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                EngineLogger.INSTANCE.log("player", "mpv: option failed " + e.getMessage());
            }
            // (midasvo example order) attach first, then force-window + vo,
            // then loadfile. Surface attach is retried until the texture
            // exists (see attachSurfaceIfReady).
            attachSurfaceIfReady();
            try {
                m.setOptionString("force-window", "yes");
                m.setOptionString("vo", "gpu");
            } catch (Exception e) {
                EngineLogger.INSTANCE.log("player", "mpv: vo option failed " + e.getMessage());
            }
            // Subtitles ride along: downloaded text as a file, a provider
            // URL streamed directly — same inputs ZaynPlayer takes.
            if (subtitleText != null) {
                File subFile = writeSubtitleFile(subtitleText);
                if (subFile != null) {
                    try {
                        m.command(new String[]{"sub-add", subFile.getAbsolutePath()});
                    } catch (Exception ignored) {
                    }
                }
            } else if (subtitleUrl != null) {
                try {
                    m.command(new String[]{"sub-add", subtitleUrl});
                } catch (Exception ignored) {
                }
            }
            try {
                m.command(new String[]{"loadfile", link.getUrl()});
            } catch (Exception e) {
                EngineLogger.INSTANCE.log("player", "mpv: loadfile failed " + e.getMessage());
                JSONObject err = new JSONObject();
                put(err, "code", "playback_error");
                put(err, "message", "Video could not be played");
                put(err, "retryable", true);
                postEvent("playerError", err);
                return;
            }
            EngineLogger.INSTANCE.log("player", "mpv: loadfile " + link.getSource());
            lastPlaying = true;
            startTicker();
            pushState();
        });
    }

    /** Overload for callers that rely on ZaynPlayer.play's defaults. */
    public void play(
            ZaynPlayer.PlayLink link,
            long startAtMs,
            String subtitleText,
            String subtitleLang,
            String subtitleUrl,
            long cacheSizeBytes) {
        play(link, startAtMs, subtitleText, subtitleLang, subtitleUrl,
                cacheSizeBytes, "normal", null);
    }

    private void onVideoReconfig() {
        MPVLib m = mpv;
        if (m == null) return;
        try {
            Integer w = m.getPropertyInt("dwidth");
            Integer h = m.getPropertyInt("dheight");
            if (w != null && h != null) {
                EngineLogger.INSTANCE.log("player", "mpv: video size " + w + "x" + h);
            }
            String hwdec = m.getPropertyString("hwdec-current");
            String codec = m.getPropertyString("video-codec");
            EngineLogger.INSTANCE.log("player",
                    "mpv: hwdec-current=" + hwdec + " video-codec=" + codec);
        } catch (Exception ignored) {
        }
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            EngineLogger.INSTANCE.log("player", "mpv: first frame rendered");
            postEvent("playerFirstFrame", new JSONObject());
        }
    }

    private void onFileLoaded() {
        MPVLib m = mpv;
        if (m == null) return;
        if (pendingStartAtMs > 0 && !startSeekDone) {
            startSeekDone = true;
            try {
                m.command(new String[]{
                    "seek", String.valueOf(pendingStartAtMs / 1000.0), "absolute"});
            } catch (Exception ignored) {
            }
        }
    }

    private File writeSubtitleFile(String text) {
        try {
            boolean isVtt = text.trim().startsWith("WEBVTT");
            File f = new File(appContext.getCacheDir(),
                    isVtt ? "zp-sub.vtt" : "zp-sub.srt");
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return f;
        } catch (Exception e) {
            EngineLogger.INSTANCE.log("player", "mpv: subtitle write failed");
            return null;
        }
    }

    /** Attach downloaded subtitle text to the current item. */
    public void setSubtitleText(String text, String lang) {
        mainHandler.post(() -> {
            MPVLib m = mpv;
            if (m == null) return;
            currentSubtitleText = text;
            currentSubtitleLang = lang;
            try {
                if (text != null) {
                    File subFile = writeSubtitleFile(text);
                    if (subFile != null) {
                        m.command(new String[]{"sub-add", subFile.getAbsolutePath()});
                    }
                } else {
                    m.command(new String[]{"sub-remove"});
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** Player controls from the bridge. value is seconds / rate / 0..1 volume. */
    public JSONObject control(String action, Double value) {
        mainHandler.post(() -> {
            MPVLib m = mpv;
            if (m == null) return;
            try {
                switch (action) {
                    case "pause":
                        m.setPropertyBoolean("pause", true);
                        break;
                    case "resume":
                        m.setPropertyBoolean("pause", false);
                        break;
                    case "toggle":
                        m.command(new String[]{"cycle", "pause"});
                        break;
                    case "seek": {
                        double target = value != null ? value : 0.0;
                        m.command(new String[]{
                            "seek", String.valueOf(target), "absolute"});
                        break;
                    }
                    case "rate": {
                        float r = value != null ? value.floatValue() : 1f;
                        r = Math.max(0.25f, Math.min(4f, r));
                        m.setPropertyDouble("speed", r);
                        lastRate = r;
                        break;
                    }
                    case "volume": {
                        float v = value != null ? value.floatValue() : 1f;
                        v = Math.max(0f, Math.min(1f, v));
                        lastVolume = v;
                        muted = v <= 0f;
                        m.setPropertyDouble("volume", v * 100.0);
                        break;
                    }
                    case "mute": {
                        boolean wantMute = value == null ? !muted : value >= 1.0;
                        if (wantMute) {
                            if (!muted) preMuteVolume = lastVolume;
                            muted = true;
                            m.setPropertyBoolean("mute", true);
                        } else {
                            muted = false;
                            m.setPropertyBoolean("mute", false);
                            m.setPropertyDouble("volume", preMuteVolume * 100.0);
                        }
                        break;
                    }
                    default:
                        break;
                }
            } catch (Exception e) {
                EngineLogger.INSTANCE.log("player", "mpv: control " + action + " failed " + e.getMessage());
            }
            pushState();
        });
        return stateSnapshot();
    }

    public JSONObject stateSnapshot() {
        JSONObject o = new JSONObject();
        put(o, "playing", lastPlaying);
        put(o, "buffering", lastBuffering);
        put(o, "posSec", (int) (lastPosMs / 1000));
        put(o, "durSec", (int) (lastDurMs / 1000));
        put(o, "bufferedSec", (int) (lastBufferedMs / 1000));
        put(o, "rate", (double) lastRate);
        put(o, "volume", (double) lastVolume);
        put(o, "muted", muted);
        put(o, "qualityLabel", lastQuality);
        put(o, "source", lastSource);
        put(o, "audio", lastAudio != null ? lastAudio : JSONObject.NULL);
        return o;
    }

    private void pushState() {
        mainHandler.post(() -> onEvent.onEvent("playerState", stateSnapshot()));
    }

    private void postEvent(String type, JSONObject data) {
        mainHandler.post(() -> onEvent.onEvent(type, data));
    }

    private void startTicker() {
        if (tickerRunning) return;
        tickerRunning = true;
        mainHandler.post(ticker);
    }

    private void stopTicker() {
        tickerRunning = false;
        mainHandler.removeCallbacks(ticker);
    }

    /** Current link (for resume / next-episode), or null. */
    public ZaynPlayer.PlayLink currentLink() {
        return currentLink;
    }

    public void pauseForBackground() {
        mainHandler.post(() -> {
            MPVLib m = mpv;
            if (m == null) return;
            try {
                m.setPropertyBoolean("pause", true);
            } catch (Exception ignored) {
            }
        });
    }

    public void release() {
        mainHandler.post(this::releaseNow);
    }

    private void releaseNow() {
        stopTicker();
        MPVLib m = mpv;
        mpv = null;
        created = false;
        surfaceAttached = false;
        if (m != null) {
            // Synchronous detach first (vo=null), then destroy — never
            // destroy() from an mpv event callback; this runs on main.
            try {
                m.setPropertyString("vo", "null");
            } catch (Exception ignored) {
            }
            try {
                m.detachSurface();
            } catch (Exception ignored) {
            }
            try {
                m.destroy();
            } catch (Exception ignored) {
            }
        }
        releaseAttachedSurface();
    }
}
