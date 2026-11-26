package app.netlify.zaynplay.engine

import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey

/**
 * Phase B: the app-context + key/value shim that replaces
 * CloudStreamApp.Companion for the vendored engine code.
 *
 * The vendored PluginManager / RepositoryManager / DataStore call
 * getKey / setKey / removeKey / getKeyClass / setKeyClass as plain
 * functions; those delegate here, and here they delegate to the vendored
 * DataStore (SharedPreferences "rebuild_preference") using the stored
 * application context. One store for engine keys — EngineStore (the
 * bridge facade) is built on the same DataStore, so there is no second
 * SharedPreferences file for engine state.
 *
 * Also holds the two flags the HTTP stack needs (jsDelivr proxy, DNS
 * mode), synced from EngineStore settings at init and on settingsSet.
 */
object EngineApp {
    private var appContext: Context? = null
    private var store: EngineStore? = null

    /** The application context; null until init() runs in MainActivity. */
    val context: Context?
        get() = appContext

    fun init(context: Context, store: EngineStore) {
        appContext = context.applicationContext
        this.store = store
        syncFlagsFromStore()
    }

    /** Re-read jsdelivrProxy / dns from EngineStore (call after settingsSet). */
    fun syncFlagsFromStore() {
        val s = store ?: return
        jsdelivrProxy = s.getSettingBoolean("jsdelivrProxy", false)
        dnsMode = dnsStringToInt(s.getSettingString("dns", "cloudflare"))
    }

    /** jsDelivr proxy flag, read by RepositoryManager.convertRawGitUrl. */
    var jsdelivrProxy: Boolean = false
        private set

    /** DNS mode int, read by RequestsHelper.buildDefaultClient (0 = system). */
    var dnsMode: Int = 0
        private set

    fun dnsStringToInt(dns: String): Int = when (dns) {
        "google" -> 1
        "cloudflare" -> 2
        "adguard" -> 4
        "dnswatch" -> 5
        "quad9" -> 6
        "dnssb" -> 7
        "canadianshield" -> 8
        else -> 0
    }

    /** Provider languages from the EngineStore "provLang" setting. */
    fun providerLangs(): List<String> {
        val s = store ?: return listOf("English", "Bengali", "Hindi")
        return s.getSettingStringList("provLang", listOf("English", "Bengali", "Hindi"))
    }

    // ---- key/value delegates (mirror CloudStreamApp.Companion) ----

    fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
        return appContext?.getKey(path, valueType)
    }

    fun <T : Any> setKeyClass(path: String, value: T) {
        appContext?.setKey(path, value)
    }

    fun removeKeys(folder: String): Int? {
        return appContext?.removeKeys(folder)
    }

    fun <T> setKey(path: String, value: T) {
        appContext?.setKey(path, value)
    }

    fun <T> setKey(folder: String, path: String, value: T) {
        appContext?.setKey(folder, path, value)
    }

    inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
        return appContext?.getKey(path, defVal)
    }

    inline fun <reified T : Any> getKey(path: String): T? {
        return appContext?.getKey(path)
    }

    inline fun <reified T : Any> getKey(folder: String, path: String): T? {
        return appContext?.getKey(folder, path)
    }

    inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
        return appContext?.getKey(folder, path, defVal)
    }

    fun getKeys(folder: String): List<String>? {
        return appContext?.getKeys(folder)
    }

    fun removeKey(folder: String, path: String) {
        appContext?.removeKey(folder, path)
    }

    fun removeKey(path: String) {
        appContext?.removeKey(path)
    }
}

/**
 * Phase B: filename sanitiser copied from upstream
 * DownloadFileManagement.sanitizeFilename (same RESERVED_CHARS, same
 * behaviour). PluginManager imports it from this package.
 */
fun sanitizeFilename(name: String, removeSpaces: Boolean = false): String {
    val reservedChars = "|\\?*\"<>+[]/'"
    var tempName = name
    for (c in reservedChars) {
        tempName = tempName.replace(c, ' ')
    }
    if (removeSpaces) tempName = tempName.replace(" ", "")
    return tempName.replace("  ", " ").trim(' ')
}
