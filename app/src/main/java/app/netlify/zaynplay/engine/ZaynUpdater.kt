package app.netlify.zaynplay.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.lagradost.cloudstream3.app
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase C updater (engine-design.md §1.11/§5.10, E29–E30).
 *
 * Re-pointed from recloudstream/cloudstream to TheSMandal/zayn-play.
 *
 * (v1.0.20 P20) Rewritten on release metadata — the v1.0.4–v1.0.19
 * updater was blind to every release since v1.0.12:
 * - version comes from the release `tag_name` (e.g. `v1.0.20`), NEVER
 *   from the asset filename (P20-B1). The asset may be a ZIP
 *   (`application/zip`) or a legacy APK; the versionCode comes from a
 *   `versionCode: N` line in the release body, falling back to the
 *   project convention major*1000 + minor*100 + patch (1.0.20 -> 1020).
 * - ZIP assets are downloaded to cacheDir/ZaynPlay.zip, verified, and
 *   extracted — the single APK inside is what gets installed. The
 *   installer is NEVER fired on ZIP bytes (P20-B2).
 * - (v1.0.25 F) APK assets are picked ABI-aware first
 *   (Build.SUPPORTED_ABIS order, universal fallback; per-ABI
 *   `SHA-256 <abi>:` body lines); the ZIP pick stays as the
 *   legacy fallback for pre-1.0.25 releases.
 * - Pre-install verification (P20-B3), all five, on the downloaded
 *   file AND the extracted APK: SHA-256 vs the asset `digest` field
 *   (ZIP) / the `APK SHA-256:` body line (APK), signing-cert
 *   fingerprint vs the INSTALLED app's own cert (PackageManager
 *   GET_SIGNING_CERTIFICATES — this guarantees update-install
 *   compatibility without hardcoding any fingerprint), package name,
 *   versionCode (== advertised, and > installed for a fresh update),
 *   size vs the asset `size` / Content-Length. Any failure deletes
 *   the file and returns a NAMED error (`verification_failed:<check>`)
 *   — never "up to date", never a fallback to an older release (B4).
 * - The cached APK is bound to the stored update record (B10):
 *   installDownloadedApk verifies it against the record's
 *   versionCode + APK hash before firing; a mismatch is `stale_apk`
 *   and the file is deleted. A verified cached APK is NOT deleted
 *   to be re-downloaded (B9).
 * - Truncated downloads are rejected (B11): written bytes must equal
 *   Content-Length when known, and the file size must equal the
 *   asset size.
 * - When an update is found, a real system notification is posted
 *   once per version (F7 remainder), reusing the NotificationCompat
 *   pattern from ZaynDownloads; POST_NOTIFICATIONS is already
 *   declared in the manifest and posting is best-effort.
 *
 * Rules kept from before:
 * - NO debug short-circuit: this build IS a release APK from CI, and
 *   the manual check always runs.
 * - No prerelease flavour: no second package, no pre-release check.
 * - Temp files are ZaynPlay.zip / ZaynPlay.apk (never CloudStream.apk);
 *   install goes through FileProvider app.netlify.zaynplay.provider +
 *   the system installer. No CloudStream name in filename/notification/dialog.
 */
