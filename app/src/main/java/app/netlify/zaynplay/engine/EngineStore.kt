package app.netlify.zaynplay.engine

import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase B engine store: the bridge facade for engine state.
 *
 * RECONCILED with the vendored DataStore (T10): the Phase A
 * SharedPreferences file ("zayn-engine") is gone as a write target —
 * all state now lives in DataStore's "rebuild_preference" file, under
 * the "zayn/" key prefix. On first use after the upgrade, any values
 * stored by Phase A are copied over once (see migrateFromPhaseA) so a
 * user's settings survive the update.
 *
 * Two kinds of state:
 * - Settings: one key per setting ("zayn/settings/<key>"), each value a
 *   small JSON wrapper {"v": <value>} (Phase A format, kept so the
 *   migration is a plain copy).
 * - Provider state: three JSON-object maps ("zayn/provOn",
 *   "zayn/provAutoOff", "zayn/provHealth"), keyed "<repoId>|<name>".
 *   Providers of a newly installed repo default OFF — the absence of a
 *   key means OFF.
 */
class EngineStore(private val context: Context) {

    companion object {
        /** Phase A prefs file — read once for migration, never written. */
        const val PREFS_NAME = "zayn-engine"

        private const val SETTINGS_PREFIX = "zayn/settings/"
        private const val KEY_PROV_ON = "zayn/provOn"
        private const val KEY_PROV_AUTO_OFF = "zayn/provAutoOff"
        private const val KEY_PROV_HEALTH = "zayn/provHealth"
        private const val KEY_MIGRATED = "zayn/migrated"

        /** The 11 app languages (engine-design.md §4.5, strict rule). */
        val APP_LANGS_11 = listOf(
            "English", "Bengali", "Hindi", "Marathi", "Telugu", "Tamil",
            "Gujarati", "Urdu", "Kannada", "Odia", "Malayalam"
        )

        /**
         * Full settings schema with defaults, taken from the UI's defaults()
         * (zaynplay-app.html) so no settings row is renamed. Engine-only
         * keys the UI never shows are at the end.
         */
        val SETTING_DEFAULTS: Map<String, Any?> = mapOf(
            "player" to "internal",
            "extApp" to "VLC",
            "engine" to "exoplayer",
            "titleChars" to "50",
            "showInfo" to true,
            "hideNames" to false,
            "resume" to true,
            "mode" to "auto",
            "res" to "Auto",
            "audio1" to "English",
            "audio2" to "বাংলা",
            "timeout" to "10s",
            "reuse" to true,
            "reuseDur" to "24h",
            "srcPrio" to "quality",
            "subLang" to "বাংলা",
            "subFilter" to "All",
            "subColor" to "সাদা",
            "subStyle" to "outline",
            "subPosV" to "bottom",
            "subPosH" to "center",
            "subSize" to "medium",
            "castStyle" to "outline",
            "castColor" to "সাদা",
            "castSize" to "medium",
            "gSwipeSeek" to true,
            "gSwipeBV" to true,
            "gDtSeek" to false,
            "gDtPause" to false,
            "gHold2x" to true,
            "seekAmt" to 10,
            "bufDisk" to "250 MB",
            "bufSize" to "64 MB",
            "bufLen" to "50s",
            "tvSeekShown" to 30,
            "tvSeekHidden" to 10,
            "theme" to "system",
            "layout" to "phone",
            "bottomTitles" to true,
            "hideQual" to "none",
            "confirmExit" to false,
            "posterUI" to listOf("রেটিং", "কোয়ালিটি"),
            "rowTrend" to true,
            "rowCW" to true,
            "rowTop10" to true,
            "appLang" to "English",
            "provLang" to listOf("English", "Bengali", "Hindi"),
            "media" to listOf("Movie", "TV Series", "Anime", "Cartoon", "Documentary", "Live TV"),
            "dubsub" to "both",
            "bioLock" to false,
            "updNotify" to true,
            "plugAutoUpd" to true,
            "apkInstaller" to "system",
            "backupFreq" to "weekly",
            "backupFolder" to "/Zayn Play/Backup",
            "dlPath" to "/Download/Zayn Play",
            "dlQuality" to "Auto",
            "wifiOnly" to false,
            "autoNextEp" to false,
            "dns" to "cloudflare",
            "swDec" to false,
            // Engine-only keys (kept native-side; the UI does not show them).
            "prefProvider" to JSONObject.NULL,
            "providerOrder" to emptyList<String>(),
            "providerPin" to JSONObject(),
            "jsdelivrProxy" to false,
            "enableAdult" to false,
            // v31 "which repos auto-download" multi-pick (inst.set.plugRepos).
            "plugRepos" to emptyList<String>()
        )

        /** Phase C stores (all JSON objects in the same DataStore). */
        const val KEY_LIBRARY = "zayn/library"
        const val KEY_PROGRESS = "zayn/progress"
        const val KEY_DOWNLOADS = "zayn/downloads"
        const val KEY_ACCOUNTS = "zayn/accounts"
        const val KEY_LAST_LINK = "zayn/lastLink"
    }

