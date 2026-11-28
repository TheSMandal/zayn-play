package app.netlify.zaynplay.engine

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * (v1.0.18 P16-01 + P16-08) The V1.0.17 -> V1.0.18 migration: every
 * `zayn/` keys in the old plain-text `rebuild_preference` file moves
 * into its P16 home — settings into Jetpack DataStore Preferences,
 * library / progress / downloads / provider metadata / last-link into
 * Room, account tokens into EncryptedSharedPreferences.
 *
 * Rules (audit P16-08 + coordinator decision 3):
 * - Idempotent: safe to run twice (every write is an upsert; the
 *   migrated flag is only the skip signal).
 * - Verified: row counts AND content hashes (SHA-256 of the canonical
 *   sorted JSON per map) must match before the migrated flag is set.
 *   On ANY mismatch the flag stays unset, nothing is retired, and the
 *   migration retries on the next launch — a failed migration must
 *   never mark itself done (the Phase A bug: the old code set the flag
 *   unconditionally after the try/catch).
 * - The old keys are LEFT IN PLACE until verification passes, and stay
 *   in place afterwards too: the old file is the backup for one
 *   version. Retirement is a V1.0.19 decision after on-phone proof.
 * - Only keys in the current SETTING_DEFAULTS are migrated as
 *   settings. Keys removed in V1.0.18 (castStyle, enableAdult, …) are
 *   counted as skipped, not copied — a dead key must not resurrect.
 */
object ZaynMigration {

    const val KEY_MIGRATED_V1018 = "zayn/migrated_v1018"

    private fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Canonical form of a JSON object: keys sorted, compact. */
    private fun canonical(map: JSONObject): String {
        val keys = mutableListOf<String>()
        val it = map.keys()
        while (it.hasNext()) keys.add(it.next())
        keys.sort()
        val out = StringBuilder("{")
        for ((i, k) in keys.withIndex()) {
            if (i > 0) out.append(",")
            out.append(JSONObject.quote(k)).append(":")
            val v = map.opt(k)
            out.append(
                when (v) {
                    is JSONObject -> canonical(v)
                    else -> JSONObject.valueToString(v)
                },
            )
        }
        out.append("}")
        return out.toString()
    }

