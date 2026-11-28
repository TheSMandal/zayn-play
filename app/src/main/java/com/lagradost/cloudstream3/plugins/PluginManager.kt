@file:OptIn(InternalAPI::class)
package com.lagradost.cloudstream3.plugins

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.removePluginMapping
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.AutoDownloadMode
import app.netlify.zaynplay.engine.EngineApp.getKey
import app.netlify.zaynplay.engine.EngineApp.removeKey
import app.netlify.zaynplay.engine.EngineApp.setKey
import app.netlify.zaynplay.engine.EngineLogger
import com.lagradost.cloudstream3.InternalAPI
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainAPI.Companion.settingsForProvider
import app.netlify.zaynplay.engine.EngineEvents.afterPluginsLoadedEvent
import app.netlify.zaynplay.engine.EngineEvents.lastError
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.PROVIDER_STATUS_OK
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.debugPrint
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.RepositoryManager.ONLINE_PLUGINS_FOLDER
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.plugins.RepositoryManager.downloadPluginToFile
import com.lagradost.cloudstream3.plugins.RepositoryManager.getRepoPlugins
import com.lagradost.cloudstream3.plugins.RepositoryManager.sha256
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.UiText
import app.netlify.zaynplay.engine.sanitizeFilename
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.txt
import dalvik.system.InMemoryDexClassLoader
import dalvik.system.PathClassLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.ZipException
import java.util.zip.ZipFile

// Different keys for local and not since local can be removed at any time without app knowing, hence the local are getting rebuilt on every app start
const val PLUGINS_KEY = "PLUGINS_KEY"
const val PLUGINS_KEY_LOCAL = "PLUGINS_KEY_LOCAL"

const val EXTENSIONS_CHANNEL_ID = "app.netlify.zaynplay.extensions"
const val EXTENSIONS_CHANNEL_NAME = "Extensions"
const val EXTENSIONS_CHANNEL_DESCRIPT = "Extension notification channel"

// Data class for internal storage
@Serializable
data class PluginData(
    @JsonProperty("internalName") @SerialName("internalName") val internalName: String,
    @JsonProperty("url") @SerialName("url") val url: String?,
    @JsonProperty("isOnline") @SerialName("isOnline") val isOnline: Boolean,
    @JsonProperty("filePath") @SerialName("filePath") val filePath: String,
    @JsonProperty("version") @SerialName("version") val version: Int,
) {
    @WorkerThread
    fun toSitePlugin(): SitePlugin {
        return SitePlugin(
            this.filePath,
            PROVIDER_STATUS_OK,
            maxOf(1, version),
            1,
            internalName,
            internalName,
            emptyList(),
            File(this.filePath).name,
            null,
            null,
            null,
            null,
            File(this.filePath).length(),
            // No file hash for local plugins. Local plugins have no use for the hash, and it's expensive to compute.
            null
        )
    }
}

// This is used as a placeholder / not set version
const val PLUGIN_VERSION_NOT_SET = Int.MIN_VALUE

// This always updates
const val PLUGIN_VERSION_ALWAYS_UPDATE = -1

object PluginManager {
    // Prevent multiple writes at once
    val lock = Mutex()

    // (v0.2.7) One lock per plugin file, covering BOTH the download
    // and the load of that file. (v0.2.8 NOTE: the v0.2.7 theory that
    // a concurrent load deleting the file caused the empty DexPathList
    // was REFUTED on-device — v0.2.7 genuinely removed delete-on-fail
    // and still failed the same way. The lock is kept anyway: it
    // serialises download+load per file and costs nothing.)
    // Different files still load in parallel (the boot scan's amap
    // takes a different lock per record). Callers must hold this
    // lock around loadPlugin — loadPlugin itself does NOT take it,
    // because downloadPluginDetailed holds it across download+load
    // and Mutex is not re-entrant. Lock order everywhere:
    // pluginFileLocks[file] -> downloadLocks[file] -> lock; nothing
    // acquired under `lock` ever takes pluginFileLocks, so no cycle.
    private val pluginFileLocks = ConcurrentHashMap<String, Mutex>()

    private suspend fun <T> withPluginFileLock(file: File, block: suspend () -> T): T {
        val fileLock = pluginFileLocks.getOrPut(file.absolutePath) { Mutex() }
        return fileLock.withLock { block() }
    }

    // (v0.2.2) Last download / load failure, as a stable code the bridge
    // reports to the UI instead of a generic "network_error". Codes:
    // download_failed | http_<code> | download_empty | hash_mismatch |
    // download_io | dex_writable | load_class_missing | load_failed.
    // Read immediately after downloadPlugin/loadPlugin returns false;
    // a success clears the matching slot.
    @Volatile
    var lastDownloadError: String? = null
        private set

    @Volatile
    var lastLoadError: String? = null
        private set

    // (v0.2.6) FORENSIC DETAIL for the last load failure: the
    // identifying text of the cause, verbatim — for
    // NoClassDefFoundError / ClassNotFoundException this is the
    // missing class's binary name (e.g.
    // "com.lagradost.cloudstream3.CommonActivity"), for anything
    // else "<ExceptionSimpleName>: <message>". A bare code
    // ("load_class_missing") cannot be fixed; the bridge threads
    // this detail through downloadPluginDetailed so the row/toast
    // can name WHAT is missing. Read it in the same call chain as
    // the result (same pattern as lastLoadError); null on success.
    // The full forensics (file size / SHA-256 / magic / plugin class
    // name) go to EngineLogger on the same failure.
    @Volatile
    var lastLoadDetail: String? = null
        private set

