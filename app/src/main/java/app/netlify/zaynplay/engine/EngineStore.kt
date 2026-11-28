package app.netlify.zaynplay.engine

import android.content.Context
import app.netlify.zaynplay.ThemePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase B engine store: the bridge facade for engine state.
 *
 * (v1.0.18 P16-01) BACKEND REPLACED. The old backend was ONE plain-text
 * SharedPreferences XML file (`rebuild_preference`) behind a vendored
 * wrapper misleadingly named `DataStore`. The P16 backends are now:
 * - Settings -> Jetpack DataStore Preferences (ZaynPrefsStore), one
 *   `zayn/settings/<key>` string per setting, same `{"v": ...}` wrapper
 *   format as before, so the migration is a plain copy.
 * - Library / progress / downloads / provider metadata / last-link /
 *   custom sites -> Room (ZaynRoomStore, `zayn.db`).
 * - Account tokens -> EncryptedSharedPreferences (ZaynSecureStore).
 * The public API below is UNCHANGED (bridge contract), so the HTML
 * and the rest of the engine do not move. The old file is LEFT IN
 * PLACE after migration as the one-version backup (coordinator
 * decision 3); ZaynMigration verifies counts + content hashes before
 * it marks the move done, and never marks a failed migration done
 * (P16-08 — the old Phase A migration set its flag unconditionally).
 *
 * Two kinds of state:
 * - Settings: one key per setting ("zayn/settings/<key>"), each value a
 *   small JSON wrapper {"v": <value>}.
 * - Provider state: one Room row per "<repoId>|<providerName>" key,
 *   carrying on / autoOff / health / healthDetail.
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
        private const val KEY_PROV_HEALTH_DETAIL = "zayn/provHealthDetail"
        private const val KEY_MIGRATED = "zayn/migrated"

        /** The 11 app languages (engine-design.md §4.5, strict rule). */
        val APP_LANGS_11 = listOf(
            "English", "Bengali", "Hindi", "Marathi", "Telugu", "Tamil",
            "Gujarati", "Urdu", "Kannada", "Odia", "Malayalam",
        )

        /** Native names of the 11 languages (audio/subtitle pickers). */
        private val LANG_NATIVES_11 = setOf(
            "English", "বাংলা", "हिन्दी", "मराठी", "తెలుగు", "தமிழ்",
            "ગુજરાતી", "اردو", "ಕನ್ನಡ", "ଓଡ଼ିଆ", "മലയാളം",
        )

        private val MEDIA_TYPES = setOf(
            "Movie", "AnimeMovie", "TvSeries", "Cartoon", "Anime", "OVA",
            "Torrent", "Documentary", "AsianDrama", "Live", "NSFW", "Others",
            "Music", "AudioBook", "CustomMedia", "Audio", "Podcast", "Video",
        )

        /**
         * Full settings schema with defaults, taken from the UI's defaults()
         * (zaynplay-app.html) so no settings row is renamed. Engine-only
         * keys the UI never shows are at the end.
         *
         * (v1.0.18 P17-01/23) REMOVED keys (dead — persisted but never
         * read, or a feature that does not exist): castStyle, castColor,
         * castSize (no Cast code at all), swDec (no player flag), bufSize,
         * bufLen (no player mapping), tvSeekShown, tvSeekHidden (no TV
         * seek path), autoNextEp (no download trigger), dubsub (search
         * items carry no dub/sub data), rowTop10 (no Top 10 row exists
         * in Home), hideQual (search items carry no quality — quality
         * exists only on resolved links), enableAdult (NSFW is the media
         * list only), prefProvider, apkInstaller, plugRepos (no reader,
         * no row). A V1.0.17 backup carrying them restores fine — the
         * importer skips unknown keys and counts them (P16-03).
         * (v1.0.23) engine REMOVED too: the player-choice setting is gone
         * entirely (user decision 2026-11-28 — ExoPlayer is the only
         * player). A phone with the removed player stored as its engine
         * falls back silently: the key is no longer in the schema, so
         * settingsSet rejects it as unknown_setting, settingsSnapshot
         * never reports it, and no code reads it — no migration needed.
         * ADDED keys: profileName, profileBg (setup profile, P17-25),
         * primaryColor (was localStorage-only, P16-09), lastBackup +
         * benene (were in-memory only, F-7/F-8).
         */
        val SETTING_DEFAULTS: Map<String, Any?> = mapOf(
            "player" to "internal",
            "extApp" to "VLC",
            "titleChars" to "50",
            "showInfo" to true,
            "resume" to true,
            "mode" to "auto",
            "res" to "Auto",
            "audio1" to "English",
            "audio2" to "বাংলা",
            // (v1.0.19 REQ1) Third audio language. Default "none": no
            // third preference until the user picks one, so ranking
            // with only audio1/audio2 set is byte-identical to before.
            "audio3" to "none",
            "timeout" to "30s",
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
            "gSwipeSeek" to true,
            "gSwipeBV" to true,
            "gDtSeek" to false,
            "gDtPause" to false,
            "gHold2x" to true,
            "seekAmt" to 10,
            "bufDisk" to "250 MB",
            "buffer" to "normal",
            "theme" to "system",
            // (v1.0.18 P17-13) Default is "auto": MainActivity's
            // formFactor() detects phone/tablet/TV until the user picks
            // one. A "phone" default would force phone layout on tablets.
            "layout" to "auto",
            "bottomTitles" to true,
            "posterUI" to listOf("রেটিং", "সাল"),
            "rowTrend" to true,
            "rowCW" to true,
            // (v1.0.21 ITEM 5) confirmExit REMOVED: double-back-to-exit
            // replaces the confirm dialog. A stale stored value is ignored
            // (applyEngineSettings only overlays keys present in inst.set)
            // and can never be written again (no UI writes it).
            "appLang" to "English",
            "media" to listOf("Movie", "AnimeMovie", "TvSeries", "Cartoon", "Anime", "OVA", "Torrent", "Documentary", "AsianDrama", "Live", "Others", "Music", "AudioBook", "CustomMedia", "Audio", "Podcast", "Video"),
            "bioLock" to false,
            "updNotify" to true,
            "plugAutoUpd" to true,
            "backupFreq" to "weekly",
            "backupFolder" to "/Zayn Play/Backup",
            // (v1.0.16 P10-09) dlPath default removed with the picker: no
            // native code ever read it; downloads always use the app's
            // Download / Zayn Play folder.
            "dlQuality" to "Auto",
            "wifiOnly" to false,
            // (v0.2.2) Upstream CloudStream defaults DNS to system (0).
            // Our old default was Cloudflare DoH with no fallback, so a
            // DoH failure on the phone's network broke all downloads.
            // (DoH resolvers now fall back to system DNS too.)
            "dns" to "system",
            // (v1.0.18 P17-25) Setup profile, persisted (was in-memory).
            "profileName" to "Zayn",
            "profileBg" to "linear-gradient(135deg,#ff5147,#b3000c)",
            // (v1.0.18 P16-09) Primary colour, persisted (was
            // localStorage-only, lost on restore/clear-data).
            "primaryColor" to "#e8261c",
            // (v1.0.18 F-7/F-8) Were in-memory only (reset every restart).
            "lastBackup" to "",
            "benene" to 0,
            // Engine-only keys (kept native-side; the UI does not show them).
            "providerOrder" to emptyList<String>(),
            "providerPin" to JSONObject(),
            "jsdelivrProxy" to false,
        )

        /** Phase C stores (Room tables + encrypted accounts). */
        const val KEY_LIBRARY = "zayn/library"
        const val KEY_PROGRESS = "zayn/progress"
        const val KEY_DOWNLOADS = "zayn/downloads"
        const val KEY_ACCOUNTS = "zayn/accounts"
        const val KEY_LAST_LINK = "zayn/lastLink"

        /**
         * (v1.0.18 P16-11) Per-key validation. `setSetting` used to
         * validate only appLang — every other key accepted ANY
         * JSON (`theme: 123`, `buffer: "banana"`). Every key now has a
         * validator against its option list (or its type/range for
         * sliders and free strings). Values arrive as org.json types
         * from the bridge (String / Boolean / Integer / JSONArray /
         * JSONObject / JSONObject.NULL) or from backupImport (same).
         * Engine-only shapes are accepted as documented:
         * providerOrder [] (JSONArray of strings), providerPin {}
         * (JSONObject).
         */
        private fun isPick(value: Any?, vararg allowed: String): Boolean =
            value is String && value in allowed

        private fun isBool(value: Any?): Boolean = value is Boolean

        private fun isStringList(value: Any?, allowed: Set<String>): Boolean {
            if (value !is JSONArray) return false
            for (i in 0 until value.length()) {
                if (value.optString(i, "") !in allowed) return false
            }
            return true
        }

        private val SETTING_VALIDATORS: Map<String, (Any?) -> Boolean> = mapOf(
            "player" to { v -> isPick(v, "internal", "external") },
            "extApp" to { v -> isPick(v, "VLC", "MX Player", "JustPlayer") },
            "titleChars" to { v -> isPick(v, "30", "50", "80", "120", "0") },
            "showInfo" to { v -> isBool(v) },
            "resume" to { v -> isBool(v) },
            "mode" to { v -> isPick(v, "auto", "manual") },
            "res" to { v -> isPick(v, "Auto", "4K", "2K", "1080p", "720p", "480p", "360p") },
            "audio1" to { v -> v is String && v in LANG_NATIVES_11 },
            "audio2" to { v -> v is String && (v in LANG_NATIVES_11 || v == "none") },
            "audio3" to { v -> v is String && (v in LANG_NATIVES_11 || v == "none") },
            "timeout" to { v -> isPick(v, "Instant", "5s", "10s", "15s", "30s", "Unlimited") },
            "reuse" to { v -> isBool(v) },
            "reuseDur" to { v -> isPick(v, "1h", "6h", "24h", "7d") },
            // 'size' removed in V1.0.18: no size data exists on links.
            "srcPrio" to { v -> isPick(v, "quality", "speed") },
            "subLang" to { v -> v is String && (v in LANG_NATIVES_11 || v == "Off") },
            "subFilter" to { v -> v is String && (v == "All" || v in LANG_NATIVES_11) },
            "subColor" to { v -> isPick(v, "সাদা", "হলুদ", "সবুজ", "আকাশি", "গোলাপি") },
            "subStyle" to { v -> isPick(v, "outline", "shadow", "raised", "depressed", "none") },
            "subPosV" to { v -> isPick(v, "top", "middle", "bottom") },
            "subPosH" to { v -> isPick(v, "left", "center", "right") },
            "subSize" to { v -> isPick(v, "small", "medium", "large", "xlarge") },
            "gSwipeSeek" to { v -> isBool(v) },
            "gSwipeBV" to { v -> isBool(v) },
            "gDtSeek" to { v -> isBool(v) },
            "gDtPause" to { v -> isBool(v) },
            "gHold2x" to { v -> isBool(v) },
            "seekAmt" to { v -> v is Number && v.toInt() in 5..60 },
            "bufDisk" to { v -> isPick(v, "100 MB", "250 MB", "500 MB", "1 GB") },
            "buffer" to { v -> isPick(v, "verylow", "low", "normal", "high", "veryhigh") },
            "theme" to { v -> isPick(v, "system", "dark", "light") },
            // (v1.0.21) "tablet" joins the layout pick: without it
            // setSetting rejects the value as invalid_value and the
            // Settings row can never persist — a dead row. The native
            // formFactor() already accepts "tablet" (MainActivity) and
            // the form-tablet CSS already exists; this one line was
            // the missing link.
            "layout" to { v -> isPick(v, "phone", "tablet", "tv", "auto") },
            "bottomTitles" to { v -> isBool(v) },
            // Quality / Dub-Sub removed in V1.0.18: no card carries a
            // quality or dub/sub badge to filter on.
            "posterUI" to { v -> isStringList(v, setOf("রেটিং", "সাল")) },
            "rowTrend" to { v -> isBool(v) },
            "rowCW" to { v -> isBool(v) },
            "appLang" to { v -> v is String && v in APP_LANGS_11 },
            "media" to { v -> isStringList(v, MEDIA_TYPES) },
            "bioLock" to { v -> isBool(v) },
            "updNotify" to { v -> isBool(v) },
            "plugAutoUpd" to { v -> isBool(v) },
            "backupFreq" to { v -> isPick(v, "never", "daily", "weekly", "monthly") },
            "backupFolder" to { v ->
                isPick(v, "/Zayn Play/Backup", "/Download/Zayn Play Backup", "/Documents/Zayn Play")
            },
            "dlQuality" to { v -> isPick(v, "Auto", "4K", "2K", "1080p", "720p", "480p") },
            "wifiOnly" to { v -> isBool(v) },
            "dns" to { v ->
                isPick(v, "system", "cloudflare", "google", "quad9", "adguard", "dnswatch", "dnssb", "canadianshield")
            },
            "profileName" to { v -> v is String && v.length <= 40 },
            "profileBg" to { v -> v is String && v.length <= 200 },
            "primaryColor" to { v -> v is String && Regex("^#[0-9a-fA-F]{6}$").matches(v) },
            "lastBackup" to { v -> v is String },
            "benene" to { v -> v is Number && v.toInt() >= 0 },
            "providerOrder" to { v ->
                v is JSONArray && (0 until v.length()).all { v.opt(it) is String }
            },
            "providerPin" to { v -> v is JSONObject },
            "jsdelivrProxy" to { v -> isBool(v) },
        )
    }

    private val prefs = ZaynPrefsStore.get(context)
    private val room = ZaynRoomStore(context)
    private val secure = ZaynSecureStore(context)

    init {
        migrateFromPhaseA()
        ZaynMigration.runIfNeeded(context, prefs, room, secure)
    }

    /**
     * One-time copy of Phase A settings ("zayn-engine" prefs, keys
     * "set:<key>" in the {"v": ...} wrapper format) into the new
     * DataStore. Runs once; afterwards the old file is never read again.
     *
     * (v1.0.18 P16-08) FIXED: the old code set the migrated flag
     * UNCONDITIONALLY after the try/catch — a copy that threw halfway
     * was marked done and never retried, silently losing settings.
     * Now the flag is set ONLY when every source key was copied (or
     * the source was empty); on failure the flag stays unset, the
     * failure is logged, and the next launch retries. The copy is
     * idempotent: a key already in the new store is never overwritten.
     */
    private fun migrateFromPhaseA() {
        if (prefs.getWrapper(KEY_MIGRATED) != null) return
        try {
            val old = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val sourceKeys = old.all.keys.filter { it.startsWith("set:") }
            var copied = 0
            for (key in sourceKeys) {
                val value = old.all[key] as? String ?: continue
                val newKey = SETTINGS_PREFIX + key.removePrefix("set:")
                val shortKey = key.removePrefix("set:")
                if (shortKey !in SETTING_DEFAULTS) continue
                if (!prefs.contains(newKey)) prefs.setWrapper(newKey, value)
                copied++
            }
            val expected = sourceKeys.count { it.removePrefix("set:") in SETTING_DEFAULTS }
            if (copied == expected) {
                prefs.setWrapper(KEY_MIGRATED, JSONObject().put("v", true).toString())
            } else {
                EngineLogger.log("store", "migration Phase A incomplete: copied $copied of $expected — will retry")
            }
        } catch (e: Exception) {
            EngineLogger.log("store", "migration Phase A failed: ${e.message} — will retry")
        }
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
        val raw: String? = prefs.getWrapper(SETTINGS_PREFIX + key)
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
    /**
     * (v1.0.18 P16-04) Validate without writing, so backup import can
     * fail BEFORE applying anything. One validation path: setSetting
     * calls this too.
     */
    fun validateSetting(key: String, value: Any?): String? {
        if (key !in SETTING_DEFAULTS) return "unknown_setting"
        val validator = SETTING_VALIDATORS[key]
        if (validator != null && !validator(value)) return "invalid_value"
        return null
    }

    fun setSetting(key: String, value: Any?): String? {
        validateSetting(key, value)?.let { return it }
        val wrapped = JSONObject().put("v", value).toString()
        prefs.setWrapper(SETTINGS_PREFIX + key, wrapped)
        // The theme has ONE derived cache for the pre-onCreate splash
        // read (ThemePrefs, file `zaynplay`): mirror the source value
        // into it here, the single writer. The DataStore value is the
        // source of truth; the splash cache only accelerates first paint.
        if (key == "theme" && value is String) {
            try {
                ThemePrefs.store(context, value)
            } catch (e: Exception) {
            }
        }
        EngineLogger.log("store", "settingsSet $key")
        return null
    }

    /** Reset every setting to its schema default (redo-setup). The
        provider maps, library, progress and downloads are untouched —
        a settings reset must never wipe the user's data. */
    fun resetSettingsToDefaults(): Int {
        var n = 0
        for ((key, def) in SETTING_DEFAULTS) {
            val wrapped = JSONObject().put("v", def).toString()
            prefs.setWrapper(SETTINGS_PREFIX + key, wrapped)
            n++
        }
        try {
            ThemePrefs.store(context, getSettingString("theme", "system"))
        } catch (e: Exception) {
        }
        return n
    }

    // ---- provider state (Room rows keyed "<repoId>|<providerName>") ----
    // (v0.2.1-a) Health probes run in async chunks and do
    // read-modify-write on shared provider state; every read+write pair
    // below runs under one lock so two probes cannot clobber each other.
    private val mapLock = Any()

    private fun providerRow(repoId: String, providerName: String): ProviderRow? =
        room.providerRow("$repoId|$providerName")

    private fun upsertProviderField(
        repoId: String,
        providerName: String,
        mutate: (ProviderRow) -> ProviderRow,
    ) {
        synchronized(mapLock) {
            val key = "$repoId|$providerName"
            val cur = room.providerRow(key) ?: ProviderRow(key, false, false, null, "", System.currentTimeMillis())
            room.upsertProvider(mutate(cur).copy(updatedAt = System.currentTimeMillis()))
        }
    }

    fun isProviderOn(repoId: String, providerName: String): Boolean =
        providerRow(repoId, providerName)?.on ?: false

    fun setProviderOn(repoId: String, providerName: String, on: Boolean) {
        upsertProviderField(repoId, providerName) { it.copy(on = on) }
    }

    /** (v0.2.11) C-10: ON providers as (repoId, name) pairs. Keys are
        "<repoId>|<name>"; a name alone cannot tell two repos apart. */
    fun providerOnPairs(): List<Pair<String, String>> {
        return room.providerRows()
            .filter { it.on && it.key.contains("|") }
            .map { it.key.substringBefore("|") to it.key.substringAfter("|") }
    }

    /** Names of all providers currently ON, across all repos. */
    fun providerOnNames(): Set<String> {
        return room.providerRows()
            .filter { it.on }
            .map { it.key.substringAfter("|", it.key) }
            .toSet()
    }

    fun providerOnCount(): Int = room.providerRows().count { it.on }

    fun providerOnCount(repoId: String): Int =
        room.providerRows().count { it.on && it.key.startsWith("$repoId|") }

    fun isProviderAutoOff(repoId: String, providerName: String): Boolean =
        providerRow(repoId, providerName)?.autoOff ?: false

    fun setProviderAutoOff(repoId: String, providerName: String, autoOff: Boolean) {
        upsertProviderField(repoId, providerName) { it.copy(autoOff = autoOff) }
    }

    /** Last health probe result for a provider ("ok" / "fail"), or null. */
    fun providerHealth(repoId: String, providerName: String): String? =
        providerRow(repoId, providerName)?.health

    fun setProviderHealth(repoId: String, providerName: String, health: String) {
        upsertProviderField(repoId, providerName) { it.copy(health = health) }
    }

    /**
     * (v1.0.17 P15-01) Per-provider Part H health record: the 8 fields
     * (load / dataValidation / linkResolution / streamValidation /
     * playerPrep / playback / lastError / lastSuccessAtMs) + lastStage +
     * summary + updatedAtMs, as one JSON object per provider. Updated
     * field-by-field (merge); the one-word summary in the provider row
     * stays the compatibility summary. Persisted in Room, so the
     * record survives an app restart.
     */
    fun providerHealthDetail(repoId: String, providerName: String): JSONObject? {
        val raw = providerRow(repoId, providerName)?.healthDetail ?: return null
        if (raw.isBlank()) return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }

    fun providerHealthDetails(): JSONObject {
        val out = JSONObject()
        for (row in room.providerRows()) {
            if (row.healthDetail.isBlank()) continue
            try {
                out.put(row.key, JSONObject(row.healthDetail))
            } catch (e: Exception) {
            }
        }
        return out
    }

    fun updateProviderHealthDetail(repoId: String, providerName: String, update: JSONObject) {
        synchronized(mapLock) {
            val key = "$repoId|$providerName"
            val cur = try {
                room.providerRow(key)?.healthDetail?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
                    ?: JSONObject()
            } catch (e: Exception) {
                JSONObject()
            }
            val keys = update.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                cur.put(k, update.opt(k))
            }
            cur.put("repoId", repoId)
            cur.put("providerName", providerName)
            cur.put("updatedAtMs", System.currentTimeMillis())
            upsertProviderField(repoId, providerName) { it.copy(healthDetail = cur.toString()) }
        }
    }

    /** Drop all provider state for one repo (called on repoRemove). */
    fun clearRepoState(repoId: String) {
        room.deleteProvidersForRepo(repoId)
    }

    // ---- Phase C: JSON-object maps (library / progress / downloads /
    // accounts / last-link). Room tables now; accounts in the encrypted
    // store. The API is unchanged so the bridge does not move. ----

    fun readJsonMap(key: String): JSONObject {
        return when (key) {
            KEY_ACCOUNTS -> secure.accountsMap()
            KEY_PROV_ON, KEY_PROV_AUTO_OFF, KEY_PROV_HEALTH, KEY_PROV_HEALTH_DETAIL -> {
                val out = JSONObject()
                for (row in room.providerRows()) {
                    when (key) {
                        KEY_PROV_ON -> out.put(row.key, row.on)
                        KEY_PROV_AUTO_OFF -> out.put(row.key, row.autoOff)
                        KEY_PROV_HEALTH -> if (row.health != null) out.put(row.key, row.health)
                        KEY_PROV_HEALTH_DETAIL -> {
                            if (row.healthDetail.isNotBlank()) {
                                try {
                                    out.put(row.key, JSONObject(row.healthDetail))
                                } catch (e: Exception) {
                                }
                            }
                        }
                    }
                }
                out
            }
            else -> room.readJsonMap(key)
        }
    }

    fun writeJsonMap(key: String, map: JSONObject) {
        when (key) {
            KEY_ACCOUNTS -> {
                // Per-entry upsert (no deletion — a restore never writes
                // accounts; this path exists for API compat only).
                val keys = map.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    map.optJSONObject(id)?.let { secure.putAccount(id, it) }
                }
            }
            KEY_PROV_ON, KEY_PROV_AUTO_OFF, KEY_PROV_HEALTH, KEY_PROV_HEALTH_DETAIL -> {
                // Whole-map replace, as the old writeJsonMap did: fields
                // absent from the map reset to their empty value.
                synchronized(mapLock) {
                    for (row in room.providerRows()) {
                        val next = when (key) {
                            KEY_PROV_ON -> row.copy(on = map.optBoolean(row.key, false))
                            KEY_PROV_AUTO_OFF -> row.copy(autoOff = map.optBoolean(row.key, false))
                            KEY_PROV_HEALTH -> row.copy(
                                health = if (map.has(row.key)) map.optString(row.key, null) else null,
                            )
                            else -> row.copy(
                                healthDetail = map.optJSONObject(row.key)?.toString() ?: "",
                            )
                        }
                        room.upsertProvider(next.copy(updatedAt = System.currentTimeMillis()))
                    }
                    // Keys in the map with no row yet get created.
                    val keys = map.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (room.providerRow(k) == null) {
                            val row = when (key) {
                                KEY_PROV_ON -> ProviderRow(k, map.optBoolean(k, false), false, null, "", System.currentTimeMillis())
                                KEY_PROV_AUTO_OFF -> ProviderRow(k, false, map.optBoolean(k, false), null, "", System.currentTimeMillis())
                                KEY_PROV_HEALTH -> ProviderRow(k, false, false, map.optString(k, null), "", System.currentTimeMillis())
                                else -> ProviderRow(k, false, false, null, map.optJSONObject(k)?.toString() ?: "", System.currentTimeMillis())
                            }
                            room.upsertProvider(row)
                        }
                    }
                }
            }
            else -> room.writeJsonMap(key, map)
        }
    }

    fun mapEntry(key: String, entryId: String): JSONObject? {
        return when (key) {
            KEY_ACCOUNTS -> secure.accountEntry(entryId)
            KEY_PROV_ON, KEY_PROV_AUTO_OFF, KEY_PROV_HEALTH, KEY_PROV_HEALTH_DETAIL -> {
                val row = room.providerRow(entryId) ?: return null
                when (key) {
                    KEY_PROV_ON -> JSONObject().put(entryId, row.on)
                    KEY_PROV_AUTO_OFF -> JSONObject().put(entryId, row.autoOff)
                    KEY_PROV_HEALTH -> row.health?.let { JSONObject().put(entryId, it) }
                    else -> if (row.healthDetail.isBlank()) null else try {
                        JSONObject(row.healthDetail)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            else -> room.mapEntry(key, entryId)
        }
    }

    fun putMapEntry(key: String, entryId: String, entry: JSONObject) {
        when (key) {
            KEY_ACCOUNTS -> secure.putAccount(entryId, entry)
            KEY_PROV_ON, KEY_PROV_AUTO_OFF, KEY_PROV_HEALTH, KEY_PROV_HEALTH_DETAIL -> {
                synchronized(mapLock) {
                    val row = room.providerRow(entryId)
                        ?: ProviderRow(entryId, false, false, null, "", System.currentTimeMillis())
                    val next = when (key) {
                        KEY_PROV_ON -> row.copy(on = entry.optBoolean(entryId, entry.optBoolean("v", false)))
                        KEY_PROV_AUTO_OFF -> row.copy(autoOff = entry.optBoolean(entryId, false))
                        KEY_PROV_HEALTH -> row.copy(health = entry.optString(entryId, null))
                        else -> row.copy(healthDetail = entry.toString())
                    }
                    room.upsertProvider(next.copy(updatedAt = System.currentTimeMillis()))
                }
            }
            else -> room.putMapEntry(key, entryId, entry)
        }
    }

    fun removeMapEntry(key: String, entryId: String) {
        when (key) {
            KEY_ACCOUNTS -> secure.removeAccount(entryId)
            KEY_PROV_ON, KEY_PROV_AUTO_OFF, KEY_PROV_HEALTH, KEY_PROV_HEALTH_DETAIL -> {
                // Removing one provider field clears that field only.
                synchronized(mapLock) {
                    val row = room.providerRow(entryId) ?: return
                    val next = when (key) {
                        KEY_PROV_ON -> row.copy(on = false)
                        KEY_PROV_AUTO_OFF -> row.copy(autoOff = false)
                        KEY_PROV_HEALTH -> row.copy(health = null)
                        else -> row.copy(healthDetail = "")
                    }
                    room.upsertProvider(next.copy(updatedAt = System.currentTimeMillis()))
                }
            }
            else -> room.removeMapEntry(key, entryId)
        }
    }

    // ---- accounts (encrypted store, P16-02) ----

    fun accountEntry(provider: String): JSONObject? = secure.accountEntry(provider)

    fun putAccount(provider: String, entry: JSONObject) = secure.putAccount(provider, entry)

    fun removeAccount(provider: String) = secure.removeAccount(provider)

    fun accountsMap(): JSONObject = secure.accountsMap()

    // ---- custom sites (Room, P17-23) ----

    fun customSitesMap(): JSONObject = room.customSitesMap()

    fun setCustomSites(map: JSONObject) = room.setCustomSites(map)
}