    /** Copy of a map with the "updatedAt" write timestamp removed
        from every top-level entry (see the migrateMap note): the
        migration stamps that field onto entries that lack it, so it
        must not participate in the content-hash comparison. */
    private fun stripUpdatedAt(map: JSONObject): JSONObject {
        val out = JSONObject()
        val it = map.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = map.opt(k)
            if (v is JSONObject) {
                val entry = JSONObject(v.toString())
                entry.remove("updatedAt")
                out.put(k, entry)
            } else {
                out.put(k, v)
            }
        }
        return out
    }

    data class Report(
        val migrated: Boolean,
        val settingsCopied: Int,
        val settingsSkipped: Int,
        val library: Int,
        val progress: Int,
        val downloads: Int,
        val providers: Int,
        val lastLink: Int,
        val accounts: Int,
        val hashMatch: Boolean,
        val error: String?,
    )

    fun runIfNeeded(
        context: Context,
        prefs: ZaynPrefsStore,
        room: ZaynRoomStore,
        secure: ZaynSecureStore,
    ): Report {
        if (prefs.getWrapper(KEY_MIGRATED_V1018) != null) {
            return Report(true, 0, 0, 0, 0, 0, 0, 0, 0, true, null)
        }
        return try {
            run(context, prefs, room, secure)
        } catch (e: Exception) {
            EngineLogger.log("store", "migration v1018 failed: ${e.message}")
            Report(false, 0, 0, 0, 0, 0, 0, 0, 0, false, e.message)
        }
    }

    private fun run(
        context: Context,
        prefs: ZaynPrefsStore,
        room: ZaynRoomStore,
        secure: ZaynSecureStore,
    ): Report {
        val legacy = context.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
        val all = legacy.all

        // ---- settings: zayn/settings/<key> -> DataStore ----
        var settingsCopied = 0
        var settingsSkipped = 0
        for ((k, v) in all) {
            if (!k.startsWith("zayn/settings/") || v !is String) continue
            val key = k.removePrefix("zayn/settings/")
            if (key !in EngineStore.SETTING_DEFAULTS) {
                // Dead key removed in V1.0.18 — do not resurrect it.
                settingsSkipped++
                continue
            }
            if (!prefs.contains(k)) prefs.setWrapper(k, v)
            settingsCopied++
        }

        // ---- provider maps -> Room provider rows ----
        fun legacyMap(name: String): JSONObject {
            val raw = all[name] as? String ?: return JSONObject()
            return try {
                JSONObject(raw)
            } catch (e: Exception) {
                JSONObject()
            }
        }
        val provOn = legacyMap("zayn/provOn")
        val provAutoOff = legacyMap("zayn/provAutoOff")
        val provHealth = legacyMap("zayn/provHealth")
        val provHealthDetail = legacyMap("zayn/provHealthDetail")
        val provKeys = mutableSetOf<String>()
        for (m in listOf(provOn, provAutoOff, provHealth, provHealthDetail)) {
            val it = m.keys()
            while (it.hasNext()) provKeys += it.next()
        }
        for (k in provKeys) {
            val detail = provHealthDetail.optJSONObject(k)
            room.upsertProvider(
                ProviderRow(
                    key = k,
                    on = provOn.optBoolean(k, false),
                    autoOff = provAutoOff.optBoolean(k, false),
                    health = if (provHealth.has(k)) provHealth.optString(k, null) else null,
                    healthDetail = detail?.toString() ?: "",
                    updatedAt = detail?.optLong("updatedAtMs", 0)?.takeIf { it > 0 }
                        ?: System.currentTimeMillis(),
                ),
            )
        }

        // ---- library / progress / downloads / last-link -> Room ----
        // (v1.0.18 fix, replacement implementer) Two corrections to the
        // first draft of this migration, both load-bearing:
        // (a) PROGRESS CAP: ZaynRoomStore prunes progress to
        // PROGRESS_CAP (500) on every write, so copying a legacy map
        // with >500 rows and then verifying count == legacy count could
        // NEVER pass — the migration would re-run on every launch
        // forever. The copy itself is capped to the 500 most recent
        // rows by updatedAt (the same rows the prune would keep), and
        // the verification below compares against that capped set.
        // (b) UPDATEDAT-INSENSITIVE HASH: ZaynRoomStore.putMapEntry
        // stamps updatedAt=now onto any entry that lacks one, so a
        // legacy entry without updatedAt (e.g. from an old backup)
        // differs from its Room copy by that one field. The hash
        // comparison strips "updatedAt" from both sides (see
        // stripUpdatedAt) — the field is a write timestamp, not
        // content — while every other field must match exactly.
        fun migrateMap(legacyKey: String, newKey: String, cap: Int = -1): Int {
            val map = legacyMap(legacyKey)
            val ids = mutableListOf<String>()
            val it = map.keys()
            while (it.hasNext()) ids += it.next()
            if (cap > 0 && ids.size > cap) {
                ids.sortByDescending { map.optJSONObject(it)?.optLong("updatedAt", 0L) ?: 0L }
            }
            var n = 0
            for (id in if (cap > 0) ids.take(cap) else ids) {
                val entry = map.optJSONObject(id) ?: continue
                room.putMapEntry(newKey, id, entry)
                n++
            }
            return n
        }
        val libraryN = migrateMap(EngineStore.KEY_LIBRARY, EngineStore.KEY_LIBRARY)
        val progressN = migrateMap(
            EngineStore.KEY_PROGRESS,
            EngineStore.KEY_PROGRESS,
            ZaynRoomStore.PROGRESS_CAP,
        )
        val downloadsN = migrateMap(EngineStore.KEY_DOWNLOADS, EngineStore.KEY_DOWNLOADS)
        val lastLinkN = migrateMap(EngineStore.KEY_LAST_LINK, EngineStore.KEY_LAST_LINK)

        // ---- accounts -> encrypted store (tokens out of plaintext) ----
        val accountsMap = legacyMap(EngineStore.KEY_ACCOUNTS)
        var accountsN = 0
        val ait = accountsMap.keys()
        while (ait.hasNext()) {
            val p = ait.next()
            val entry = accountsMap.optJSONObject(p) ?: continue
            if (secure.available) {
                secure.putAccount(p, entry)
                accountsN++
            }
        }

        // ---- verification: counts + content hashes ----
        // The expected progress map is the capped set (newest 500 by
        // updatedAt), matching what migrateMap copied; hashes ignore
        // "updatedAt" (see the note above migrateMap).
        val legacyProgressAll = legacyMap(EngineStore.KEY_PROGRESS)
        val expectedProgress = JSONObject()
        run {
            val ids = mutableListOf<String>()
            val pit = legacyProgressAll.keys()
            while (pit.hasNext()) ids += pit.next()
            ids.sortByDescending { legacyProgressAll.optJSONObject(it)?.optLong("updatedAt", 0L) ?: 0L }
            for (id in ids.take(ZaynRoomStore.PROGRESS_CAP)) {
                legacyProgressAll.optJSONObject(id)?.let { expectedProgress.put(id, it) }
            }
        }
        val hashMatch =
            sha256Hex(canonical(stripUpdatedAt(room.readJsonMap(EngineStore.KEY_LIBRARY)))) ==
                sha256Hex(canonical(stripUpdatedAt(legacyMap(EngineStore.KEY_LIBRARY)))) &&
                sha256Hex(canonical(stripUpdatedAt(room.readJsonMap(EngineStore.KEY_PROGRESS)))) ==
                sha256Hex(canonical(stripUpdatedAt(expectedProgress))) &&
                sha256Hex(canonical(stripUpdatedAt(room.readJsonMap(EngineStore.KEY_DOWNLOADS)))) ==
                sha256Hex(canonical(stripUpdatedAt(legacyMap(EngineStore.KEY_DOWNLOADS)))) &&
                room.providerCount() == provKeys.size &&
                room.count(EngineStore.KEY_LIBRARY) == libraryN &&
                room.count(EngineStore.KEY_PROGRESS) == progressN &&
                room.count(EngineStore.KEY_DOWNLOADS) == downloadsN
        if (!hashMatch) {
            EngineLogger.log(
                "store",
                "migration v1018 VERIFICATION FAILED — flag NOT set, will retry " +
                    "(lib=$libraryN prog=$progressN dl=$downloadsN prov=${provKeys.size})",
            )
            return Report(false, settingsCopied, settingsSkipped, libraryN, progressN, downloadsN, provKeys.size, lastLinkN, accountsN, false, "hash/count mismatch")
        }
        // Verified: only now mark migrated. Old keys stay in place as
        // the one-version backup (coordinator decision 3).
        prefs.setWrapper(KEY_MIGRATED_V1018, JSONObject().put("v", true).toString())
        EngineLogger.log(
            "store",
            "migration v1018 OK settings=$settingsCopied skipped=$settingsSkipped " +
                "library=$libraryN progress=$progressN downloads=$downloadsN " +
                "providers=${provKeys.size} lastLink=$lastLinkN accounts=$accountsN",
        )
        return Report(true, settingsCopied, settingsSkipped, libraryN, progressN, downloadsN, provKeys.size, lastLinkN, accountsN, true, null)
    }
}
