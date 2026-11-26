package app.netlify.zaynplay.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase A engine store: a SharedPreferences facade for bridge-owned state.
 *
 * Engine state (settings, and later repos / provider ON-OFF / library /
 * progress) lives here — never in the WebView's localStorage. The schema is
 * the UI's defaults() keys (engine-design.md §4.5) plus engine-only keys.
 * Each value is stored as a small JSON wrapper {"v": <value>} so any JSON
 * type round-trips exactly.
 */
class EngineStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "zayn-engine"

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
            "enableAdult" to false
        )
    }

    /** All settings: stored values overlaid on the schema defaults. */
    fun settingsSnapshot(): JSONObject {
        val out = JSONObject()
        for ((key, def) in SETTING_DEFAULTS) {
            out.put(key, storedOrDefault(key, def))
        }
        return out
    }

    private fun storedOrDefault(key: String, def: Any?): Any? {
        val raw = prefs.getString("set:$key", null) ?: return def
        return try {
            JSONObject(raw).opt("v")
        } catch (e: Exception) {
            def
        }
    }

    /**
     * Validate + persist one setting.
     * @return null on success, else an error code from the bridge set:
     *   "unknown_setting" | "invalid_value".
     * Per-key range validation beyond the 11-language rule lands with the
     * settings wiring in a later phase; Phase A enforces key existence and
     * the language rule only.
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
        prefs.edit().putString("set:$key", wrapped).apply()
        EngineLogger.log("store", "settingsSet $key")
        return null
    }
}
