package app.netlify.zaynplay.engine

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v1.0.18 P16-03/P16-04) Backup export + the real backup folder.
 * Shared by the manual `backupSave` bridge (ZaynEngine) and the
 * scheduled auto-backup worker (ZaynBackupWorker) so both write the
 * identical format to the identical folder.
 *
 * Format `zaynplay-backup-0.3.0` (was 0.2.0): adds `provHealthDetail`
 * (the 8-field V1.0.17 record — 0.2.0 exported `downloads` and
 * `provHealth` but import ignored them, and never exported the detail
 * at all). Account tokens are deliberately NOT exported — a backup
 * file can be shared, and tokens are secrets (same rule as 0.2.0).
 *
 * Folders: the three `backupFolder` picker values are display paths,
 * not filesystem paths. They map to app-specific external dirs (no
 * storage permission needed on scoped storage; the files survive
 * until uninstall/clear-data, like the downloads themselves). Shared
 * Download/Documents folders would need SAF — out of scope for
 * V1.0.18; the mapping is documented here, not hidden.
 */
object ZaynBackup {
    const val FORMAT = "zaynplay-backup-0.3.0"
    const val FILE_NAME = "zaynplay-backup-0.3.0.json"

    fun exportJson(context: Context, store: EngineStore): JSONObject {
        val settings = store.settingsSnapshot()
        val repos = JSONArray()
        for (repo in com.lagradost.cloudstream3.plugins.RepositoryManager.getRepositories()) {
            repos.put(JSONObject().put("name", repo.name).put("url", repo.url))
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("app", "Zayn Play")
            .put("version", "0.3.0")
            .put("settings", settings)
            .put("repos", repos)
            .put("provOn", store.readJsonMap("zayn/provOn"))
            .put("provAutoOff", store.readJsonMap("zayn/provAutoOff"))
            .put("provHealth", store.readJsonMap("zayn/provHealth"))
            .put("provHealthDetail", store.readJsonMap("zayn/provHealthDetail"))
            .put("library", store.readJsonMap(EngineStore.KEY_LIBRARY))
            .put("progress", store.readJsonMap(EngineStore.KEY_PROGRESS))
            .put("downloads", store.readJsonMap(EngineStore.KEY_DOWNLOADS))
            .put("savedAt", System.currentTimeMillis())
        // NOTE: account tokens are deliberately NOT exported — a backup
        // file can be shared, and tokens are secrets.
    }

    /** The real directory for a `backupFolder` picker value. */
    fun resolveFolder(context: Context, folderSetting: String): File {
        return when (folderSetting) {
            "/Download/Zayn Play Backup" -> File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "Zayn Play Backup",
            )
            "/Documents/Zayn Play" -> File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "Zayn Play",
            )
            else -> File(context.getExternalFilesDir(null), "Backup")
        }
    }

    /**
     * Write the export JSON into the chosen folder. The file name
     * carries the timestamp so daily/weekly auto-backups do not
     * overwrite each other. Returns the written file.
     */
    fun writeToFolder(context: Context, folderSetting: String, json: JSONObject): File {
        val dir = resolveFolder(context, folderSetting)
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "zaynplay-backup-$stamp.json")
        file.writeText(json.toString(2))
        return file
    }

    /** Folder path shown to the user (the real dir, not the picker label). */
    fun folderDisplayPath(context: Context, folderSetting: String): String {
        return try {
            resolveFolder(context, folderSetting).absolutePath
        } catch (e: Exception) {
            folderSetting
        }
    }
}