    const val TAG = "PluginManager"

    private var hasCreatedNotChanel = false

    /**
     * Store data about the plugin for fetching later
     * */
    private suspend fun setPluginData(data: PluginData) {
        lock.withLock {
            if (data.isOnline) {
                val plugins = getPluginsOnline()
                val newPlugins = plugins.filter { it.filePath != data.filePath } + data
                setKey(PLUGINS_KEY, newPlugins)
            } else {
                val plugins = getPluginsLocal()
                setKey(PLUGINS_KEY_LOCAL, plugins.filter { it.filePath != data.filePath } + data)
            }
        }
    }

    private suspend fun deletePluginData(data: PluginData?) {
        if (data == null) return
        lock.withLock {
            if (data.isOnline) {
                val plugins = getPluginsOnline().filter { it.url != data.url }
                setKey(PLUGINS_KEY, plugins)
            } else {
                val plugins = getPluginsLocal().filter { it.filePath != data.filePath }
                setKey(PLUGINS_KEY_LOCAL, plugins)
            }
        }
    }

    suspend fun deleteRepositoryData(repositoryPath: String) {
        lock.withLock {
            val plugins = getPluginsOnline().filter {
                !it.filePath.contains(repositoryPath)
            }
            val file = File(repositoryPath)
            safe {
                if (file.exists()) file.deleteRecursively()
            }
            setKey(PLUGINS_KEY, plugins)
        }
    }

    /**
     * Deletes all generated oat files which will force Android to recompile the dex extensions.
     * This might fix unrecoverable SIGSEGV exceptions when old oat files are loaded in a new app update.
     */
    fun deleteAllOatFiles(context: Context) {
        File("${context.filesDir}/${ONLINE_PLUGINS_FOLDER}").listFiles()?.forEach { repo ->
            repo.listFiles { file -> file.name == "oat" && file.isDirectory }?.forEach { file ->
                val success = file.deleteRecursively()
                Log.i(TAG, "Deleted oat directory: ${file.absolutePath} Success=$success")
            }
        }
    }


    fun getPluginsOnline(): Array<PluginData> {
        return getKey<Array<PluginData>>(PLUGINS_KEY) ?: emptyArray()
    }

    fun getPluginsLocal(): Array<PluginData> {
        return getKey<Array<PluginData>>(PLUGINS_KEY_LOCAL) ?: emptyArray()
    }

    private val CLOUD_STREAM_FOLDER =
        Environment.getExternalStorageDirectory().absolutePath + "/Zayn Play/"

    private val LOCAL_PLUGINS_PATH = CLOUD_STREAM_FOLDER + "plugins"

    var currentlyLoading: String? = null

    // Maps filepath to plugin
    val plugins: MutableMap<String, BasePlugin> =
        LinkedHashMap<String, BasePlugin>()

    // Maps urls to plugin
    val urlPlugins: MutableMap<String, BasePlugin> =
        LinkedHashMap<String, BasePlugin>()

    private val classLoaders: MutableMap<ClassLoader, BasePlugin> =
        HashMap<ClassLoader, BasePlugin>()

    var loadedLocalPlugins = false
        private set

    var loadedOnlinePlugins = false
        private set

    private suspend fun maybeLoadPlugin(context: Context, file: File) {
        val name = file.name
        if (file.extension == "zip" || file.extension == "cs3") {
            withPluginFileLock(file) {
                loadPlugin(
                    context,
                    file,
                    PluginData(name, null, false, file.absolutePath, PLUGIN_VERSION_NOT_SET)
                )
            }
        } else {
            Log.i(TAG, "Skipping invalid plugin file: $file")
        }
    }


    // Helper class for updateAllOnlinePluginsAndLoadThem
    data class OnlinePluginData(
        val savedData: PluginData,
        val onlineData: PluginWrapper,
    ) {
        val isOutdated =
            onlineData.plugin.version > savedData.version || onlineData.plugin.version == PLUGIN_VERSION_ALWAYS_UPDATE
        val isDisabled = onlineData.plugin.status == PROVIDER_STATUS_DOWN

        fun validOnlineData(context: Context): Boolean {
            return getPluginPath(
                context,
                savedData.internalName,
                onlineData.repositoryData.url
            ).absolutePath == savedData.filePath
        }
    }

    // var allCurrentOutDatedPlugins: Set<OnlinePluginData> = emptySet()

    suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean {
        return (getPluginsOnline().firstOrNull {
            // Most of the time the provider ends with Provider which isn't part of the api name
            it.internalName.replace("provider", "", ignoreCase = true) == apiName
        }
            ?: getPluginsLocal().firstOrNull {
                it.internalName.replace("provider", "", ignoreCase = true) == apiName
            })?.let { savedData ->
            // OnlinePluginData(savedData, onlineData)
            withPluginFileLock(File(savedData.filePath)) {
                loadPlugin(
                    context,
                    File(savedData.filePath),
                    savedData
                ).first
            }
        } ?: false
    }

