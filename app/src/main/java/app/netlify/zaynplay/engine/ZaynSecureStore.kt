package app.netlify.zaynplay.engine

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * (v1.0.18 P16-02) Encrypted storage for account tokens
 * (AES-256-GCM via the AndroidX security-crypto MasterKey).
 *
 * The old code wrote tokens into the same plaintext
 * SharedPreferences XML as everything else
 * (ZaynAccounts.storeToken -> zayn/accounts map). That exposure was
 * latent — no login can complete today (keys are gated) — but the
 * moment any login ships, a plaintext token is readable by any
 * root/adb-backup reader. Tokens now live here, never in
 * `rebuild_preference`, never in settingsSnapshot, never in backups
 * (backupExport already excludes them), and never in logs.
 *
 * Fail-closed: if the encrypted store cannot be created (keystore
 * problem on some devices), `available` is false and every method is
 * a no-op — logins then report logged-out instead of silently writing
 * a token to plaintext. There is NO plaintext fallback.
 */
class ZaynSecureStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "zayn_accounts_enc"
    }

    private var prefs: SharedPreferences? = null

    /** False when the encrypted store could not be created. */
    val available: Boolean
        get() = prefs != null

    init {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            EngineLogger.log("store", "secure: encrypted store unavailable: ${e.message}")
            prefs = null
        }
    }

    fun accountEntry(provider: String): JSONObject? {
        val raw = prefs?.getString(provider, null) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }

    fun putAccount(provider: String, entry: JSONObject) {
        prefs?.edit()?.putString(provider, entry.toString())?.apply()
    }

    fun removeAccount(provider: String) {
        prefs?.edit()?.remove(provider)?.apply()
    }

    /** All stored accounts (provider -> {token, name}), for status(). */
    fun accountsMap(): JSONObject {
        val out = JSONObject()
        val p = prefs ?: return out
        for ((key, value) in p.all) {
            if (value is String) {
                try {
                    out.put(key, JSONObject(value))
                } catch (e: Exception) {
                }
            }
        }
        return out
    }

    fun count(): Int = prefs?.all?.size ?: 0
}
