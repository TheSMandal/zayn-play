package app.netlify.zaynplay.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
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
 * - pause / resume / cancel / retry / delete via a job map + metadata in DataStore,
 * - progress push events throttled to ~2/s,
 * - wifiOnly + dlQuality enforced from settings.
 *
 * Files live in the app's own external Downloads folder
 * (<external-files>/Download/Zayn Play) — writable without any storage
 * permission on Android 14 scoped storage.
 * Notification channel is Zayn Play branded
 * (app.netlify.zaynplay.downloads); no cloudstream3.* id anywhere.
 *
 * (v1.0.16 P10) Unified versioned download contract (CONTRACT_VERSION):
 * every download event and every downloads() item carries the same shape
 * — v, downloadId (+ id alias), mediaId, title, poster, type,
 * providerName, status, progressPct (+ pct alias, -1 when the total is
 * unknown), downloadedBytes, totalBytes (-1 when unknown), downloadSpeed
 * (bytes/s, 0 when not measurable), sizeMB (finished file size, legacy),
 * path, contentUri (FileProvider URI when the file exists), error /
 * errorDetail, updatedAt, addedAt. No percentage is ever invented when
 * the total is unknown (master rule): the list shows MB downloaded only.
 * Concurrency is capped at MAX_CONCURRENT; further starts stay visibly
 * queued and are pumped when a slot frees. Stale downloading/queued
 * entries left by a killed process are reconciled to paused on start.
 */
