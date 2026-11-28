package app.netlify.zaynplay.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * (v1.0.18 P17-16) Scheduled auto-backup.
 *
 * `backupFreq` (never/daily/weekly/monthly) used to be a dead picker:
 * no scheduler existed at all. The worker runs the same export +
 * folder write as the manual `backupSave` bridge (ZaynBackup), so a
 * scheduled backup is byte-identical to a manual one. `never` cancels
 * the work. The schedule is (re)applied whenever the frequency or the
 * folder changes (ZaynEngine.settingsSet) and once per app launch
 * (MainActivity, KEEP policy — no duplicate work).
 *
 * The worker needs no network and no charging constraint: a backup is
 * a local JSON write. It does not wake the user and does not touch
 * account tokens (the export excludes them, same as manual backup).
 */
class ZaynBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val store = EngineStore(applicationContext)
            val folder = store.getSettingString("backupFolder", "/Zayn Play/Backup")
            val json = ZaynBackup.exportJson(applicationContext, store)
            val file = ZaynBackup.writeToFolder(applicationContext, folder, json)
            store.setSetting("lastBackup", file.name)
            EngineLogger.log("backup", "auto-backup wrote ${file.absolutePath} (${file.length()} bytes)")
            Result.success()
        } catch (e: Exception) {
            EngineLogger.log("backup", "auto-backup failed: ${e.message}")
            Result.retry()
        }
    }
}

object ZaynBackupScheduler {
    const val UNIQUE_NAME = "zayn-auto-backup"

    fun schedule(context: Context) {
        try {
            val store = EngineStore(context)
            val freq = store.getSettingString("backupFreq", "weekly")
            val wm = WorkManager.getInstance(context.applicationContext)
            if (freq == "never") {
                wm.cancelUniqueWork(UNIQUE_NAME)
                EngineLogger.log("backup", "auto-backup cancelled (freq=never)")
                return
            }
            val days = when (freq) {
                "daily" -> 1L
                "monthly" -> 30L
                else -> 7L // weekly (and any unknown value falls back here)
            }
            val req = PeriodicWorkRequestBuilder<ZaynBackupWorker>(days, TimeUnit.DAYS)
                .addTag("zayn-backup")
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            EngineLogger.log("backup", "auto-backup scheduled every $days day(s)")
        } catch (e: Exception) {
            EngineLogger.log("backup", "auto-backup schedule failed: ${e.message}")
        }
    }
}