    @Throws
    private fun assertNonRecursiveCallstack() {
        if (Thread.currentThread().stackTrace.any { it.methodName == "loadPlugin" }) {
            throw Error("You tried to call a function that will recursively call loadPlugin, this will cause crashes or memory leaks. Do not do this, there is better ways to implement the feature than reloading plugins. Are you sure you read the compile error or docs?")
        }
    }

    /**
     * Use updateAllOnlinePluginsAndLoadThem
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context: Context) {
        assertNonRecursiveCallstack()

        // Load all plugins as fast as possible!
        (getPluginsOnline()).toList().amap { pluginData ->
            withPluginFileLock(File(pluginData.filePath)) {
                loadPlugin(
                    context,
                    File(pluginData.filePath),
                    pluginData
                )
            }
        }
    }

    /**
     * @param forceReload see afterPluginsLoadedEvent, basically a way to load all local plugins
     * and reload all pages even if they are previously valid
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(context: Context, forceReload: Boolean) {
        assertNonRecursiveCallstack()

        val dir = File(LOCAL_PLUGINS_PATH)

        if (!dir.exists()) {
            val res = dir.mkdirs()
            if (!res) {
                Log.w(TAG, "Failed to create local directories")
                // We have tried to load local plugins, but exit early.
                // This needs to be true to prevent the downloader waiting for plugins.
                loadedLocalPlugins = true
                return
            }
        }

        val sortedPlugins = dir.listFiles()
        // Always sort plugins alphabetically for reproducible results

        Log.d(TAG, "Files in '${LOCAL_PLUGINS_PATH}' folder: ${sortedPlugins?.size}")

        // Use app-specific external files directory and copy the file there.
        // We have to do this because on Android 14+, it otherwise gives SecurityException
        // due to dex files and setReadOnly seems to have no effect unless it it here.
        val pluginDirectory = File(context.getExternalFilesDir(null), "plugins")
        if (!pluginDirectory.exists()) {
            pluginDirectory.mkdirs() // Ensure the plugins directory exists
        }

        // Make sure all local plugins are fully refreshed.
        removeKey(PLUGINS_KEY_LOCAL)

        sortedPlugins?.sortedBy { it.name }?.amap { file ->
            try {
                val destinationFile = File(pluginDirectory, file.name)

                // Only copy the file if the destination file doesn't exist or if it
                // has been modified (check file length and modification time).
                if (!destinationFile.exists() ||
                    destinationFile.length() != file.length() ||
                    destinationFile.lastModified() != file.lastModified()
                ) {

                    // Copy the file to the app-specific plugin directory
                    file.copyTo(destinationFile, overwrite = true)

                    // After copying, set the destination file's modification time
                    // to match the source file. We do this for performance so that we
                    // can check the modification time and not make redundant writes.
                    destinationFile.setLastModified(file.lastModified())
                }

                // Load the plugin after it has been copied
                maybeLoadPlugin(context, destinationFile)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to copy the file")
                logError(t)
            }
        }

        loadedLocalPlugins = true
        afterPluginsLoadedEvent.invoke(forceReload)
    }

    /** @return true if safe mode is enabled in any possible way. */
    fun isSafeMode(): Boolean {
        return checkSafeModeFile() || lastError != null
    }

    /**
     * This can be used to override any extension loading to fix crashes!
     * @return true if safe mode file is present
     **/
    fun checkSafeModeFile(): Boolean {
        return safe {
            val folder = File(CLOUD_STREAM_FOLDER)
            if (!folder.exists()) return@safe false
            val files = folder.listFiles { _, name ->
                name.equals("safe", ignoreCase = true)
            }
            files?.any()
        } ?: false
    }

    /**
     * @return True if successful, false if not
     * */
    // (v0.2.5) First 4 bytes of a file as hex, for forensics
    // ("504b0304" = PK\x03\x04, a real zip). "empty" when the file has
    // no bytes, "?" when it cannot be read at all.
    private fun fileMagicHex(file: File): String {
        return try {
            val bytes = ByteArray(4)
            val n = file.inputStream().use { it.read(bytes) }
            if (n <= 0) "empty"
            else bytes.take(n).joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            "?"
        }
    }