    init {
        migrateFromPhaseA()
    }

    /**
     * One-time copy of Phase A settings ("zayn-engine" prefs, keys
     * "set:<key>" in the {"v": ...} wrapper format) into DataStore.
     * Runs once; afterwards the old file is never read again.
     */
    private fun migrateFromPhaseA() {
        if (context.getKey<Boolean>(KEY_MIGRATED, false) == true) return
        try {
            val old = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            for ((key, value) in old.all) {
                if (key.startsWith("set:") && value is String) {
                    context.setKey(SETTINGS_PREFIX + key.removePrefix("set:"), value)
                }
            }
        } catch (e: Exception) {
            EngineLogger.log("store", "migration failed: ${e.message}")
        }
        context.setKey(KEY_MIGRATED, true)
    }

    // ---- settings ----

    /** All settings: stored values overlaid on the schema defaults. */
    fun settingsSnapshot(): JSONObject {
        val out = JSONObject()
        for ((key, def) in SETTING_DEFAULTS) {
            out.put(key, storedOrDefault(key, def))
        }
        return out
    }

    private fun storedOrDefault(key: String, def: Any?): Any? {
        val raw: String? = context.getKey<String>(SETTINGS_PREFIX + key, null)
            ?: return def
        return try {
            JSONObject(raw).opt("v")
        } catch (e: Exception) {
            def
        }
    }

    fun getSettingString(key: String, def: String): String {
        val v = storedOrDefault(key, def)
        return if (v is String) v else def
    }

    fun getSettingBoolean(key: String, def: Boolean): Boolean {
        val v = storedOrDefault(key, def)
        return if (v is Boolean) v else def
    }

    fun getSettingStringList(key: String, def: List<String>): List<String> {
        val v = storedOrDefault(key, def)
        return when (v) {
            is JSONArray -> (0 until v.length()).map { v.optString(it) }
            is List<*> -> v.filterIsInstance<String>()
            else -> def
        }
    }

    /**
     * Validate + persist one setting.
     * @return null on success, else an error code from the bridge set:
     *   "unknown_setting" | "invalid_value".
     */
    fun setSetting(key: String, value: Any?): String? {
        if (key !in SETTING_DEFAULTS) return "unknown_setting"
        if (key == "appLang") {
            if (value !is String || value !in APP_LANGS_11) return "invalid_value"
        }
        if (key == "provLang") {
            if (value !is JSONArray) return "invalid_value"
            for (i in 0 until value.length()) {
                val lang = value.optString(i, "")
                if (lang !in APP_LANGS_11) return "invalid_value"
            }
        }
        val wrapped = JSONObject().put("v", value).toString()
        context.setKey(SETTINGS_PREFIX + key, wrapped)
        EngineLogger.log("store", "settingsSet $key")
        return null
    }

