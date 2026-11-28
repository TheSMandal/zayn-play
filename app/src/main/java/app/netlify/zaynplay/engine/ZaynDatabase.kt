package app.netlify.zaynplay.engine

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import org.json.JSONObject

/**
 * (v1.0.18 P16-01) Room database — the P16 replacement for the single
 * plain-text SharedPreferences file (`rebuild_preference`) as the store
 * for library / progress / downloads / provider metadata / last-link /
 * custom sites.
 *
 * Design notes (all deliberate, all load-bearing for the V1.0.18 audit):
 * - One entity per domain, keyed exactly like the old JSON maps, so the
 *   migration (ZaynMigration) is a key-for-key copy and the bridge
 *   contract (`library` / `progressAll` / `downloads` /
 *   `providerHealthRecord`) does not move.
 * - DAOs are SYNCHRONOUS (no suspend, no room-ktx). The bridge is
 *   synchronous by contract (WebView JS interface + EngineStore's
 *   getSetting/readJsonMap API), and rewriting every caller to suspend
 *   would touch the play path. `allowMainThreadQueries()` below is the
 *   price: these tables are small maps (a few hundred rows at most) and
 *   every write is a single-row upsert, so a query is sub-millisecond.
 *   The old code did strictly worse — a whole-map JSON parse + rewrite
 *   on every progress save (P16-05).
 * - The old `rebuild_preference` file is LEFT IN PLACE after migration
 *   (coordinator decision 3): it is the backup for one version, and is
 *   only retired by a V1.0.19 decision after on-phone proof.
 * - `updatedAt` (ms) is stored per row: the backup-import merge
 *   (newest wins, P16-03) and the progress cap prune (P16-05) both read
 *   it. Rows migrated from the old maps keep their old `updatedAt`
 *   when the JSON carries one, else the migration time.
 */
@Entity(tableName = "library")
data class LibraryRow(
    @PrimaryKey val id: String,
    val json: String,
    val updatedAt: Long,
)

@Entity(tableName = "progress")
data class ProgressRow(
    @PrimaryKey val id: String,
    val json: String,
    val updatedAt: Long,
)

@Entity(tableName = "downloads")
data class DownloadRow(
    @PrimaryKey val id: String,
    val json: String,
    val updatedAt: Long,
)

/**
 * Provider metadata, one row per "<repoId>|<providerName>" key.
 * `on` / `autoOff` / `health` are the old zayn/provOn, provAutoOff,
 * provHealth values; `healthDetail` is the old zayn/provHealthDetail
 * record object serialised as JSON (or "" when none).
 */
@Entity(tableName = "providers")
data class ProviderRow(
    @PrimaryKey val key: String,
    val on: Boolean,
    val autoOff: Boolean,
    val health: String?,
    val healthDetail: String,
    val updatedAt: Long,
)

@Entity(tableName = "lastlink")
data class LastLinkRow(
    @PrimaryKey val key: String,
    val json: String,
    val updatedAt: Long,
)

/**
 * Cloned / added custom sites (P17-23 fix). One row per
 * "<repoId>|<siteName>"; `json` is the site object
 * {name, url, lang, prov} as the JS side builds it. The old code kept
 * these in JS memory only (ST.customSites) — the audit traced NO native
 * write path (USER_PROVIDER_API is an unused constant), so clones
 * vanished on restart. This table is that write path.
 */
@Entity(tableName = "customsites")
data class CustomSiteRow(
    @PrimaryKey val key: String,
    val json: String,
    val updatedAt: Long,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library")
    fun getAll(): List<LibraryRow>

    @Query("SELECT * FROM library WHERE id = :id")
    fun get(id: String): LibraryRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: LibraryRow)

    @Query("DELETE FROM library WHERE id = :id")
    fun delete(id: String)

    @Query("SELECT COUNT(*) FROM library")
    fun count(): Int
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress")
    fun getAll(): List<ProgressRow>

    @Query("SELECT * FROM progress WHERE id = :id")
    fun get(id: String): ProgressRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: ProgressRow)

    @Query("DELETE FROM progress WHERE id = :id")
    fun delete(id: String)

    @Query("SELECT COUNT(*) FROM progress")
    fun count(): Int

    /** Keep only the N most-recent rows by updatedAt (P16-05 cap). */
    @Query("DELETE FROM progress WHERE id NOT IN (SELECT id FROM progress ORDER BY updatedAt DESC LIMIT :keep)")
    fun pruneToNewest(keep: Int)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads")
    fun getAll(): List<DownloadRow>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun get(id: String): DownloadRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: DownloadRow)

    @Query("DELETE FROM downloads WHERE id = :id")
    fun delete(id: String)

    @Query("SELECT COUNT(*) FROM downloads")
    fun count(): Int
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers")
    fun getAll(): List<ProviderRow>

    @Query("SELECT * FROM providers WHERE key = :key")
    fun get(key: String): ProviderRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: ProviderRow)

    @Query("DELETE FROM providers WHERE key = :key")
    fun delete(key: String)

    @Query("SELECT COUNT(*) FROM providers")
    fun count(): Int

    @Query("DELETE FROM providers WHERE key LIKE :repoPrefix || '%'")
    fun deleteForRepo(repoPrefix: String)
}