    // (v0.2.5) Read a plugin's manifest DIRECTLY from its .cs3 zip with
    // java.util.zip.ZipFile, instead of
    // PathClassLoader.getResourceAsStream("manifest.json").
    //
    // Proven cause of the v0.2.4 "file is missing its manifest" failure:
    // the real .cs3 files ARE valid zips containing manifest.json at the
    // root (verified byte-for-byte live, SHA matches the repo manifest,
    // Range-resume reconstruction verified), and the app's download
    // returns a SHA-verified file — yet the classloader lookup returned
    // null on the phone for every provider. That lookup goes through
    // DexPathList / ClassPathURLStreamHandler and is device/ART
    // dependent; a direct ZipFile read is deterministic and does not
    // depend on it. The loader in loadPlugin below only loads the
    // plugin class — on API 26+ from the dex bytes in memory (see
    // loadPlugin), on older APIs from the file path.
    //
    // Returns null (and sets lastLoadError/lastError + logs forensics:
    // size, SHA-256, magic bytes, zip entries) when the file is missing,
    // is not a zip, has no manifest.json, or the manifest JSON does not
    // parse. (v0.2.7) The file is LEFT in place on failure — it is
    // never deleted here. The v0.2.5/v0.2.6 deletes were the proven
    // cause of the empty DexPathList: a concurrent load could delete
    // the file between another load's manifest read and its
    // PathClassLoader construction (upstream deletes nothing on load
    // failure). The next test re-downloads fresh anyway.
    private fun readPluginManifest(file: File, fileName: String): BasePlugin.Manifest? {
        if (!file.exists()) {
            lastLoadError = "file_missing"
            lastError = "Failed to load plugin $fileName"
            EngineLogger.log("load", "file missing: ${file.name}")
            Log.e(TAG, "Failed to load plugin $fileName: file missing")
            return null
        }
        val fileSha = try { sha256(file) } catch (_: Throwable) { "?" }
        val zip: ZipFile = try {
            ZipFile(file)
        } catch (e: ZipException) {
            val magic = fileMagicHex(file)
            lastLoadError = "bad_zip"
            lastError = "Failed to load plugin $fileName"
            EngineLogger.log(
                "load",
                "bad zip ${file.name}: size=${file.length()} sha=$fileSha magic=$magic (${e.message})"
            )
            Log.e(TAG, "Failed to load plugin $fileName: not a zip (magic=$magic)", e)
            return null
        } catch (e: IOException) {
            val magic = fileMagicHex(file)
            lastLoadError = "bad_zip"
            lastError = "Failed to load plugin $fileName"
            EngineLogger.log(
                "load",
                "bad zip ${file.name}: size=${file.length()} sha=$fileSha magic=$magic (${e.javaClass.simpleName} ${e.message})"
            )
            Log.e(TAG, "Failed to load plugin $fileName: cannot open zip (magic=$magic)", e)
            return null
        }
        return zip.use { z ->
            val entry = z.getEntry("manifest.json")
            if (entry == null) {
                val entries = z.entries().toList().joinToString(",") { it.name }
                lastLoadError = "load_no_manifest"
                lastError = "Failed to load plugin $fileName"
                EngineLogger.log(
                    "load",
                    "no manifest in ${file.name}: size=${file.length()} sha=$fileSha entries=[$entries]"
                )
                Log.e(TAG, "Failed to load plugin $fileName: No manifest found (entries: $entries)")
                null
            } else {
                try {
                    z.getInputStream(entry).use { stream ->
                        InputStreamReader(stream).use { reader ->
                            parseJson<BasePlugin.Manifest>(reader.readText())
                        }
                    }
                } catch (e: Exception) {
                    lastLoadError = "bad_manifest"
                    lastError = "Failed to load plugin $fileName"
                    EngineLogger.log("load", "bad manifest in ${file.name}: ${e.javaClass.simpleName} ${e.message}")
                    Log.e(TAG, "Failed to load plugin $fileName: bad manifest", e)
                    // (v0.2.7) A manifest that does not parse can never
                    // load — but the file is NOT deleted here (see the
                    // header comment): deleting it raced concurrent
                    // loads of the same file.
                    null
                }
            }
        }
    }

    // (v0.2.8) Read classes.dex bytes out of the .cs3 with ZipFile —
    // the same mechanism as readPluginManifest, already proven to
    // work on his phone (the manifest read succeeds there). Returns
    // null (setting lastLoadError / lastLoadDetail + logging
    // forensics) when the entry is missing or unreadable.
    private fun readDexBytes(file: File, fileName: String): ByteArray? {
        return try {
            ZipFile(file).use { z ->
                val entry = z.getEntry("classes.dex")
                if (entry == null) {
                    lastLoadError = "load_no_dex"
                    lastLoadDetail = "classes.dex missing from ${file.name}"
                    lastError = "Failed to load plugin $fileName"
                    EngineLogger.log("load", "no classes.dex in ${file.name}")
                    Log.e(TAG, "Failed to load plugin $fileName: no classes.dex entry")
                    null
                } else {
                    z.getInputStream(entry).use { it.readBytes() }
                }
            }
        } catch (e: Throwable) {
            lastLoadError = "load_failed"
            lastLoadDetail = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                .replace('\n', ' ').take(200)
            lastError = "Failed to load plugin $fileName"
            EngineLogger.log("load", "read classes.dex failed ${file.name}: ${e.javaClass.simpleName} ${e.message}")
            Log.e(TAG, "Failed to read classes.dex from $fileName", e)
            null
        }
    }

