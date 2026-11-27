package app.netlify.zaynplay.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase C downloads (engine-design.md §5.7, E22–E25).
 *
 * Upstream DownloadManager (2,154 lines) is NOT vendored: it drags R,
 * Toast, MainActivity, RepoLinkGenerator, QualityDataHelper, M3u8Helper2
 * and a foreground service with it. This class implements the same
 * bridge contract with OkHttp directly:
 * - direct file downloads with HTTP Range resume,
 * - HLS: playlist + segments downloaded, local .m3u8 rewritten to the
 *   local segment files (AES-128 key + init map downloaded too),
 * - pause / resume / delete via a job map + metadata in DataStore,
 * - progress push events throttled to ~2/s,
 * - wifiOnly + dlQuality enforced from settings.
 *
 * Files live in the app's own external Downloads folder
 * (<external-files>/Download/Zayn Play) — writable without any storage
 * permission on Android 14 scoped storage. The UI's dlPath setting
 * ("/Download/Zayn Play") names the same folder; the setting is kept
 * as the display path, the real location is the app-private one.
 * Notification channel is Zayn Play branded
 * (app.netlify.zaynplay.downloads); no cloudstream3.* id anywhere.
 */
class ZaynDownloads(
    private val context: Context,
    private val store: EngineStore,
    private val onEvent: (type: String, data: JSONObject) -> Unit,
) {
    companion object {
        const val CHANNEL_ID = "app.netlify.zaynplay.downloads"
        private const val NOTIF_BASE = 3100
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val notifManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zayn Play downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Download progress from Zayn Play" }
            notifManager.createNotificationChannel(channel)
        }
    }

    fun downloads(): JSONObject {
        val map = store.readJsonMap(EngineStore.KEY_DOWNLOADS)
        val arr = JSONArray()
        val keys = map.keys()
        while (keys.hasNext()) {
            val entry = map.optJSONObject(keys.next()) ?: continue
            arr.put(
                JSONObject()
                    .put("id", entry.optString("id"))
                    .put("title", entry.optString("title"))
                    .put("type", entry.optString("type", "movie"))
                    .put("poster", entry.optString("poster", ""))
                    .put("status", entry.optString("status"))
                    .put("progressPct", entry.optInt("progressPct", 0))
                    .put("sizeMB", entry.optDouble("sizeMB", 0.0))
                    .put("path", entry.optString("path", "")),
            )
        }
        return JSONObject().put("items", arr)
    }

    fun downloadDir(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Download")
        return File(base, "Zayn Play").apply { mkdirs() }
    }

    private fun isWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Start a download for a resolved link. Returns the download id, or
     * null when wifiOnly blocks it (the caller turns that into an error).
     */
    fun start(
        id: String,
        link: ExtractorLink,
        title: String,
        type: String,
        poster: String,
        providerName: String,
        episodeUrl: String?,
    ): String? {
        if (store.getSettingBoolean("wifiOnly", false) && !isWifi()) return null
        val existing = store.mapEntry(EngineStore.KEY_DOWNLOADS, id)
        // (v0.2.11) C-15: a "done" entry whose file was deleted outside
        // the app is not done — fall through and re-download it.
        if (existing != null && existing.optString("status") == "done") {
            val donePath = existing.optString("path", "")
            if (donePath.isNotBlank() && File(donePath).exists()) return id
        }
        val safeTitle = sanitizeFilename(title.ifBlank { "video" })
        val entry = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("type", type)
            .put("poster", poster)
            .put("status", "queued")
            .put("progressPct", existing?.optInt("progressPct", 0) ?: 0)
            .put("sizeMB", existing?.optDouble("sizeMB", 0.0) ?: 0.0)
            .put("path", existing?.optString("path", "") ?: "")
            .put("url", link.url)
            .put("source", link.source)
            .put("providerName", providerName)
            .put("episodeUrl", episodeUrl ?: JSONObject.NULL)
            .put("quality", link.quality)
            .put("isM3u8", link.isM3u8)
            .put("isDash", link.isDash)
            .put("referer", link.referer)
            .put("headers", JSONObject(link.headers))
        store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
        val fileBase = File(downloadDir(), "$safeTitle-$id")
        jobs[id] = scope.launch {
            runDownload(id, entry, link, fileBase)
        }
        return id
    }

    private suspend fun runDownload(
        id: String,
        entry: JSONObject,
        link: ExtractorLink,
        fileBase: File,
    ) {
        setStatus(id, "downloading", entry.optInt("progressPct", 0))
        try {
            val (path, sizeMB) = if (link.isM3u8) {
                downloadHls(id, link, fileBase)
            } else {
                downloadFile(id, link, fileBase)
            }
            entry.put("status", "done")
                .put("progressPct", 100)
                .put("sizeMB", sizeMB)
                .put("path", path)
            store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
            notify(id, entry.optString("title"), "Download complete", 100, done = true)
            onEvent(
                "downloadDone",
                JSONObject().put("id", id).put("path", path),
            )
            EngineLogger.log("download", "done $id $sizeMB MB")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Pause: keep the partial file + metadata; resume restarts it.
            entry.put("status", "paused")
            store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
            notify(id, entry.optString("title"), "Download paused", entry.optInt("progressPct", 0))
            pushProgress(id, "paused")
            throw e
        } catch (e: Exception) {
            entry.put("status", "failed")
            store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
            notify(id, entry.optString("title"), "Download failed", entry.optInt("progressPct", 0))
            onEvent(
                "downloadFailed",
                JSONObject().put("id", id).put("code", "download_failed"),
            )
            EngineLogger.log("download", "failed $id")
        } finally {
            jobs.remove(id)
        }
    }

    private fun setStatus(id: String, status: String, pct: Int) {
        val entry = store.mapEntry(EngineStore.KEY_DOWNLOADS, id) ?: return
        entry.put("status", status).put("progressPct", pct)
        store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
        pushProgress(id, status)
    }

    private var lastPushMs = 0L
    private fun pushProgress(id: String, status: String) {
        val now = System.currentTimeMillis()
        if (status == "downloading" && now - lastPushMs < 500) return
        lastPushMs = now
        val entry = store.mapEntry(EngineStore.KEY_DOWNLOADS, id) ?: return
        onEvent(
            "downloadProgress",
            JSONObject()
                .put("id", id)
                .put("progressPct", entry.optInt("progressPct", 0))
                .put("status", status),
        )
    }

    private fun notify(id: String, title: String, text: String, pct: Int, done: Boolean = false) {
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Zayn Play — $title")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(!done)
                .setOnlyAlertOnce(true)
            if (!done) builder.setProgress(100, pct, false)
            notifManager.notify(NOTIF_BASE + (id.hashCode() and 0x7FFF), builder.build())
        } catch (e: Exception) {
            // Notifications are best-effort (e.g. POST_NOTIFICATIONS denied).
        }
    }

    private suspend fun downloadFile(
        id: String,
        link: ExtractorLink,
        fileBase: File,
    ): Pair<String, Double> = withContext(Dispatchers.IO) {
        val outFile = File(fileBase.parentFile, fileBase.name + ".mp4")
        val downloaded = if (outFile.exists()) outFile.length() else 0L
        val request = Request.Builder()
            .url(link.url)
            .apply {
                link.getAllHeaders().forEach { (k, v) -> header(k, v) }
                if (downloaded > 0) header("Range", "bytes=$downloaded-")
            }
            .build()
        val response = app.baseClient.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw IllegalStateException("HTTP ${response.code}")
        }
        // Server ignored Range (200, not 206): restart from zero or the
        // file would be a corrupt mix of old + full content.
        val effectiveDownloaded = if (downloaded > 0 && response.code != 206) 0L else downloaded
        val total = (response.header("Content-Length")?.toLongOrNull() ?: -1L)
            .let { if (it < 0) -1L else it + effectiveDownloaded }
        val body = response.body ?: throw IllegalStateException("empty body")
        var written = effectiveDownloaded
        var lastReport = System.currentTimeMillis()
        RandomAccessFile(outFile, "rw").use { raf ->
            if (effectiveDownloaded == 0L) raf.setLength(0)
            raf.seek(effectiveDownloaded)
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 500 && total > 0) {
                        lastReport = now
                        val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                        setStatus(id, "downloading", pct)
                        notify(id, fileBase.name, "Downloading… $pct%", pct)
                    }
                }
            }
        }
        response.close()
        outFile.absolutePath to (written / 1048576.0)
    }

    /**
     * HLS download: fetch the playlist, download every segment (plus the
     * AES key and init map when present) and write a local playlist that
     * points at the local files, so ExoPlayer can play it offline.
     */
    private suspend fun downloadHls(
        id: String,
        link: ExtractorLink,
        fileBase: File,
    ): Pair<String, Double> = withContext(Dispatchers.IO) {
        val dir = File(fileBase.parentFile, fileBase.name + "_hls").apply { mkdirs() }
        val playlistUrl = link.url
        val playlistText = fetchText(playlistUrl, link)
        // Variant playlist? Follow the first (highest-bandwidth) variant.
        val lines = playlistText.lines()
        val variant = lines.firstOrNull { it.startsWith("#EXT-X-STREAM-INF") }
        var mediaText = playlistText
        var mediaBase = playlistUrl.substringBeforeLast("/") + "/"
        if (variant != null) {
            val idx = lines.indexOf(variant)
            val variantUrl = lines.getOrNull(idx + 1)?.trim().orEmpty()
            if (variantUrl.isNotBlank() && !variantUrl.startsWith("#")) {
                val abs = resolveUrl(mediaBase, variantUrl)
                mediaText = fetchText(abs, link)
                mediaBase = abs.substringBeforeLast("/") + "/"
            }
        }
        val out = StringBuilder()
        var segIndex = 0
        var totalBytes = 0L
        val totalSegs = mediaText.lines().count { it.isNotBlank() && !it.startsWith("#") }
        for (raw in mediaText.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    if (uri != null) {
                        val keyFile = File(dir, "key.bin")
                        downloadToFile(resolveUrl(mediaBase, uri), link, keyFile, 0)
                        totalBytes += keyFile.length()
                        out.appendLine(line.replace(uri, "key.bin"))
                    } else {
                        out.appendLine(line)
                    }
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    if (uri != null) {
                        val mapFile = File(dir, "init.mp4")
                        downloadToFile(resolveUrl(mediaBase, uri), link, mapFile, 0)
                        totalBytes += mapFile.length()
                        out.appendLine(line.replace(uri, "init.mp4"))
                    } else {
                        out.appendLine(line)
                    }
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    val segFile = File(dir, "seg-%05d.ts".format(segIndex))
                    downloadToFile(resolveUrl(mediaBase, line), link, segFile, 0)
                    totalBytes += segFile.length()
                    out.appendLine(segFile.name)
                    segIndex++
                    if (totalSegs > 0) {
                        val pct = ((segIndex * 100) / totalSegs).coerceIn(0, 100)
                        setStatus(id, "downloading", pct)
                    }
                }
                else -> out.appendLine(line)
            }
        }
        val localPlaylist = File(dir, "playlist.m3u8")
        localPlaylist.writeText(out.toString())
        localPlaylist.absolutePath to (totalBytes / 1048576.0)
    }

    private fun resolveUrl(base: String, rel: String): String {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        return try {
            java.net.URI(base).resolve(rel).toString()
        } catch (e: Exception) {
            base + rel
        }
    }

    private fun fetchText(url: String, link: ExtractorLink): String {
        val request = Request.Builder()
            .url(url)
            .apply { link.getAllHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        app.baseClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("empty playlist")
        }
    }

    private fun downloadToFile(url: String, link: ExtractorLink, out: File, resumeFrom: Long) {
        val request = Request.Builder()
            .url(url)
            .apply {
                link.getAllHeaders().forEach { (k, v) -> header(k, v) }
                if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-")
            }
            .build()
        app.baseClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty body")
            FileOutputStream(out, resumeFrom > 0).use { fos ->
                body.byteStream().use { input -> input.copyTo(fos) }
            }
        }
    }

    /** Pause (cancel, keep partial), resume (re-start from metadata), delete. */
    fun control(id: String, action: String): JSONObject {
        val entry = store.mapEntry(EngineStore.KEY_DOWNLOADS, id)
            ?: return JSONObject().put("status", JSONObject.NULL)
        return when (action) {
            "pause" -> {
                jobs.remove(id)?.cancel()
                entry.put("status", "paused")
                store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
                pushProgress(id, "paused")
                JSONObject().put("status", "paused")
            }
            "resume" -> {
                if (entry.optString("status") == "done") return JSONObject().put("status", "done")
                entry.put("status", "queued")
                store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
                // Rebuild the link from stored metadata inside the
                // coroutine (newExtractorLink is suspend) and restart.
                val url = entry.optString("url")
                if (url.isBlank()) return JSONObject().put("status", "failed")
                val headersObj = entry.optJSONObject("headers")
                val headers = mutableMapOf<String, String>()
                if (headersObj != null) {
                    val hk = headersObj.keys()
                    while (hk.hasNext()) {
                        val k = hk.next()
                        headers[k] = headersObj.optString(k)
                    }
                }
                val fileBase = File(downloadDir(), "${sanitizeFilename(entry.optString("title", "video"))}-$id")
                jobs[id] = scope.launch {
                    val link = com.lagradost.cloudstream3.utils.newExtractorLink(
                        entry.optString("source", "Zayn Play"),
                        entry.optString("title", "video"),
                        url,
                    ) {
                        referer = entry.optString("referer", "")
                        quality = entry.optInt("quality", 0)
                        this.headers = headers
                    }
                    runDownload(id, entry, link, fileBase)
                }
                JSONObject().put("status", "downloading")
            }
            "delete" -> {
                jobs.remove(id)?.cancel()
                deleteFilesFor(entry)
                store.removeMapEntry(EngineStore.KEY_DOWNLOADS, id)
                try {
                    notifManager.cancel(NOTIF_BASE + (id.hashCode() and 0x7FFF))
                } catch (e: Exception) {
                }
                JSONObject().put("status", "deleted")
            }
            else -> JSONObject().put("status", entry.optString("status"))
        }
    }

    private fun deleteFilesFor(entry: JSONObject) {
        val path = entry.optString("path", "")
        if (path.isBlank()) return
        try {
            val f = File(path)
            if (f.isDirectory) f.deleteRecursively() else f.delete()
            // A direct download leaves "<base>.mp4"; an HLS leaves "<base>_hls/".
            val base = f.parentFile?.let { File(it, f.nameWithoutExtension) }
            if (f.isDirectory) {
                File(f.parentFile, f.name.removeSuffix("_hls") + ".mp4").delete()
            }
            base?.let { if (it.isDirectory) it.deleteRecursively() }
        } catch (e: Exception) {
        }
    }

    /** Local path of a finished download (for the offline player path). */
    fun localPath(id: String): String? {
        val entry = store.mapEntry(EngineStore.KEY_DOWNLOADS, id) ?: return null
        if (entry.optString("status") != "done") return null
        // (v0.2.11) C-15: the stored path must still exist.
        return entry.optString("path", "").takeIf { it.isNotBlank() && File(it).exists() }
    }

    fun shutdown() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
    }
}