@Dao
interface LastLinkDao {
    @Query("SELECT * FROM lastlink")
    fun getAll(): List<LastLinkRow>

    @Query("SELECT * FROM lastlink WHERE key = :key")
    fun get(key: String): LastLinkRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: LastLinkRow)

    @Query("DELETE FROM lastlink WHERE key = :key")
    fun delete(key: String)
}

@Dao
interface CustomSiteDao {
    @Query("SELECT * FROM customsites")
    fun getAll(): List<CustomSiteRow>

    @Query("SELECT * FROM customsites WHERE key = :key")
    fun get(key: String): CustomSiteRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: CustomSiteRow)

    @Query("DELETE FROM customsites WHERE key = :key")
    fun delete(key: String)

    @Query("DELETE FROM customsites")
    fun deleteAll()
}

@Database(
    entities = [
        LibraryRow::class, ProgressRow::class, DownloadRow::class,
        ProviderRow::class, LastLinkRow::class, CustomSiteRow::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ZaynDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun progressDao(): ProgressDao
    abstract fun downloadDao(): DownloadDao
    abstract fun providerDao(): ProviderDao
    abstract fun lastLinkDao(): LastLinkDao
    abstract fun customSiteDao(): CustomSiteDao

    companion object {
        @Volatile
        private var INSTANCE: ZaynDatabase? = null

        fun get(context: Context): ZaynDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ZaynDatabase::class.java,
                    "zayn.db",
                )
                    // See the file header: synchronous DAOs keep the
                    // bridge contract; the tables are small single-row
                    // upserts, never whole-map rewrites.
                    .allowMainThreadQueries()
                    .build().also { INSTANCE = it }
            }
        }
    }
}

/**
 * Map-shaped facade over the Room tables, so EngineStore keeps its
 * readJsonMap / writeJsonMap / putMapEntry / removeMapEntry / mapEntry
 * API (the bridge and the backup code call those) while the backend is
 * Room. Every method is one Room call — no whole-map parse/rewrite.
 */
class ZaynRoomStore(context: Context) {
    private val db = ZaynDatabase.get(context)

    /** The documented progress cap (P16-05): keep the 500 most recent
        resume positions; older ones are pruned on write. 500 is far
        above any real watch history the UI shows (Continue Watching
        renders a handful) and bounds the table forever. */
    companion object {
        const val PROGRESS_CAP = 500
    }

    private fun updatedAtOf(entry: JSONObject): Long {
        val t = entry.optLong("updatedAt", 0)
        return if (t > 0) t else System.currentTimeMillis()
    }

    // ---- generic JSON-object maps (library / progress / downloads) ----

    fun readJsonMap(key: String): JSONObject {
        val out = JSONObject()
        val rows = when (key) {
            EngineStore.KEY_LIBRARY -> db.libraryDao().getAll().map { it.id to it.json }
            EngineStore.KEY_PROGRESS -> db.progressDao().getAll().map { it.id to it.json }
            EngineStore.KEY_DOWNLOADS -> db.downloadDao().getAll().map { it.id to it.json }
            EngineStore.KEY_LAST_LINK -> db.lastLinkDao().getAll().map { it.key to it.json }
            else -> emptyList()
        }
        for ((id, json) in rows) {
            try {
                out.put(id, JSONObject(json))
            } catch (e: Exception) {
                // A corrupt row must not poison the whole map: skip it
                // and log, exactly like the old readMap did.
                EngineLogger.log("store", "room: corrupt row skipped $key/$id")
            }
        }
        return out
    }