    // (v0.2.7) Returns (ok, code, detail): the load result together
    // with the exact failure code and forensic detail, threaded
    // through the return value instead of the process-wide
    // lastLoadError/lastLoadDetail slots — those slots are shared by
    // every concurrent load, so a different plugin's load could
    // overwrite them between loadPlugin returning and the caller
    // reading them. Callers MUST hold withPluginFileLock(file) around
    // this call (see the lock above); loadPlugin does not take it
    // itself. The slots are still written (forensics/logging) but no
    // caller reads them for a result any more.
    internal suspend fun loadPlugin(context: Context, file: File, data: PluginData): Triple<Boolean, String?, String?> {
        val fileName = file.nameWithoutExtension
        // A stale detail from an earlier load must never leak into
        // this call's result (manifest failures below set a code but
        // no detail of their own).
        lastLoadDetail = null
        val filePath = file.absolutePath
        currentlyLoading = fileName
        Log.i(TAG, "Loading plugin: $data")
        // (v0.2.6) Declared outside the try so the catch can name
        // the plugin class in the forensic log line.
        var pluginClassName: String? = null
        // (v0.2.9) Fix G: set when the old version is evicted below, so
        // the catch knows this load was a RELOAD (see there).
        var evictedOldVersion = false

        return try {
            // In case of Android 14+ then: ART refuses a WRITABLE dex
            // file ("Writable dex file ... is not allowed",
            // SecurityException). The old code only LOGGED a failed
            // setReadOnly() and loaded anyway, so every plugin failed
            // with no diagnosable cause on a device where the chmod did
            // not take. (v0.2.2) Verify the file is really read-only
            // now; if setReadOnly() did not take, retry with an explicit
            // chmod 0400; if it is STILL writable, fail here with the
            // true code (dex_writable) instead of letting ART throw.
            try {
                if (!file.setReadOnly() || file.canWrite()) {
                    try {
                        android.system.Os.chmod(file.absolutePath, 256 /* 0400 */)
                    } catch (_: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to set dex as read-only")
                logError(t)
            }
            if (file.canWrite()) {
                lastLoadError = "dex_writable"
                lastError = "Failed to load plugin $fileName"
                EngineLogger.log("load", "dex still writable, refusing load: ${file.name}")
                Log.e(TAG, "Refusing to load writable dex file: ${file.name}")
                currentlyLoading = null
                return Triple(false, "dex_writable", null)
            }

            // (v0.2.5) The manifest is read directly from the zip (see
            // readPluginManifest); the loader below (v0.2.8: in-memory
            // dex on API 26+, PathClassLoader below) only loads the
            // plugin class.
            val manifest = readPluginManifest(file, fileName) ?: run {
                // (v0.2.7) A file that fails the manifest read can
                // never load — but it is NOT deleted (see
                // readPluginManifest): deleting it here raced
                // concurrent loads of the same file and produced the
                // empty DexPathList. The next test re-downloads
                // fresh; the startup scan just fails this record.
                currentlyLoading = null
                return Triple(false, lastLoadError, null)
            }
            pluginClassName = manifest.pluginClassName

            // (v0.2.7) The file was verified by readPluginManifest
            // above, but another actor (a repo removal, an explicit
            // uninstall) could have removed it since. Never build a
            // loader on a missing path: PathClassLoader silently
            // yields an empty DexPathList and loadClass then throws
            // a misleading ClassNotFoundException. Fail here with
            // the true code instead.
            if (!file.exists() || file.length() == 0L) {
                lastLoadError = "file_missing"
                lastError = "Failed to load plugin $fileName"
                EngineLogger.log("load", "file vanished before loader: ${file.name}")
                Log.e(TAG, "Failed to load plugin $fileName: file vanished before loader")
                currentlyLoading = null
                return Triple(false, "file_missing", null)
            }

            // (v0.2.8) IN-MEMORY dex load. Proven on his phone: v0.2.7
            // still failed with an empty DexPathList even though the
            // file exists, is non-empty, hash-valid and ZipFile opens
            // it fine (the manifest read succeeds) — so ART's
            // DexPathList / DexFile.loadDex file-opening step drops
            // the element below our code. PathClassLoader silently
            // drops unopenable path elements, so bypass that step
            // entirely: read classes.dex bytes with ZipFile (the
            // same mechanism already proven to work on his phone)
            // and hand them to InMemoryDexClassLoader, which never
            // opens a file path. The .cs3 contains only manifest.json
            // + classes.dex (no native libs), so there is no .so
            // concern, and no oat files are written. API 26+ only —
            // below 26 fall back to the PathClassLoader path. The
            // exact ART-level step that fails is still unproven;
            // this is a bypass, not a proven root-cause fix.
            val loader: ClassLoader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val dexBytes = readDexBytes(file, fileName) ?: run {
                    currentlyLoading = null
                    return Triple(false, lastLoadError, lastLoadDetail)
                }
                InMemoryDexClassLoader(ByteBuffer.wrap(dexBytes), context.classLoader)
            } else {
                PathClassLoader(filePath, context.classLoader)
            }

            val name: String = manifest.name ?: "NO NAME".also {
                Log.d(TAG, "No manifest name for ${data.internalName}")
            }
            val version: Int = manifest.version ?: PLUGIN_VERSION_NOT_SET.also {
                Log.d(TAG, "No manifest version for ${data.internalName}")
            }

            @Suppress("UNCHECKED_CAST")
            val pluginClass: Class<*> =
                loader.loadClass(manifest.pluginClassName) as Class<out BasePlugin?>
            val pluginInstance: BasePlugin =
                pluginClass.getDeclaredConstructor().newInstance() as BasePlugin

            // (v0.2.1-k) The old version is unloaded ONLY now — after the
            // new plugin has been constructed from the new file. A new
            // plugin that fails to instantiate (bad dex / manifest / class)
            // never evicts the working old one. (Full "unload after the new
            // load() succeeds" is impossible with same-path registration:
            // old and new share one sourcePlugin path, so unloading the old
            // after the new load() would also remove the new APIs. The file
            // on disk is already the new version at this point, so the old
            // version is unrecoverable regardless.)
            if (plugins.containsKey(filePath)) {
                Log.i(TAG, "Plugin $name already loaded, reloading from new file")
                unloadPlugin(filePath)
                evictedOldVersion = true
            }

            pluginInstance.filename = file.absolutePath
            if (manifest.requiresResources) {
                Log.d(TAG, "Loading resources for ${data.internalName}")
                // based on https://stackoverflow.com/questions/7483568/dynamic-resource-loading-from-other-apk
                val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                val addAssetPath =
                    AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                addAssetPath.invoke(assets, file.absolutePath)

                @Suppress("DEPRECATION")
                (pluginInstance as? Plugin)?.resources = Resources(
                    assets,
                    context.resources.displayMetrics,
                    context.resources.configuration
                )
            }
            synchronized(plugins) {
                plugins[filePath] = pluginInstance
            }
            synchronized(classLoaders) {
                classLoaders[loader] = pluginInstance
            }
            synchronized(urlPlugins) {
                urlPlugins[data.url ?: filePath] = pluginInstance
            }
            if (pluginInstance is Plugin) {
                // (v1.0.21) Donation suppression, prong 1 of 2 — starve
                // the Activity: plugin load() gets the application
                // context, never the Activity. Proven from the plugin
                // dexes (CastleTvProvider.cs3, 2026-11-28): the CNC
                // donation manager stores its dialog Activity ONLY via
                // DonationManager.setActivity(context) in load(), as a
                // safe `as? AppCompatActivity` cast into a WeakReference;
                // with a non-Activity context the cast yields null, the
                // reference is never set, and checkAndShow() returns at
                // once (no dialog, no crash, no stats fetch). The same
                // load() call is the funnel for every load path
                // (loadAllOnline/Local, loadSinglePlugin), so starving
                // it here covers all of them; MainActivity keeps passing
                // the Activity (its loadAll* calls also need it for
                // getExternalFilesDir), and loadPlugin derives the
                // application context itself.
                // Fallback for plugins that NEED the Activity: a sweep
                // of all 834 live .cs3 dexes found 30 plugins whose
                // load() hard-casts the context (`context as
                // AppCompatActivity`, first statement or later — e.g.
                // zzikozz WiflixPlugin, 9 CNC plugins); those throw
                // ClassCastException on the application context and
                // would otherwise fail to load (load_failed). On exactly
                // that exception — and no other — drop whatever the
                // failed attempt registered (same three blocks
                // unloadPlugin uses, plus VideoClickActions, so the
                // retry cannot double-register a MainAPI) and retry
                // once with the original context. A second throw falls
                // through to the catch below as load_failed, as today.
                // Plugins that safe-cast (`as?`, 51 dexes) load fine
                // with a null stored activity — their settings screen
                // null-checks it (verified: PlavioHD, CineVood).
                try {
                    pluginInstance.load(context.applicationContext)
                } catch (e: ClassCastException) {
                    APIHolder.apis.filter { api -> api.sourcePlugin == filePath }.forEach {
                        removePluginMapping(it)
                    }
                    APIHolder.allProviders.withLock {
                        APIHolder.allProviders.removeAll { provider -> provider.sourcePlugin == filePath }
                    }
                    extractorApis.withLock {
                        extractorApis.removeAll { provider -> provider.sourcePlugin == filePath }
                    }
                    VideoClickActionHolder.allVideoClickActions.withLock {
                        VideoClickActionHolder.allVideoClickActions.removeAll { action -> action.sourcePlugin == filePath }
                    }
                    EngineLogger.log(
                        "load",
                        "plugin ${data.internalName} hard-casts load context to Activity, retrying load() with it"
                    )
                    pluginInstance.load(context)
                }
            } else {
                pluginInstance.load()
            }
            // (v0.2.6) NOTE: the catch below also covers this load()
            // call, not just loadClass(): a NoClassDefFoundError
            // thrown while the plugin's own load() runs (e.g. its
            // provider class resolves a missing host type during
            // verification) lands here as load_class_missing with
            // the missing class in lastLoadDetail.
            // (v0.2.1-g) Apply MainAPI.init() / clone-site overrides to the
            // freshly registered APIs (APIHolder.initAll was never called).
            APIHolder.initAll()
            // (v0.2.1-j) Persist the PluginData ONLY after a successful
            // load. The old code persisted before load() ran, leaving a
            // stored-but-unloaded record when load() threw.
            setPluginData(data.copy(version = version))
            Log.i(TAG, "Loaded plugin ${data.internalName} successfully")
            EngineLogger.log("load", "loaded ${data.internalName} v$version")
            lastLoadError = null
            lastLoadDetail = null
            currentlyLoading = null
            Triple(true, null, null)
        } catch (e: Throwable) {
            // (v0.2.2) Map the failure to a stable code so the UI can say
            // WHY the plugin did not load, instead of a silent false.
            lastLoadError = when (e) {
                is SecurityException -> "dex_writable"
                is ClassNotFoundException -> "load_class_missing"
                is NoClassDefFoundError -> "load_class_missing"
                else -> "load_failed"
            }
            // (v0.2.6) FORENSIC DETAIL: for the two class-missing
            // failures the exception message IS the missing class's
            // binary name (NoClassDefFoundError) or the class that
            // could not be found (ClassNotFoundException) — that
            // name is what the next screenshot must show. For any
            // other failure keep "<SimpleName>: <message>" (capped,
            // single-line) so the row/toast stays readable. The
            // EngineLogger line carries the full forensics: file
            // size / SHA-256 / magic / plugin class name.
            lastLoadDetail = when (e) {
                is ClassNotFoundException,
                is NoClassDefFoundError -> e.message?.takeIf { it.isNotBlank() }
                    ?: pluginClassName
                    ?: e.javaClass.simpleName
                else -> "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                    .replace('\n', ' ').take(200)
            }
            val fileSha = try { sha256(file) } catch (_: Throwable) { "?" }
            EngineLogger.log(
                "load",
                "load failed ${data.internalName}: $lastLoadError detail=${lastLoadDetail} " +
                    "pluginClass=$pluginClassName size=${file.length()} sha=$fileSha magic=${fileMagicHex(file)}"
            )
            Log.e(TAG, "Failed to load $file: ${Log.getStackTraceString(e)}")
            lastError = "Failed to load plugin $fileName"
            // (v0.2.7) The file is NOT deleted on failure any more
            // (see readPluginManifest): deleting it raced concurrent
            // loads of the same file and produced the empty
            // DexPathList. The next test re-downloads fresh.
            // (v0.2.9) Fix G: a failed reload used to leave a STALE
            // PluginData record: the stored record still points at this
            // file, but the file on disk is now the NEW version that just
            // failed to load, and the old version was already evicted
            // (and its bytes overwritten on disk by the download). The
            // boot scan would retry the broken file forever while the UI
            // showed the provider as installed. The previous file cannot
            // be re-loaded (its bytes are gone), so delete the stale
            // record when the old version was evicted, or when the
            // failure proves the file itself can never load
            // (load_class_missing / dex_writable). A merely transient
            // load() failure keeps its record for the next retry.
            if (evictedOldVersion || lastLoadError == "load_class_missing" || lastLoadError == "dex_writable") {
                try {
                    deletePluginData(data)
                    EngineLogger.log(
                        "load",
                        "deleted stale record for ${data.internalName} after failed load ($lastLoadError)"
                    )
                } catch (_: Throwable) {
                }
            }
            currentlyLoading = null
            Triple(false, lastLoadError, lastLoadDetail)
        }
    }

    fun unloadPlugin(absolutePath: String) {
        Log.i(TAG, "Unloading plugin: $absolutePath")
        val plugin = plugins[absolutePath]
        if (plugin == null) {
            Log.w(TAG, "Couldn't find plugin $absolutePath")
            return
        }

        try {
            plugin.beforeUnload()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to run beforeUnload $absolutePath: ${Log.getStackTraceString(e)}")
        }

        // remove all registered apis
        APIHolder.apis.filter { api -> api.sourcePlugin == plugin.filename }.forEach {
            removePluginMapping(it)
        }

        APIHolder.allProviders.withLock {
            APIHolder.allProviders.removeAll { provider -> provider.sourcePlugin == plugin.filename }
        }

        extractorApis.withLock {
            extractorApis.removeAll { provider -> provider.sourcePlugin == plugin.filename }
        }

        VideoClickActionHolder.allVideoClickActions.withLock {
            VideoClickActionHolder.allVideoClickActions.removeAll { action -> action.sourcePlugin == plugin.filename }
        }

        synchronized(classLoaders) {
            classLoaders.values.removeIf { v -> v == plugin }
        }

        synchronized(plugins) {
            plugins.remove(absolutePath)
        }

        synchronized(urlPlugins) {
            urlPlugins.values.removeIf { v -> v == plugin }
        }
    }

    /**
     * Spits out a unique and safe filename based on name.
     * Used for repo folders (using repo url) and plugin file names (using internalName)
     * */
    fun getPluginSanitizedFileName(name: String): String {
        return sanitizeFilename(
            name,
            true
        ) + "." + name.hashCode()
    }

    /**
     * This should not be changed as it is used to also detect if a plugin is installed!
     **/
    fun getPluginPath(
        context: Context,
        internalName: String,
        repositoryUrl: String
    ): File {
        val folderName = getPluginSanitizedFileName(repositoryUrl) // Guaranteed unique
        val fileName = getPluginSanitizedFileName(internalName)
        return File("${context.filesDir}/${ONLINE_PLUGINS_FOLDER}/${folderName}/$fileName.cs3")
    }

    suspend fun downloadPlugin(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        repositoryUrl: String,
        loadPlugin: Boolean,
        expectedFileSize: Long? = null,
    ): Boolean {
        return downloadPluginDetailed(
            activity, pluginUrl, pluginHash, internalName, repositoryUrl, loadPlugin, expectedFileSize
        ).first
    }

    /**
     * (v0.2.4) Typed download+load: the success flag together with the
     * exact failure code (null on success), so callers use the code from
     * this call instead of reading the process-wide lastDownloadError /
     * lastLoadError slots after the fact. Same codes downloadPlugin
     * already set on those slots (http_<code> | timeout |
     * download_failed | download_empty | hash_mismatch | download_io |
     * dex_writable | load_class_missing | load_no_manifest |
     * load_failed). The Boolean downloadPlugin overloads above delegate
     * here, so behaviour for existing callers is unchanged.
     * (v0.2.6) Now a Triple: the third value is the forensic detail
     * (null on success) — for load_class_missing the missing class
     * name, for download errors the typed detail. expectedFileSize
     * is the repo manifest's SitePlugin.fileSize, checked by the
     * downloader when present (see downloadPluginToFile).
     */
    suspend fun downloadPluginDetailed(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        repositoryUrl: String,
        loadPlugin: Boolean,
        expectedFileSize: Long? = null,
    ): Triple<Boolean, String?, String?> {
        val file = getPluginPath(activity, internalName, repositoryUrl)
        return downloadPluginDetailed(activity, pluginUrl, pluginHash, internalName, file, loadPlugin, expectedFileSize)
    }

    suspend fun downloadPlugin(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        file: File,
        loadPlugin: Boolean,
        expectedFileSize: Long? = null,
    ): Boolean {
        return downloadPluginDetailed(
            activity, pluginUrl, pluginHash, internalName, file, loadPlugin, expectedFileSize
        ).first
    }

    suspend fun downloadPluginDetailed(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        file: File,
        loadPlugin: Boolean,
        expectedFileSize: Long? = null,
    ): Triple<Boolean, String?, String?> {
        // (v0.2.7) Hold the per-file lock across the whole
        // download+load: no other load of this file (boot scan,
        // health probe, another test) can interleave, and no load
        // can delete the file out from under another load.
        return withPluginFileLock(file) {
            downloadPluginDetailedLocked(activity, pluginUrl, pluginHash, internalName, file, loadPlugin, expectedFileSize)
        }
    }

    private suspend fun downloadPluginDetailedLocked(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        file: File,
        loadPlugin: Boolean,
        expectedFileSize: Long? = null,
    ): Triple<Boolean, String?, String?> {
        try {
            Log.d(TAG, "Downloading plugin: $pluginUrl to ${file.absolutePath}")
            // The plugin file needs to be salted with the repository url hash as to allow multiple repositories with the same internal plugin names
            // (v0.2.2) Typed result: the exact failure code is kept in
            // lastDownloadError instead of collapsing to false.
            val downloaded = downloadPluginToFile(activity, pluginUrl, file, pluginHash, expectedFileSize)
            val newFile = when (downloaded) {
                is RepositoryManager.PluginDownloadResult.Ok -> downloaded.file
                is RepositoryManager.PluginDownloadResult.Err -> {
                    lastDownloadError = downloaded.code
                    EngineLogger.log("download", "downloadPlugin failed $internalName: ${downloaded.code} — ${downloaded.detail}")
                    return Triple(false, downloaded.code, downloaded.detail)
                }
            }
            lastDownloadError = null
            // (v0.2.7) deleteAllOatFiles used to run here, between
            // the download and the load — an extra filesystem
            // mutation on the plugin tree inside the window, which
            // upstream never does (it only runs on app-version
            // change). ART already invalidates a stale oat when the
            // dex changes, so the call is removed from this path.

            val data = PluginData(
                internalName,
                pluginUrl,
                true,
                newFile.absolutePath,
                PLUGIN_VERSION_NOT_SET
            )

            return if (loadPlugin) {
                // (v0.2.1-k) Do NOT unload the old version here: loadPlugin
                // unloads it itself, only after the new plugin has been
                // constructed from the new file (see loadPlugin).
                // (v0.2.7) loadPlugin returns its own (ok, code,
                // detail) — the process-wide slots are no longer
                // read for a result (a concurrent load of another
                // plugin could overwrite them).
                val (ok, loadCode, loadDetail) = loadPlugin(
                    activity,
                    newFile,
                    data
                )
                Triple(ok, if (ok) null else (loadCode ?: "load_failed"), if (ok) null else loadDetail)
            } else {
                setPluginData(data)
                Triple(true, null, null)
            }
        } catch (e: CancellationException) {
            // (v0.2.4) Never launder a cancellation into
            // "download_failed": a withTimeout kill must propagate as
            // TimeoutCancellationException to the caller.
            throw e
        } catch (e: Exception) {
            lastDownloadError = "download_failed"
            EngineLogger.log("download", "downloadPlugin threw $internalName: ${e.javaClass.simpleName} ${e.message}")
            logError(e)
            return Triple(false, "download_failed", "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    suspend fun deletePlugin(file: File): Boolean {
        val list =
            (getPluginsLocal() + getPluginsOnline()).filter { it.filePath == file.absolutePath }

        return try {
            if (File(file.absolutePath).delete()) {
                unloadPlugin(file.absolutePath)
                list.forEach { deletePluginData(it) }
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    private fun Context.createNotificationChannel() {
        hasCreatedNotChanel = true
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = EXTENSIONS_CHANNEL_NAME
            val descriptionText =
                EXTENSIONS_CHANNEL_DESCRIPT
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(EXTENSIONS_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        context: Context,
        uitext: UiText,
        extensions: List<String>
    ): Notification? {
        try {

            if (extensions.isEmpty()) return null

            val content = extensions.joinToString(", ")
//        main { // DON'T WANT TO SLOW IT DOWN
            val builder = NotificationCompat.Builder(context, EXTENSIONS_CHANNEL_ID)
                .setAutoCancel(false)
                .setColorized(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setColor(0xFFE20914.toInt())
                .setContentTitle(uitext.asString(context))
                //.setContentTitle(context.getString(title, extensionNames.size))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(content)
                )
                .setContentText(content)

            if (!hasCreatedNotChanel) {
                context.createNotificationChannel()
            }

            val notification = builder.build()
            // notificationId is a unique int for each notification that you must define
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context)
                    .notify((System.currentTimeMillis() / 1000).toInt(), notification)
            }
            return notification
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }
}
