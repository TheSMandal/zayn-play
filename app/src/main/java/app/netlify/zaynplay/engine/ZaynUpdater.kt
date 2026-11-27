package app.netlify.zaynplay.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.lagradost.cloudstream3.app
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase C updater (engine-design.md §1.11/§5.10, E29–E30).
 *
 * Re-pointed from recloudstream/cloudstream to TheSMandal/zayn-play.
 * Rules:
 * - versionCode FIRST (int): the asset name may carry a "-vc<N>"
 *   suffix (e.g. ZaynPlay-0.2.1-vc10.apk); when present it is compared
 *   as an int against the installed versionCode. The x.y.z regex from
 *   the asset name is the fallback (the 1.0.10 bug class: comparing the
 *   asset NAME, not the tag — the regex is only used on the asset name,
 *   and prereleases are filtered out).
 * - NO debug short-circuit: this build IS a debug APK from CI, so the
 *   upstream "no updates on debug" guard would make "Check for update"
 *   always report up-to-date. The manual check always runs.
 * - No prerelease flavour: no second package, no pre-release check.
 * - Temp file is ZaynPlay.apk (never CloudStream.apk); install goes
 *   through FileProvider app.netlify.zaynplay.provider + the system
 *   installer. No CloudStream name in filename/notification/dialog.
 * - No GitHub Release exists for zayn-play yet, so the check currently
 *   finds no releases and reports up-to-date — honestly, not as a bug.
 */
class ZaynUpdater(private val context: Context) {

    companion object {
        const val GITHUB_USER = "TheSMandal"
        const val GITHUB_REPO = "zayn-play"
        private val VERSION_REGEX = Regex("""((\d+)\.(\d+)\.(\d+))(?:-vc\d+)?\.apk""")
        private val VC_REGEX = Regex("""-vc(\d+)\.apk$""")
    }

    private fun installedVersion(): Pair<Long, String> {
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return code to (info.versionName ?: "0.0.0")
    }

    private fun versionTuple(v: String): Triple<Int, Int, Int> {
        val parts = v.split(".").map { it.toIntOrNull() ?: 0 }
        return Triple(
            parts.getOrElse(0) { 0 },
            parts.getOrElse(1) { 0 },
            parts.getOrElse(2) { 0 },
        )
    }

    /** Check github.com/TheSMandal/zayn-play/releases for a newer APK. */
    suspend fun checkUpdate(): JSONObject {
        val (installedCode, installedName) = installedVersion()
        val releasesText = try {
            app.get(
                url = "https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases",
                headers = mapOf("Accept" to "application/vnd.github.v3+json"),
            ).text
        } catch (e: Exception) {
            EngineLogger.log("updater", "releases fetch failed")
            return JSONObject()
                .put("updateAvailable", false)
                .put("error", "network_error")
        }
        val releases = try {
            JSONArray(releasesText)
        } catch (e: Exception) {
            // (v0.2.11) C-14: a GitHub rate-limit returns a JSON object,
            // not an array — report it, never silent "up to date".
            val errCode = if (releasesText.contains("rate limit", ignoreCase = true)) "rate_limited" else "network_error"
            return JSONObject().put("updateAvailable", false).put("error", errCode)
        }
        var best: JSONObject? = null
        var bestCode = -1L
        var bestTuple = Triple(0, 0, 0)
        var bestAsset: JSONObject? = null
        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            if (rel.optBoolean("prerelease", false)) continue
            val assets = rel.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                if (asset.optString("content_type") != "application/vnd.android.package-archive") continue
                val name = asset.optString("name")
                val verMatch = VERSION_REGEX.find(name) ?: continue
                val tuple = versionTuple(verMatch.groupValues[1])
                val vc = VC_REGEX.find(name)?.groupValues?.get(1)?.toLongOrNull()
                val better = when {
                    vc != null && bestCode >= 0 -> vc > bestCode
                    vc != null -> true
                    bestAsset == null -> true
                    tuple.first != bestTuple.first -> tuple.first > bestTuple.first
                    tuple.second != bestTuple.second -> tuple.second > bestTuple.second
                    else -> tuple.third > bestTuple.third
                }
                if (better) {
                    best = rel
                    bestAsset = asset
                    if (vc != null) bestCode = vc
                    bestTuple = tuple
                }
            }
        }
        if (best == null || bestAsset == null) {
            return JSONObject().put("updateAvailable", false)
        }
        val remoteVc = VC_REGEX.find(bestAsset.optString("name"))
            ?.groupValues?.get(1)?.toLongOrNull()
        val remoteName = VERSION_REGEX.find(bestAsset.optString("name"))
            ?.groupValues?.get(1) ?: best.optString("tag_name", "")
        val available = if (remoteVc != null) {
            remoteVc > installedCode
        } else {
            val (a, b, c) = versionTuple(remoteName)
            val (ia, ib, ic) = versionTuple(installedName)
            a > ia || (a == ia && (b > ib || (b == ib && c > ic)))
        }
        val out = JSONObject().put("updateAvailable", available)
        if (available) {
            out.put("version", remoteName)
            if (remoteVc != null) out.put("versionCode", remoteVc)
            out.put("changelog", best.optString("body", ""))
            out.put("url", bestAsset.optString("browser_download_url", ""))
        }
        return out
    }

    /** Download the APK to cacheDir/ZaynPlay.apk and fire the installer. */
    suspend fun downloadUpdate(url: String): JSONObject {
        if (url.isBlank()) return JSONObject().put("started", false)
        return try {
            // Delete stale update files first.
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("ZaynPlay") && it.extension == "apk" }
                ?.forEach { it.delete() }
            val file = File(context.cacheDir, "ZaynPlay.apk")
            val resp = app.get(url)
            val body = resp.body
            if (!resp.isSuccessful || body == null) {
                return JSONObject().put("started", false)
            }
            file.outputStream().use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
            // (v0.2.11) C-14: a truncated/empty download must fail here,
            // not later at the system installer with no message.
            if (!file.exists() || file.length() <= 0) {
                return JSONObject().put("started", false).put("error", "download_empty")
            }
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.provider", file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setDataAndType(uri, "application/vnd.android.package-archive")
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            context.startActivity(intent)
            JSONObject().put("started", true)
        } catch (e: Exception) {
            EngineLogger.log("updater", "download failed")
            JSONObject().put("started", false)
        }
    }
}