    // ---- provider state (maps keyed "<repoId>|<providerName>") ----

    private fun readMap(key: String): JSONObject {
        val raw: String? = context.getKey<String>(key, null)
        return try {
            if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun writeMap(key: String, map: JSONObject) {
        context.setKey(key, map.toString())
    }

    private fun mapKey(repoId: String, providerName: String) = "$repoId|$providerName"

    fun isProviderOn(repoId: String, providerName: String): Boolean =
        readMap(KEY_PROV_ON).optBoolean(mapKey(repoId, providerName), false)

    fun setProviderOn(repoId: String, providerName: String, on: Boolean) {
        val map = readMap(KEY_PROV_ON)
        map.put(mapKey(repoId, providerName), on)
        writeMap(KEY_PROV_ON, map)
    }

    /** Names of all providers currently ON, across all repos. */
    fun providerOnNames(): Set<String> {
        val map = readMap(KEY_PROV_ON)
        val out = mutableSetOf<String>()
        val keys = map.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (map.optBoolean(k, false)) {
                out += k.substringAfter("|", k)
            }
        }
        return out
    }

    fun providerOnCount(): Int {
        val map = readMap(KEY_PROV_ON)
        var n = 0
        val keys = map.keys()
        while (keys.hasNext()) {
            if (map.optBoolean(keys.next(), false)) n++
        }
        return n
    }

    fun providerOnCount(repoId: String): Int {
        val map = readMap(KEY_PROV_ON)
        var n = 0
        val keys = map.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith("$repoId|") && map.optBoolean(k, false)) n++
        }
        return n
    }

    fun isProviderAutoOff(repoId: String, providerName: String): Boolean =
        readMap(KEY_PROV_AUTO_OFF).optBoolean(mapKey(repoId, providerName), false)

    fun setProviderAutoOff(repoId: String, providerName: String, autoOff: Boolean) {
        val map = readMap(KEY_PROV_AUTO_OFF)
        map.put(mapKey(repoId, providerName), autoOff)
        writeMap(KEY_PROV_AUTO_OFF, map)
    }

    /** Last health probe result for a provider ("ok" / "fail"), or null. */
    fun providerHealth(repoId: String, providerName: String): String? =
        readMap(KEY_PROV_HEALTH).optString(mapKey(repoId, providerName), null)

    fun setProviderHealth(repoId: String, providerName: String, health: String) {
        val map = readMap(KEY_PROV_HEALTH)
        map.put(mapKey(repoId, providerName), health)
        writeMap(KEY_PROV_HEALTH, map)
    }

    /** Drop all provider state for one repo (called on repoRemove). */
    fun clearRepoState(repoId: String) {
        for (key in listOf(KEY_PROV_ON, KEY_PROV_AUTO_OFF, KEY_PROV_HEALTH)) {
            val map = readMap(key)
            val keys = map.keys()
            val doomed = mutableListOf<String>()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.startsWith("$repoId|")) doomed += k
            }
            for (k in doomed) map.remove(k)
            writeMap(key, map)
        }
    }

    // ---- Phase C: generic JSON-object maps (library / progress /
    // downloads / accounts / last-link). One DataStore string per map,
    // values are JSON objects keyed by a stable id. ----

    fun readJsonMap(key: String): JSONObject = readMap(key)

    fun writeJsonMap(key: String, map: JSONObject) = writeMap(key, map)

    fun mapEntry(key: String, entryId: String): JSONObject? {
        val map = readMap(key)
        return if (map.has(entryId)) map.optJSONObject(entryId) else null
    }

    fun putMapEntry(key: String, entryId: String, entry: JSONObject) {
        val map = readMap(key)
        map.put(entryId, entry)
        writeMap(key, map)
    }

    fun removeMapEntry(key: String, entryId: String) {
        val map = readMap(key)
        map.remove(entryId)
        writeMap(key, map)
    }
}