class ZaynUpdater(
    private val context: Context,
    // (v1.0.4) F3: progress callback (downloadedBytes, totalBytes; total -1 when unknown).
    private val onProgress: (Long, Long) -> Unit = { _, _ -> },
) {

    companion object {
        const val GITHUB_USER = "TheSMandal"
        const val GITHUB_REPO = "zayn-play"
        const val EXPECTED_PACKAGE = "app.netlify.zaynplay"
        private val TAG_REGEX = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")
        private val VC_BODY_REGEX = Regex("""versionCode:\s*(\d+)""")
        private val APK_SHA_BODY_REGEX = Regex("""APK SHA-256:\s*([0-9a-fA-F]{64})""")
        // (v1.0.25 F) Per-ABI SHA-256 lines in the release body,
        // e.g. `SHA-256 arm64-v8a: <sha>`. The universal APK
        // keeps the legacy `APK SHA-256:` line above. The asset
        // `digest` field is the fallback for APK assets only
        // (for a ZIP asset the digest is the ZIP file's hash
        // and must never stand in for the extracted APK's).
        private val PER_ABI_SHA_BODY_REGEX =
            Regex("""SHA-256\s+(arm64-v8a|armeabi-v7a|x86_64|x86):\s*([0-9a-fA-F]{64})""")
        // (v1.0.25 F) Release asset names (renamed by the
        // assembly coordinator from the CI split outputs):
        // ZaynPlay-1.0.25-<abi>.apk / ZaynPlay-1.0.25-universal.apk.
        // x86_64 precedes x86 so the shorter token can never
        // match the longer name.
        private val SUPPORTED_ABI_TOKENS =
            listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        private const val UPDATE_CHANNEL_ID = "app.netlify.zaynplay.updates"
        private const val UPDATE_NOTIF_ID = 3200
        private const val MAX_ZIP_ENTRIES = 50
        private const val MAX_EXTRACTED_BYTES = 250L * 1024 * 1024
    }

    /** Installed versionCode for the JS banner gate (v1.0.24 L ITEM 4):
     * engineStatus() exposes it so renderUpdBanner can hide a stored
     * record whose versionCode is already installed. Read fresh from
     * PackageManager on every call — never cached. Verification /
     * checkUpdate untouched. */
    fun installedVersionCode(): Long = installedVersion().first

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

    /** Project versionCode convention: 1.0.20 -> 1020. Fallback only;
     * the release body line `versionCode: N` wins when present. */
    private fun derivedVersionCode(tuple: Triple<Int, Int, Int>): Long =
        (tuple.first * 1000 + tuple.second * 100 + tuple.third).toLong()

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ApkInfo(
        val packageName: String,
        val versionCode: Long,
        val certSha256: String?,
    )

    private fun certSha256Of(signingInfo: SigningInfo?): String? {
        if (signingInfo == null) return null
        val signers = signingInfo.apkContentsSigners ?: return null
        if (signers.isEmpty()) return null
        return sha256Hex(signers[0].toByteArray())
    }

    /** Read package name + versionCode + signing-cert fingerprint of a
     * downloaded APK file. Null when the file is not a parseable APK. */
    private fun readApkInfo(file: File): ApkInfo? {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(
                        file.path,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
                } ?: return null
                ApkInfo(info.packageName ?: "", info.longVersionCode, certSha256Of(info.signingInfo))
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES) ?: return null
                val sigs = info.signatures
                val cert = if (sigs != null && sigs.isNotEmpty()) sha256Hex(sigs[0].toByteArray()) else null
                @Suppress("DEPRECATION")
                ApkInfo(info.packageName ?: "", info.versionCode.toLong(), cert)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** SHA-256 of the INSTALLED app's own signing cert. The extracted
     * APK's cert must equal this — same cert means Android accepts the
     * update-install; a repack (different cert) is rejected. */
    private fun ownCertSha256(): String? {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                }
                certSha256Of(info.signingInfo)
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                val sigs = info.signatures
                if (sigs != null && sigs.isNotEmpty()) sha256Hex(sigs[0].toByteArray()) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Verify an APK file. Returns null on pass, else a named error
     * (`verification_failed:<check>`). Checks that need an expected
     * value are skipped when it is absent (<= 0 / null); package,
     * cert and the no-downgrade rule always apply.
     * [requireNewer] is true for a fresh update download (the APK must
     * be newer than installed) and false for an explicit reinstall of
     * an already-downloaded update (same versionCode allowed).
     */
    private fun verifyApk(
        file: File,
        expectedSize: Long = -1,
        expectedSha256: String? = null,
        expectedVersionCode: Long = -1,
        requireNewer: Boolean = true,
    ): String? {
        if (!file.exists() || file.length() <= 0) return "verification_failed:missing"
        if (expectedSize > 0 && file.length() != expectedSize) return "verification_failed:size"
        if (expectedSha256 != null && sha256Hex(file) != expectedSha256.lowercase()) {
            return "verification_failed:sha256"
        }
        val info = readApkInfo(file) ?: return "verification_failed:unparseable"
        if (info.packageName != EXPECTED_PACKAGE) return "verification_failed:package"
        if (expectedVersionCode > 0 && info.versionCode != expectedVersionCode) {
            return "verification_failed:version"
        }
        val (installedCode, _) = installedVersion()
        if (requireNewer && info.versionCode <= installedCode) return "verification_failed:version"
        if (!requireNewer && info.versionCode < installedCode) return "verification_failed:version"
        val cert = info.certSha256 ?: return "verification_failed:cert"
        val own = ownCertSha256()
        EngineLogger.log("updater", "apk cert=$cert own=$own")
        if (own == null || cert != own) return "verification_failed:cert"
        return null
    }

    /**
     * (v1.0.25 F) ABI-aware asset selection — pure logic, no
     * Android calls except the [deviceAbis] passed in (normally
     * `Build.SUPPORTED_ABIS`, most-preferred first).
     * 1. Walk the device ABIs IN ORDER; the first ABI whose asset
     *    (`ZaynPlay-1.0.25-<abi>.apk`, matched by the `-<abi>.apk`
     *    name suffix) exists wins.
     * 2. Else the universal APK (`-universal.apk`).
     * 3. Else null — the caller falls through to the unchanged
     *    legacy ZIP / single-APK picks (old ZIP-only releases).
     * ZIP assets never match here (the `.apk` suffix check
     * excludes them); matching is by name suffix, not
     * `content_type`.
     */
    private fun selectApkAsset(assets: JSONArray, deviceAbis: Array<String>): JSONObject? {
        for (abi in deviceAbis) {
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                if (a.optString("name", "").endsWith("-$abi.apk", ignoreCase = true)) return a
            }
        }
        for (j in 0 until assets.length()) {
            val a = assets.optJSONObject(j) ?: continue
            if (a.optString("name", "").endsWith("-universal.apk", ignoreCase = true)) return a
        }
        return null
    }

    /** (v1.0.25 F) The ABI token of a per-ABI asset name, or null
     * for the universal asset / anything else. */
    private fun abiTokenOfAsset(name: String): String? {
        for (abi in SUPPORTED_ABI_TOKENS) {
            if (name.endsWith("-$abi.apk", ignoreCase = true)) return abi
        }
        return null
    }

    /** (v1.0.25 F) Per-ABI SHA-256 lines from a release body
     * (`SHA-256 <abi>: <sha>`), as abi -> lowercase sha. */
    private fun parsePerAbiSha256(body: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (m in PER_ABI_SHA_BODY_REGEX.findAll(body)) {
            out[m.groupValues[1]] = m.groupValues[2].lowercase()
        }
        return out
    }

    /** (v1.0.20 P20-B1/B4) Check github.com/TheSMandal/zayn-play/releases
     * for a newer release. Version from `tag_name`, never the asset
     * filename; the newest non-prerelease release is the ONLY candidate
     * — no fallback to an older release. The result ALWAYS carries
     * `latestVersion`/`latestVersionCode` when a newest release was
     * seen, so the UI can display it instead of a bare false; failures
     * carry a named `error` and are never reported as "up to date". */
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
        // Newest non-prerelease release by tag tuple.
        var newest: JSONObject? = null
        var newestTuple = Triple(0, 0, 0)
        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            if (rel.optBoolean("prerelease", false)) continue
            val tag = rel.optString("tag_name", "")
            val m = TAG_REGEX.matchEntire(tag.trim()) ?: continue
            val tuple = Triple(
                m.groupValues[1].toIntOrNull() ?: 0,
                m.groupValues[2].toIntOrNull() ?: 0,
                m.groupValues[3].toIntOrNull() ?: 0,
            )
            val newer = tuple.first > newestTuple.first ||
                (tuple.first == newestTuple.first && tuple.second > newestTuple.second) ||
                (tuple.first == newestTuple.first && tuple.second == newestTuple.second && tuple.third > newestTuple.third)
            if (newest == null || newer) {
                newest = rel
                newestTuple = tuple
            }
        }
        if (newest == null) {
            return JSONObject().put("updateAvailable", false).put("error", "no_usable_asset")
        }
        val remoteName = "${newestTuple.first}.${newestTuple.second}.${newestTuple.third}"
        val body = newest.optString("body", "")
        val remoteVc = VC_BODY_REGEX.find(body)?.groupValues?.get(1)?.toLongOrNull()
            ?: derivedVersionCode(newestTuple)
        // (v1.0.25 F) apkSha256 is resolved AFTER asset selection
        // below — it depends on which asset was chosen (per-ABI
        // line, legacy line, or asset digest for APK assets).
        // Usable asset: ABI-aware APK pick first (below), else the
        // legacy ZIP container, else a legacy APK. No usable asset
        // on the NEWEST release is an error, not a reason to offer
        // an older release (downgrade vector).
        val assets = newest.optJSONArray("assets")
        var asset: JSONObject? = null
        var isZip = false
        // (v1.0.25 F) ABI-aware pick FIRST: the APK matching this
        // device (Build.SUPPORTED_ABIS order), else the universal
        // APK. When no APK asset matches (old ZIP-only releases)
        // the unchanged ZIP / legacy-APK picks below still run --
        // the earlier structure is not disturbed.
        if (assets != null) {
            asset = selectApkAsset(assets, Build.SUPPORTED_ABIS)
        }
        if (asset == null && assets != null) {
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                if (a.optString("content_type") == "application/zip" &&
                    a.optString("name").endsWith(".zip", ignoreCase = true)
                ) {
                    asset = a
                    isZip = true
                    break
                }
            }
            if (asset == null) {
                for (j in 0 until assets.length()) {
                    val a = assets.optJSONObject(j) ?: continue
                    if (a.optString("content_type") == "application/vnd.android.package-archive" &&
                        a.optString("name").endsWith(".apk", ignoreCase = true)
                    ) {
                        asset = a
                        isZip = false
                        break
                    }
                }
            }
        }
        if (asset == null) {
            return JSONObject()
                .put("updateAvailable", false)
                .put("latestVersion", remoteName)
                .put("latestVersionCode", remoteVc)
                .put("error", "no_usable_asset")
        }
        val digest = asset.optString("digest", "").removePrefix("sha256:")
        val chosenAbi = if (!isZip) abiTokenOfAsset(asset.optString("name", "")) else null
        // (v1.0.25 F) APK hash for the CHOSEN file. Per-ABI asset:
        // its `SHA-256 <abi>:` body line, else the asset `digest`
        // (GitHub's own SHA-256 of the uploaded file -- for an APK
        // asset that IS the file's hash). Universal / legacy APK:
        // the `APK SHA-256:` body line, else the digest. ZIP: the
        // body line ONLY, as before -- the ZIP digest is the ZIP
        // file's hash and must never stand in for the extracted
        // APK's hash.
        val apkSha256 = when {
            isZip -> APK_SHA_BODY_REGEX.find(body)?.groupValues?.get(1)?.lowercase()
            chosenAbi != null -> parsePerAbiSha256(body)[chosenAbi]?.lowercase()
                ?: asset.optString("digest", "").removePrefix("sha256:").lowercase().takeIf { it.isNotBlank() }
            else -> APK_SHA_BODY_REGEX.find(body)?.groupValues?.get(1)?.lowercase()
                ?: asset.optString("digest", "").removePrefix("sha256:").lowercase().takeIf { it.isNotBlank() }
        }
        val available = remoteVc > installedCode
        val out = JSONObject()
            .put("updateAvailable", available)
            .put("latestVersion", remoteName)
            .put("latestVersionCode", remoteVc)
        if (available) {
            out.put("version", remoteName)
            out.put("versionCode", remoteVc)
            out.put("changelog", body)
            out.put("url", asset.optString("browser_download_url", ""))
            out.put("size", asset.optLong("size", -1))
            if (digest.isNotBlank()) out.put("sha256", digest.lowercase())
            if (apkSha256 != null) out.put("apkSha256", apkSha256)
            out.put("isZip", isZip)
            out.put("assetName", asset.optString("name", ""))
            notifyUpdateFound(remoteName)
        }
        return out
    }

    /** (v1.0.20 F7 remainder) Post a real system notification the first
     * time a version is found; once per version, best-effort (no
     * permission = silently skipped, never a crash). */
    private fun notifyUpdateFound(version: String) {
        try {
            val prefs = context.getSharedPreferences("zayn_updater", Context.MODE_PRIVATE)
            if (prefs.getString("notified_version", "") == version) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    UPDATE_CHANNEL_ID,
                    "Zayn Play updates",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "New Zayn Play versions" }
                manager.createNotificationChannel(channel)
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val pending = launch?.let {
                PendingIntent.getActivity(
                    context, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val builder = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
                .setContentTitle("Zayn Play v$version এসেছে")
                .setContentText("নতুন ভার্সন ডাউনলোড করতে অ্যাপটি খুলুন")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
            if (pending != null) builder.setContentIntent(pending)
            manager.notify(UPDATE_NOTIF_ID, builder.build())
            prefs.edit().putString("notified_version", version).apply()
        } catch (e: Exception) {
            // Notifications are best-effort (e.g. POST_NOTIFICATIONS denied).
        }
    }

    /**
     * (v1.0.2) Android 8+ needs the per-app unknown-sources grant before
     * ANY install intent can work — and the V1.0.1 APK did not even declare
     * REQUEST_INSTALL_PACKAGES, so the grant could never be given. Check
     * FIRST (before the ~75 MB download): if the grant is missing, open
     * this app's "Install unknown apps" screen and report
     * install_permission, so the user grants it and taps again instead of
     * downloading for nothing.
     */
    private fun ensureInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            EngineLogger.log("updater", "unknown-sources grant missing — opening Settings")
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return false
        }
        return true
    }

    private fun fireInstaller(file: File): JSONObject {
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
        return JSONObject().put("started", true)
    }

    /** Extract the single APK from a ZIP. Returns null on success, else
     * a named error. Rejects 0 or >1 APK entries, path-traversal names,
     * >50 entries, and >250 MB extracted total (zip-slip / zip-bomb). */
    private fun extractSingleApk(zipFile: File, outFile: File): String? {
        var apkCount = 0
        var entries = 0
        var totalOut = 0L
        try {
            ZipInputStream(FileInputStream(zipFile)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    entries++
                    if (entries > MAX_ZIP_ENTRIES) return "verification_failed:zip_entries"
                    val name = entry.name
                    if (!entry.isDirectory) {
                        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                            return "verification_failed:zip_entries"
                        }
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkCount++
                            if (apkCount > 1) return "verification_failed:zip_entries"
                            outFile.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zin.read(buf)
                                    if (n < 0) break
                                    totalOut += n
                                    if (totalOut > MAX_EXTRACTED_BYTES) return "verification_failed:zip_entries"
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        } catch (e: Exception) {
            return "verification_failed:zip_entries"
        }
        if (apkCount != 1) return "verification_failed:zip_entries"
        return null
    }

    /**
     * Download the update. ZIP assets go to cacheDir/ZaynPlay.zip,
     * are verified, and the single APK inside is extracted to
     * cacheDir/ZaynPlay.apk; legacy APK assets download straight to
     * the .apk. When [autoInstall] is true (default) the system
     * installer fires at once, as before; when false the file stays
     * for the in-app Install pop-up, which calls [installDownloadedApk].
     * Expected metadata (size/sha256/apkSha256/versionCode) comes from
     * the stored update record; when missing, the newest release is
     * looked up by URL. A verification failure deletes the file and
     * returns a named error — the installer never fires on bad bytes.
     */
    suspend fun downloadUpdate(
        url: String,
        autoInstall: Boolean = true,
        expectedSize: Long = -1,
        expectedSha256: String? = null,
        expectedVersionCode: Long = -1,
        expectedApkSha256: String? = null,
    ): JSONObject {
        if (url.isBlank()) return JSONObject().put("started", false)
        if (!ensureInstallPermission()) {
            return JSONObject().put("started", false).put("error", "install_permission")
        }
        // Fill missing expectations from the newest release (URL match).
        var expSize = expectedSize
        var expSha = expectedSha256
        var expVc = expectedVersionCode
        var expApkSha = expectedApkSha256
        if (expSize < 0 || expSha == null || expVc < 0 || expApkSha == null) {
            try {
                val meta = checkUpdate()
                if (meta.optString("url", "") == url) {
                    if (expSize < 0) expSize = meta.optLong("size", -1)
                    if (expSha == null) expSha = meta.optString("sha256", "").ifBlank { null }
                    if (expVc < 0) expVc = meta.optLong("versionCode", -1)
                    if (expApkSha == null) expApkSha = meta.optString("apkSha256", "").ifBlank { null }
                }
            } catch (e: Exception) {
                // Lookup is best-effort; the JS-supplied record is primary.
            }
        }
        val isZip = url.endsWith(".zip", ignoreCase = true)
        val apkFile = File(context.cacheDir, "ZaynPlay.apk")
        val zipFile = File(context.cacheDir, "ZaynPlay.zip")
        // (v1.0.20 P20-B9) A verified cached APK for this update is
        // reused, not deleted and re-downloaded on a slow link.
        if (expApkSha != null && expVc > 0 && apkFile.exists()) {
            val reuseErr = verifyApk(
                apkFile,
                expectedSize = -1,
                expectedSha256 = expApkSha,
                expectedVersionCode = expVc,
                requireNewer = false,
            )
            if (reuseErr == null) {
                EngineLogger.log("updater", "cached APK verified for v$expVc — reusing, no re-download")
                return JSONObject().put("started", true).put("downloaded", true).put("reused", true)
            }
        }
        // (v1.0.14 P4 rank 8) The target files are declared outside the
        // try so the CancellationException path below can delete the
        // partial download.
        val file = if (isZip) zipFile else apkFile
        return try {
            // Delete stale update files first (a verified APK was already
            // returned above, so anything left here is stale or unverified).
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("ZaynPlay") && (it.extension == "apk" || it.extension == "zip") }
                ?.forEach { it.delete() }
            val resp = app.get(url)
            val body = resp.body
            if (!resp.isSuccessful || body == null) {
                return JSONObject().put("started", false).put("error", "download_failed")
            }
            // (v1.0.4) F3: stream in 64 KB chunks and report progress (throttled to 500 ms, like video downloads) instead of one blocking copyTo with no feedback.
            val total = try { body.contentLength() } catch (e: Exception) { -1L }
            var written = 0L
            var lastReport = 0L
            file.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 500) {
                            lastReport = now
                            try { onProgress(written, total) } catch (e: Exception) { }
                        }
                    }
                }
            }
            try { onProgress(written, total) } catch (e: Exception) { }
            // (v1.0.20 P20-B11) A truncated non-empty download must fail
            // here, not later at the system installer with no message.
            if (!file.exists() || file.length() <= 0) {
                file.delete()
                return JSONObject().put("started", false).put("error", "download_empty")
            }
            if (total >= 0 && written != total) {
                file.delete()
                return JSONObject().put("started", false).put("error", "verification_failed:truncated")
            }
            if (expSize > 0 && file.length() != expSize) {
                file.delete()
                return JSONObject().put("started", false).put("error", "verification_failed:size")
            }
            if (expSha != null && sha256Hex(file) != expSha.lowercase()) {
                file.delete()
                return JSONObject().put("started", false).put("error", "verification_failed:sha256")
            }
            if (isZip) {
                val extractErr = extractSingleApk(zipFile, apkFile)
                if (extractErr != null) {
                    zipFile.delete()
                    apkFile.delete()
                    return JSONObject().put("started", false).put("error", extractErr)
                }
                zipFile.delete()
                val apkErr = verifyApk(
                    apkFile,
                    expectedSize = -1,
                    expectedSha256 = expApkSha,
                    expectedVersionCode = expVc,
                    requireNewer = true,
                )
                if (apkErr != null) {
                    apkFile.delete()
                    return JSONObject().put("started", false).put("error", apkErr)
                }
            } else {
                val apkErr = verifyApk(
                    apkFile,
                    expectedSize = expSize,
                    expectedSha256 = expSha,
                    expectedVersionCode = expVc,
                    requireNewer = true,
                )
                if (apkErr != null) {
                    apkFile.delete()
                    return JSONObject().put("started", false).put("error", apkErr)
                }
            }
            if (!autoInstall) return JSONObject().put("started", true).put("downloaded", true)
            fireInstaller(apkFile)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // (v1.0.14 P4 rank 8) A cancelled download must stay cancelled
            // (the old catch-all swallowed it and replied "not started")
            // and must not leave a partial file behind for
            // installDownloadedApk to fire later.
            file.delete()
            apkFile.delete()
            throw e
        } catch (e: Exception) {
            EngineLogger.log("updater", "download failed")
            JSONObject().put("started", false).put("error", "download_failed")
        }
    }

    /**
     * (v1.0.4) F2: fire the system installer for the already-downloaded
     * update (cacheDir/ZaynPlay.apk). The in-app Install pop-up calls
     * this; "Reinstall" calls it too. (v1.0.20 P20-B10) The cached file
     * is verified against the stored update record (APK hash +
     * versionCode) and the installed cert before firing: a stale file
     * is deleted and reported as `stale_apk` so the UI re-downloads
     * instead of installing the wrong bytes.
     */
    fun installDownloadedApk(
        expectedApkSha256: String? = null,
        expectedVersionCode: Long = -1,
    ): JSONObject {
        if (!ensureInstallPermission()) {
            return JSONObject().put("started", false).put("error", "install_permission")
        }
        val file = File(context.cacheDir, "ZaynPlay.apk")
        if (!file.exists() || file.length() <= 0) {
            return JSONObject().put("started", false).put("error", "no_apk")
        }
        val err = verifyApk(
            file,
            expectedSize = -1,
            expectedSha256 = expectedApkSha256,
            expectedVersionCode = expectedVersionCode,
            requireNewer = false,
        )
        if (err != null) {
            file.delete()
            // A file that no longer matches the advertised update is
            // stale (re-download); a cert/package/unparseable failure
            // is reported verbatim — it must never install.
            val code = if (err == "verification_failed:size" ||
                err == "verification_failed:sha256" ||
                err == "verification_failed:version"
            ) {
                "stale_apk"
            } else {
                err
            }
            return JSONObject().put("started", false).put("error", code)
        }
        return try {
            fireInstaller(file)
        } catch (e: Exception) {
            EngineLogger.log("updater", "install intent failed")
            JSONObject().put("started", false)
        }
    }
}