    fun writeJsonMap(key: String, map: JSONObject) {
        // Whole-map replace (backup import's legacy path + clearRepoState
        // only). New code prefers putMapEntry; this stays for compat.
        val keys = map.keys()
        val ids = mutableSetOf<String>()
        while (keys.hasNext()) {
            val id = keys.next()
            ids += id
            map.optJSONObject(id)?.let { putMapEntry(key, id, it) }
        }
        // Drop rows the new map no longer contains.
        val existing = when (key) {
            EngineStore.KEY_LIBRARY -> db.libraryDao().getAll().map { it.id }
            EngineStore.KEY_PROGRESS -> db.progressDao().getAll().map { it.id }
            EngineStore.KEY_DOWNLOADS -> db.downloadDao().getAll().map { it.id }
            EngineStore.KEY_LAST_LINK -> db.lastLinkDao().getAll().map { it.key }
            else -> emptyList()
        }
        for (id in existing) {
            if (id !in ids) removeMapEntry(key, id)
        }
    }

    fun mapEntry(key: String, entryId: String): JSONObject? {
        val json = when (key) {
            EngineStore.KEY_LIBRARY -> db.libraryDao().get(entryId)?.json
            EngineStore.KEY_PROGRESS -> db.progressDao().get(entryId)?.json
            EngineStore.KEY_DOWNLOADS -> db.downloadDao().get(entryId)?.json
            EngineStore.KEY_LAST_LINK -> db.lastLinkDao().get(entryId)?.json
            else -> null
        } ?: return null
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            null
        }
    }

    fun putMapEntry(key: String, entryId: String, entry: JSONObject) {
        val now = System.currentTimeMillis()
        // Stamp updatedAt before serialising, so the stored row and the
        // caller's object agree (backup-merge + progress-cap both read it).
        if (entry.optLong("updatedAt", 0) == 0L) {
            try {
                entry.put("updatedAt", now)
            } catch (e: Exception) {
            }
        }
        val at = updatedAtOf(entry)
        when (key) {
            EngineStore.KEY_LIBRARY -> db.libraryDao().upsert(LibraryRow(entryId, entry.toString(), at))
            EngineStore.KEY_PROGRESS -> {
                db.progressDao().upsert(ProgressRow(entryId, entry.toString(), at))
                // Bound the table on every write (P16-05).
                if (db.progressDao().count() > PROGRESS_CAP) {
                    db.progressDao().pruneToNewest(PROGRESS_CAP)
                }
            }
            EngineStore.KEY_DOWNLOADS -> db.downloadDao().upsert(DownloadRow(entryId, entry.toString(), at))
            EngineStore.KEY_LAST_LINK -> db.lastLinkDao().upsert(LastLinkRow(entryId, entry.toString(), at))
            else -> {
                EngineLogger.log("store", "room: putMapEntry unknown key $key")
                return
            }
        }
    }

    fun removeMapEntry(key: String, entryId: String) {
        when (key) {
            EngineStore.KEY_LIBRARY -> db.libraryDao().delete(entryId)
            EngineStore.KEY_PROGRESS -> db.progressDao().delete(entryId)
            EngineStore.KEY_DOWNLOADS -> db.downloadDao().delete(entryId)
            EngineStore.KEY_LAST_LINK -> db.lastLinkDao().delete(entryId)
        }
    }

    fun count(key: String): Int = when (key) {
        EngineStore.KEY_LIBRARY -> db.libraryDao().count()
        EngineStore.KEY_PROGRESS -> db.progressDao().count()
        EngineStore.KEY_DOWNLOADS -> db.downloadDao().count()
        EngineStore.KEY_LAST_LINK -> db.lastLinkDao().getAll().size
        else -> 0
    }

    // ---- provider metadata ----

    fun providerRow(key: String): ProviderRow? = db.providerDao().get(key)

    fun providerRows(): List<ProviderRow> = db.providerDao().getAll()

    fun upsertProvider(row: ProviderRow) = db.providerDao().upsert(row)

    fun deleteProvidersForRepo(repoId: String) = db.providerDao().deleteForRepo("$repoId|")

    fun providerCount(): Int = db.providerDao().count()

    // ---- custom sites (P17-23) ----

    fun customSitesMap(): JSONObject {
        val out = JSONObject()
        for (row in db.customSiteDao().getAll()) {
            try {
                out.put(row.key, JSONObject(row.json))
            } catch (e: Exception) {
                EngineLogger.log("store", "room: corrupt custom site skipped ${row.key}")
            }
        }
        return out
    }

    fun setCustomSites(map: JSONObject) {
        db.customSiteDao().deleteAll()
        val keys = map.keys()
        val now = System.currentTimeMillis()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = map.opt(k) ?: continue
            val json = if (v is JSONObject) v.toString() else JSONObject().put("value", v).toString()
            db.customSiteDao().upsert(CustomSiteRow(k, json, now))
        }
    }

    fun customSiteCount(): Int = db.customSiteDao().getAll().size
}