class ZaynDownloads(
    private val context: Context,
    private val store: EngineStore,
    private val onEvent: (type: String, data: JSONObject) -> Unit,
) {
    companion object {
        const val CHANNEL_ID = "app.netlify.zaynplay.downloads"
        private const val NOTIF_BASE = 3100
        /** (v1.0.16 P10-07) Version of the unified download contract. */
        const val CONTRACT_VERSION = 1
        /** (v1.0.16 P10-08) Max simultaneous downloads; the rest queue. */
        const val MAX_CONCURRENT = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    /** (v1.0.16 P10-15) In-flight OkHttp calls per download, so a pause /
        cancel can interrupt blocking I/O immediately instead of waiting
        for the current chunk/segment to finish. */
    private val activeCalls = ConcurrentHashMap<String, MutableList<Call>>()

    /** (v1.0.16 P10-05) Live byte counters per download, persisted into
        the entry on each throttled progress write. */
    private data class DlProgress(
        var downloadedBytes: Long = 0L,
        var totalBytes: Long = -1L,
        var speedBps: Long = 0L,
        var updatedAt: Long = 0L,
        var lastBytes: Long = 0L,
        var lastMs: Long = 0L,
    )

    private val progressStates = ConcurrentHashMap<String, DlProgress>()

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
        // (v1.0.16 P10-08) A killed process leaves downloading/queued
        // rows with no job behind them; they can never progress, so they
        // become paused (resumable) instead of stuck forever.
        reconcileStale()
    }

    /** (v1.0.16 P10-08) downloading/queued entries with no live job. */
    fun reconcileStale() {
        val map = store.readJsonMap(EngineStore.KEY_DOWNLOADS)
        val keys = map.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val entry = map.optJSONObject(id) ?: continue
            val st = entry.optString("status")
            if ((st == "downloading" || st == "queued") && jobs[id] == null) {
                entry.put("status", "paused")
                entry.put("updatedAt", System.currentTimeMillis())
                store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
            }
        }
    }

    /** (v1.0.16 P10-07) The ONE download item/event shape. `entry` is a
        stored downloads row; the returned object carries every contract
        field plus the V1.0.14 aliases (id/pct/progressPct/error/
        providerName/sizeMB/path) so old readers keep working. */
    fun downloadItem(entry: JSONObject): JSONObject {
        val id = entry.optString("id")
        val path = entry.optString("path", "")
        val prog = progressStates[id]
        val downloadedBytes = prog?.downloadedBytes ?: entry.optLong("downloadedBytes", 0L)
        val totalBytes = prog?.totalBytes ?: entry.optLong("totalBytes", -1L)
        val speedBps = prog?.speedBps ?: entry.optLong("downloadSpeed", 0L)
        val pct = entry.optInt("progressPct", 0)
        // (v1.0.16) episodeUrl is stored as JSONObject.NULL when absent,
        // and optString() would render that as the literal "null" — treat
        // blank/"null" as missing at every step of the fallback chain.
        fun nonNullStr(key: String): String? =
            entry.optString(key, "").takeIf { it.isNotBlank() && it != "null" }
        val mediaId = nonNullStr("mediaId") ?: nonNullStr("episodeUrl") ?: entry.optString("url", "")
        val contentUri = contentUriFor(path)
        return JSONObject()
            .put("v", CONTRACT_VERSION)
            .put("downloadId", id)
            .put("id", id)
            .put("mediaId", mediaId)
            .put("title", entry.optString("title"))
            .put("type", entry.optString("type", "movie"))
            .put("poster", entry.optString("poster", ""))
            .put("status", entry.optString("status"))
            .put("progressPct", pct)
            .put("pct", pct)
            .put("downloadedBytes", downloadedBytes)
            .put("totalBytes", totalBytes)
            .put("downloadSpeed", speedBps)
            .put("error", if (entry.optString("status") == "failed") entry.optString("error", "download_failed") else entry.optString("error", ""))
            .put("errorDetail", entry.optString("errorDetail", ""))
            .put("providerName", entry.optString("providerName", ""))
            .put("sizeMB", entry.optDouble("sizeMB", 0.0))
            .put("path", path)
            .put("contentUri", contentUri ?: JSONObject.NULL)
            .put("updatedAt", entry.optLong("updatedAt", 0L))
            .put("addedAt", entry.optLong("addedAt", 0L))
    }

    /** (v1.0.16 P10-07) Content URI for a downloaded file, via the
        FileProvider already declared in the manifest (authority
        app.netlify.zaynplay.provider); null when there is no file yet. */
    fun contentUriFor(path: String): String? {
        if (path.isBlank()) return null
        val f = File(path)
        if (!f.exists()) return null
        return try {
            FileProvider.getUriForFile(context, "app.netlify.zaynplay.provider", f).toString()
        } catch (e: Exception) {
            null
        }
    }

    fun downloads(): JSONObject {
        val map = store.readJsonMap(EngineStore.KEY_DOWNLOADS)
        val arr = JSONArray()
        val keys = map.keys()
        while (keys.hasNext()) {
            val entry = map.optJSONObject(keys.next()) ?: continue
            arr.put(downloadItem(entry))
        }
        return JSONObject().put("items", arr)
    }

    /** (v1.0.16 P10-06) Real storage figures for the download dir's
        filesystem (StatFs — never a hardcoded pair of numbers). */
    fun storageInfo(): JSONObject {
        val dir = downloadDir()
        return try {
            val stat = StatFs(dir.absolutePath)
            val total = stat.totalBytes
            val free = stat.freeBytes
            JSONObject()
                .put("v", CONTRACT_VERSION)
                .put("totalBytes", total)
                .put("freeBytes", free)
                .put("usedBytes", (total - free).coerceAtLeast(0L))
                .put("path", dir.absolutePath)
        } catch (e: Exception) {
            JSONObject()
                .put("v", CONTRACT_VERSION)
                .put("totalBytes", -1L)
                .put("freeBytes", -1L)
                .put("usedBytes", -1L)
                .put("path", dir.absolutePath)
        }
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
     * When MAX_CONCURRENT downloads are already running the entry stays
     * visibly queued and is pumped when a slot frees (P10-08).
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
        val now = System.currentTimeMillis()
        val entry = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("type", type)
            .put("poster", poster)
            .put("status", "queued")
            .put("progressPct", existing?.optInt("progressPct", 0) ?: 0)
            .put("downloadedBytes", existing?.optLong("downloadedBytes", 0L) ?: 0L)
            .put("totalBytes", existing?.optLong("totalBytes", -1L) ?: -1L)
            .put("downloadSpeed", 0L)
            .put("sizeMB", existing?.optDouble("sizeMB", 0.0) ?: 0.0)
            .put("path", existing?.optString("path", "") ?: "")
            .put("url", link.url)
            .put("source", link.source)
            .put("providerName", providerName)
            .put("mediaId", episodeUrl ?: link.url)
            .put("episodeUrl", episodeUrl ?: JSONObject.NULL)
            .put("quality", link.quality)
            .put("isM3u8", link.isM3u8)
            .put("isDash", link.isDash)
            .put("linkType", link.type.name)
            .put("referer", link.referer)
            .put("headers", JSONObject(link.headers))
            .put("addedAt", existing?.optLong("addedAt", now) ?: now)
            .put("updatedAt", now)
        store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
        progressStates[id] = DlProgress(
            downloadedBytes = entry.optLong("downloadedBytes", 0L),
            totalBytes = entry.optLong("totalBytes", -1L),
            updatedAt = now,
            lastBytes = entry.optLong("downloadedBytes", 0L),
            lastMs = now,
        )
        maybeLaunch(id)
        return id
    }

    /** (v1.0.16 P10-08) Launch a queued entry when a slot is free;
        otherwise it stays queued (visible, not lost). */
    private fun maybeLaunch(id: String) {
        if (jobs.containsKey(id)) return
        if (jobs.size >= MAX_CONCURRENT) return
        launchFromEntry(id)
    }

    /** (v1.0.16 P10-08) Pump queued entries (oldest first) into free slots. */
    private fun pumpQueue() {
        if (jobs.size >= MAX_CONCURRENT) return
        val map = store.readJsonMap(EngineStore.KEY_DOWNLOADS)
        val queued = mutableListOf<Pair<Long, String>>()
        val keys = map.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val entry = map.optJSONObject(id) ?: continue
            if (entry.optString("status") == "queued" && jobs[id] == null) {
                queued += entry.optLong("addedAt", 0L) to id
            }
        }
        queued.sortedBy { it.first }.forEach { (_, id) ->
            if (jobs.size < MAX_CONCURRENT) launchFromEntry(id)
        }
    }

    /**
     * (v1.0.16 P10-11) Rebuild a download's link from its stored metadata
     * and run it. The stored link TYPE is restored (never re-inferred from
     * the URL suffix — extensionless HLS/DASH URLs would resume down the
     * wrong path as a plain .mp4). Used by resume, retry and the queue pump.
     */
    private fun launchFromEntry(id: String) {
        val entry = store.mapEntry(EngineStore.KEY_DOWNLOADS, id) ?: return
        val url = entry.optString("url")
        if (url.isBlank()) return
        val headersObj = entry.optJSONObject("headers")
        val headers = mutableMapOf<String, String>()
        if (headersObj != null) {
            val hk = headersObj.keys()
            while (hk.hasNext()) {
                val k = hk.next()
                headers[k] = headersObj.optString(k)
            }
        }
        val storedType = try {
            ExtractorLinkType.valueOf(entry.optString("linkType", ""))
        } catch (e: Exception) {
            null
        }
        val fileBase = File(downloadDir(), "${sanitizeFilename(entry.optString("title", "video"))}-$id")
        entry.put("status", "queued")
        entry.put("updatedAt", System.currentTimeMillis())
        store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
        jobs[id] = scope.launch {
            val link = com.lagradost.cloudstream3.utils.newExtractorLink(
                entry.optString("source", "Zayn Play"),
                entry.optString("title", "video"),
                url,
                storedType,
            ) {
                referer = entry.optString("referer", "")
                quality = entry.optInt("quality", 0)
                this.headers = headers
            }
            runDownload(id, entry, link, fileBase)
        }
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
            // Re-read: the entry may have been re-written while we ran.
            val cur = store.mapEntry(EngineStore.KEY_DOWNLOADS, id) ?: entry
            val prog = progressStates[id]
            cur.put("status", "done")
                .put("progressPct", 100)
                .put("downloadedBytes", prog?.downloadedBytes ?: (sizeMB * 1048576.0).toLong())
                .put("totalBytes", prog?.let { if (it.totalBytes > 0) it.totalBytes else (sizeMB * 1048576.0).toLong() } ?: (sizeMB * 1048576.0).toLong())
                .put("downloadSpeed", 0L)
                .put("sizeMB", sizeMB)
                .put("path", path)
                .put("updatedAt", System.currentTimeMillis())
            store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, cur)
            notify(id, cur.optString("title"), "Download complete", 100, done = true)
            onEvent("downloadDone", downloadItem(cur))
            EngineLogger.log("download", "done $id $sizeMB MB")
        } catch (e: kotlinx.coroutines.CancellationException) {
            markPaused(id, entry)
            throw e
        } catch (e: Exception) {
            // (v1.0.16 P10-15) A pause/cancel interrupts blocking OkHttp I/O
            // with an IOException, not a CancellationException — but the
            // coroutine itself is cancelled, so this is a pause, not a
            // failure. Never report a user pause as a failed download.
            if (!currentCoroutineContext().isActive) {
                markPaused(id, entry)
                throw kotlinx.coroutines.CancellationException("paused")
            }
            // (v1.0.16 P10-03) A delete that already removed the row must
            // not resurrect it as "failed".
            val cur = store.mapEntry(EngineStore.KEY_DOWNLOADS, id)
            if (cur == null) throw e
            val (friendly, raw) = friendlyError(e)
            cur.put("status", "failed").put("error", friendly).put("errorDetail", raw)
            cur.put("downloadSpeed", 0L)
            cur.put("updatedAt", System.currentTimeMillis())
            // Persist byte counters so a retry resumes from the partial file.
            progressStates[id]?.let {
                cur.put("downloadedBytes", it.downloadedBytes)
                cur.put("totalBytes", it.totalBytes)
            }
            store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, cur)
            notify(id, cur.optString("title"), "Download failed", cur.optInt("progressPct", 0))
            onEvent("downloadFailed", downloadItem(cur).put("code", "download_failed"))
            EngineLogger.log("download", "failed $id $raw")
        } finally {
            jobs.remove(id)
            activeCalls.remove(id)
            pumpQueue()
        }
    }

    /** (v1.0.16 P10-15) Shared pause bookkeeping: keep the partial file +
        metadata, never resurrect a deleted row. */
    private fun markPaused(id: String, entry: JSONObject) {
        val cur = store.mapEntry(EngineStore.KEY_DOWNLOADS, id) ?: return
        progressStates[id]?.let {
            cur.put("downloadedBytes", it.downloadedBytes)
            cur.put("totalBytes", it.totalBytes)
            cur.put("downloadSpeed", 0L)
        }
        cur.put("status", "paused")
        cur.put("updatedAt", System.currentTimeMillis())
        store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, cur)
        notify(id, cur.optString("title"), "Download paused", cur.optInt("progressPct", 0))
        pushProgress(id, "paused")
    }

    /**
     * (v1.0.16 P10-16) Friendly on-screen message for a download failure;
     * the raw exception text is the second value (kept in the log and in
     * errorDetail, never shown as the headline). Never invent a cause:
     * anything unrecognised stays generic.
     */
    fun friendlyError(e: Exception): Pair<String, String> {
        val raw = "${e.javaClass.simpleName}: ${e.message}"
        val msg = e.message ?: ""
        val friendly = when {
            msg.contains("HTTP 403") -> "This server blocked the download (403)"
            msg.contains("HTTP 404") -> "File not found on the server (404)"
            msg.contains("HTTP 429") -> "The server is rate-limiting downloads — try again later"
            msg.contains("HTTP 5") -> "The server failed the download — try again later"
            msg.contains("No space left") || msg.contains("ENOSPC") -> "Not enough storage space"
            e is java.net.SocketTimeoutException -> "Download timed out — check the connection"
            e is java.net.UnknownHostException || e is java.net.ConnectException -> "Couldn't download — check the connection"
            e is java.io.IOException && msg.contains("Canceled") -> "Download stopped"
            else -> "Couldn't download — try again"
        }
        return friendly to raw
    }

    private fun setStatus(id: String, status: String, pct: Int) {
        val entry = store.mapEntry(EngineStore.KEY_DOWNLOADS, id) ?: return
        entry.put("status", status).put("progressPct", pct)
        progressStates[id]?.let {
            entry.put("downloadedBytes", it.downloadedBytes)
            entry.put("totalBytes", it.totalBytes)
            entry.put("downloadSpeed", it.speedBps)
            entry.put("updatedAt", System.currentTimeMillis())
        }
        store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
        pushProgress(id, status)
    }

    /** (v1.0.16 P10-05) Speed = bytes written since the last report /
        elapsed wall time. Never derived from the % bar. */
    private fun noteBytes(id: String, downloadedBytes: Long, totalBytes: Long) {
        val now = System.currentTimeMillis()
        val st = progressStates.getOrPut(id) { DlProgress() }
        if (st.lastMs > 0 && now > st.lastMs) {
            val dtSec = (now - st.lastMs) / 1000.0
            if (dtSec >= 0.25) {
                val delta = (downloadedBytes - st.lastBytes).coerceAtLeast(0L)
                st.speedBps = (delta / dtSec).toLong()
                st.lastBytes = downloadedBytes
                st.lastMs = now
            }
        } else {
            st.lastBytes = downloadedBytes
            st.lastMs = now
        }
        st.downloadedBytes = downloadedBytes
        st.totalBytes = totalBytes
        st.updatedAt = now
    }

    private var lastPushMs = 0L
    private fun pushProgress(id: String, status: String) {
        val now = System.currentTimeMillis()
        if (status == "downloading" && now - lastPushMs < 500) return
        lastPushMs = now
        val entry = store.mapEntry(EngineStore.KEY_DOWNLOADS, id) ?: return
        onEvent("downloadProgress", downloadItem(entry))
    }

    private fun notify(id: String, title: String, text: String, pct: Int, done: Boolean = false) {
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Zayn Play — $title")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(!done)
                .setOnlyAlertOnce(true)
            if (!done) builder.setProgress(100, pct.coerceIn(0, 100), pct < 0)
            notifManager.notify(NOTIF_BASE + (id.hashCode() and 0x7FFF), builder.build())
        } catch (e: Exception) {
            // Notifications are best-effort (e.g. POST_NOTIFICATIONS denied).
        }
    }

    private fun trackCall(id: String, call: Call) {
        activeCalls.getOrPut(id) { mutableListOf() }.add(call)
    }

    private fun cancelCalls(id: String) {
        activeCalls.remove(id)?.forEach { call ->
            try {
                call.cancel()
            } catch (e: Exception) {
            }
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
        val call = app.baseClient.newCall(request)
        trackCall(id, call)
        val response = call.execute()
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
        noteBytes(id, written, total)
        RandomAccessFile(outFile, "rw").use { raf ->
            if (effectiveDownloaded == 0L) raf.setLength(0)
            raf.seek(effectiveDownloaded)
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 500) {
                        lastReport = now
                        noteBytes(id, written, total)
                        // (v1.0.16 P10-05) No % when the total is unknown
                        // (pct -1): show MB downloaded only, never invent.
                        val pct = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else -1
                        setStatus(id, "downloading", pct)
                        notify(id, fileBase.name, "Downloading… " + if (pct >= 0) "$pct%" else "", pct)
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
     * (v1.0.16 P10-14) Progress is BYTE-based, not segment-count: the
     * playlist total is unknowable up front, so pct stays -1 (MB
     * downloaded only) while totalBytes accumulates the real bytes.
     * (v1.0.16 P10-11) Resume skips segments already on disk instead of
     * re-downloading the whole playlist from scratch.
     */
    private suspend fun downloadHls(
        id: String,
        link: ExtractorLink,
        fileBase: File,
    ): Pair<String, Double> = withContext(Dispatchers.IO) {
        val dir = File(fileBase.parentFile, fileBase.name + "_hls").apply { mkdirs() }
        val playlistUrl = link.url
        val playlistText = fetchText(id, playlistUrl, link)
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
                mediaText = fetchText(id, abs, link)
                mediaBase = abs.substringBeforeLast("/") + "/"
            }
        }
        val out = StringBuilder()
        var segIndex = 0
        var totalBytes = 0L
        // Bytes already on disk from an earlier (paused) attempt count
        // toward the total — a resume does not pretend to start at 0.
        var lastReport = System.currentTimeMillis()
        noteBytes(id, 0L, -1L)
        for (raw in mediaText.lines()) {
            ensureActive()
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    if (uri != null) {
                        val keyFile = File(dir, "key.bin")
                        if (!(keyFile.exists() && keyFile.length() > 0)) {
                            downloadToFile(id, resolveUrl(mediaBase, uri), link, keyFile, 0)
                        }
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
                        if (!(mapFile.exists() && mapFile.length() > 0)) {
                            downloadToFile(id, resolveUrl(mediaBase, uri), link, mapFile, 0)
                        }
                        totalBytes += mapFile.length()
                        out.appendLine(line.replace(uri, "init.mp4"))
                    } else {
                        out.appendLine(line)
                    }
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    val segFile = File(dir, "seg-%05d.ts".format(segIndex))
                    // (v1.0.16 P10-11) Segment-level resume: an existing
                    // segment is kept, not re-fetched.
                    if (!(segFile.exists() && segFile.length() > 0)) {
                        downloadToFile(id, resolveUrl(mediaBase, line), link, segFile, 0)
                    }
                    totalBytes += segFile.length()
                    out.appendLine(segFile.name)
                    segIndex++
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 500) {
                        lastReport = now
                        noteBytes(id, totalBytes, -1L)
                        setStatus(id, "downloading", -1)
                    }
                }
                else -> out.appendLine(line)
            }
        }
        noteBytes(id, totalBytes, -1L)
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

    private fun fetchText(id: String, url: String, link: ExtractorLink): String {
        val request = Request.Builder()
            .url(url)
            .apply { link.getAllHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        val call = app.baseClient.newCall(request)
        trackCall(id, call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("empty playlist")
        }
    }

    private fun downloadToFile(id: String, url: String, link: ExtractorLink, out: File, resumeFrom: Long) {
        val request = Request.Builder()
            .url(url)
            .apply {
                link.getAllHeaders().forEach { (k, v) -> header(k, v) }
                if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-")
            }
            .build()
        val call = app.baseClient.newCall(request)
        trackCall(id, call)
        call.execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty body")
            FileOutputStream(out, resumeFrom > 0).use { fos ->
                body.byteStream().use { input -> input.copyTo(fos) }
            }
        }
    }

    /**
     * Pause (cancel, keep partial), resume (re-start from metadata),
     * retry (failed -> restart), cancel (stop + remove entry + partial
     * file), delete. Unknown actions return the current status unchanged.
     */
    fun control(id: String, action: String): JSONObject {
        val entry = store.mapEntry(EngineStore.KEY_DOWNLOADS, id)
            ?: return JSONObject().put("status", JSONObject.NULL)
        return when (action) {
            "pause" -> {
                // (v1.0.16 P10-15) Cancel the in-flight HTTP call too:
                // cancelling the coroutine alone cannot interrupt a
                // blocking OkHttp read.
                cancelCalls(id)
                jobs.remove(id)?.cancel()
                progressStates[id]?.let { it.speedBps = 0L }
                entry.put("status", "paused")
                entry.put("downloadSpeed", 0L)
                entry.put("updatedAt", System.currentTimeMillis())
                store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
                pushProgress(id, "paused")
                JSONObject().put("status", "paused")
            }
            "resume", "retry" -> {
                if (entry.optString("status") == "done") return JSONObject().put("status", "done")
                // (v1.0.16 §3.1) In-flight guard: resuming a download that
                // is already running must NOT launch a second job for the
                // same id (the old code overwrote jobs[id], orphaning the
                // first job, which still wrote the store on completion).
                if (jobs.containsKey(id)) return JSONObject().put("status", entry.optString("status"))
                if (action == "retry") {
                    entry.put("error", "")
                    entry.put("errorDetail", "")
                }
                entry.put("status", "queued")
                entry.put("updatedAt", System.currentTimeMillis())
                store.putMapEntry(EngineStore.KEY_DOWNLOADS, id, entry)
                maybeLaunch(id)
                JSONObject().put("status", "queued")
            }
            "cancel" -> {
                cancelCalls(id)
                jobs.remove(id)?.cancel()
                progressStates.remove(id)
                deleteFilesFor(entry)
                store.removeMapEntry(EngineStore.KEY_DOWNLOADS, id)
                try {
                    notifManager.cancel(NOTIF_BASE + (id.hashCode() and 0x7FFF))
                } catch (e: Exception) {
                }
                JSONObject().put("status", "cancelled")
            }
            "delete" -> {
                cancelCalls(id)
                jobs.remove(id)?.cancel()
                progressStates.remove(id)
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

    /**
     * (v1.0.16 P10-03) Delete the downloaded files for an entry. A direct
     * download is "<base>.mp4"; an HLS download is "<base>_hls/" holding
     * playlist.m3u8 + every segment (the old code deleted only the
     * playlist file and computed a "playlist" base that never exists, so
     * the whole segment directory survived every delete).
     */
    private fun deleteFilesFor(entry: JSONObject) {
        val path = entry.optString("path", "")
        if (path.isBlank()) return
        try {
            val f = File(path)
            if (f.isDirectory) {
                f.deleteRecursively()
                return
            }
            // HLS: path is "<base>_hls/playlist.m3u8" — the whole
            // directory (segments, key, init map) goes with it.
            if (f.name == "playlist.m3u8" && f.parentFile?.name?.endsWith("_hls") == true) {
                f.parentFile?.deleteRecursively()
                return
            }
            f.delete()
            // Direct download: also drop a sibling "<base>_hls" dir and
            // "<base>.mp4" if either exists (mixed/legacy rows).
            val parent = f.parentFile
            if (parent != null) {
                val base = f.nameWithoutExtension
                File(parent, base + "_hls").takeIf { it.isDirectory }?.deleteRecursively()
                File(parent, base + ".mp4").takeIf { it != f }?.delete()
            }
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

    /** (v1.0.16 P10-01) The stored row for an id (title etc. for Open). */
    fun downloadEntry(id: String): JSONObject? {
        return store.mapEntry(EngineStore.KEY_DOWNLOADS, id)
    }

    fun shutdown() {
        activeCalls.keys.toList().forEach { cancelCalls(it) }
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
    }
}
