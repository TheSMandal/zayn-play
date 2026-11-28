package app.netlify.zaynplay.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * (v1.0.18 P16-01) Jetpack DataStore Preferences — the P16 settings
 * store. This is the REAL DataStore: the vendored
 * `com.lagradost.cloudstream3.utils.DataStore` it replaces is a
 * SharedPreferences wrapper that only shares the name.
 *
 * Value format: identical to the old store — one string per setting,
 * `{"v": <value>}` (the Phase A wrapper format) — so the migration is
 * a plain copy and `storedOrDefault` in EngineStore parses it the same
 * way. Keys are the same `zayn/settings/<key>` strings.
 *
 * Synchronous API by design: the bridge and the play path read
 * settings synchronously (getSettingString/Boolean/StringList). Reads
 * come from an in-memory cache loaded once at init; writes update the
 * cache first, then the DataStore file via runBlocking edit on the
 * calling thread. DataStore does its file I/O on Dispatchers.IO, so a
 * runBlocking edit from the main thread cannot deadlock on it — the
 * same pattern the DataStore docs use for one-shot reads.
 *
 * Singleton per application: MainActivity.formFactor() reads the
 * `layout` setting before EngineStore exists, so both must share one
 * cache — two instances could disagree after a write.
 */
private val Context.zaynSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "zayn_settings",
)

class ZaynPrefsStore private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: ZaynPrefsStore? = null

        fun get(context: Context): ZaynPrefsStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ZaynPrefsStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val dataStore: DataStore<Preferences> = context.zaynSettingsDataStore

    /** In-memory mirror of every stored wrapper string, keyed by the
        full `zayn/settings/<key>` string. */
    private val cache = ConcurrentHashMap<String, String>()

    init {
        try {
            runBlocking {
                val prefs = dataStore.data.first()
                for (entry in prefs.asMap().entries) {
                    val k = entry.key.name
                    val v = entry.value
                    if (v is String) cache[k] = v
                }
            }
        } catch (e: Exception) {
            EngineLogger.log("store", "prefs: initial load failed: ${e.message}")
        }
    }

    fun getWrapper(fullKey: String): String? = cache[fullKey]

    fun setWrapper(fullKey: String, wrapper: String) {
        cache[fullKey] = wrapper
        try {
            runBlocking {
                dataStore.edit { prefs ->
                    prefs[stringPreferencesKey(fullKey)] = wrapper
                }
            }
        } catch (e: Exception) {
            EngineLogger.log("store", "prefs: write failed for $fullKey: ${e.message}")
        }
    }

    fun removeWrapper(fullKey: String) {
        cache.remove(fullKey)
        try {
            runBlocking {
                dataStore.edit { prefs ->
                    prefs.remove(stringPreferencesKey(fullKey))
                }
            }
        } catch (e: Exception) {
            EngineLogger.log("store", "prefs: remove failed for $fullKey: ${e.message}")
        }
    }

    /** All stored wrappers (for migration verification + snapshots). */
    fun snapshot(): Map<String, String> = HashMap(cache)

    fun contains(fullKey: String): Boolean = cache.containsKey(fullKey)
}
