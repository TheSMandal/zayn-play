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
import dalvik.system.PathClassLoader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStreamReader

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

    private val classLoaders: MutableMap<PathClassLoader, BasePlugin> =
        HashMap<PathClassLoader, BasePlugin>()

    var loadedLocalPlugins = false
        private set

    var loadedOnlinePlugins = false
        private set

    private suspend fun maybeLoadPlugin(context: Context, file: File) {
        val name = file.name
        if (file.extension == "zip" || file.extension == "cs3") {
            loadPlugin(
                context,
                file,
                PluginData(name, null, false, file.absolutePath, PLUGIN_VERSION_NOT_SET)
            )
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
            loadPlugin(
                context,
                File(savedData.filePath),
                savedData
            )
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
            loadPlugin(
                context,
                File(pluginData.filePath),
                pluginData
            )
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
    internal suspend fun loadPlugin(context: Context, file: File, data: PluginData): Boolean {
        val fileName = file.nameWithoutExtension
        val filePath = file.absolutePath
        currentlyLoading = fileName
        Log.i(TAG, "Loading plugin: $data")

        return try {
            // In case of Android 14+ then
            try {
                // Set the file as read-only and log if it fails
                if (!file.setReadOnly()) {
                    Log.e(TAG, "Failed to set read-only on plugin file: ${file.name}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to set dex as read-only")
                logError(t)
            }

            val loader = PathClassLoader(filePath, context.classLoader)
            var manifest: BasePlugin.Manifest
            loader.getResourceAsStream("manifest.json").use { stream ->
                if (stream == null) {
                    Log.e(TAG, "Failed to load plugin  $fileName: No manifest found")
                    return false
                }
                InputStreamReader(stream).use { reader ->
                    manifest = parseJson<BasePlugin.Manifest>(reader.readText())
                }
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
                pluginInstance.load(context)
            } else {
                pluginInstance.load()
            }
            // (v0.2.1-g) Apply MainAPI.init() / clone-site overrides to the
            // freshly registered APIs (APIHolder.initAll was never called).
            APIHolder.initAll()
            // (v0.2.1-j) Persist the PluginData ONLY after a successful
            // load. The old code persisted before load() ran, leaving a
            // stored-but-unloaded record when load() threw.
            setPluginData(data.copy(version = version))
            Log.i(TAG, "Loaded plugin ${data.internalName} successfully")
            currentlyLoading = null
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $file: ${Log.getStackTraceString(e)}")
            lastError = "Failed to load plugin $fileName"
            currentlyLoading = null
            false
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
        loadPlugin: Boolean
    ): Boolean {
        val file = getPluginPath(activity, internalName, repositoryUrl)
        return downloadPlugin(activity, pluginUrl, pluginHash, internalName, file, loadPlugin)
    }

    suspend fun downloadPlugin(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        file: File,
        loadPlugin: Boolean,
    ): Boolean {
        try {
            Log.d(TAG, "Downloading plugin: $pluginUrl to ${file.absolutePath}")
            // The plugin file needs to be salted with the repository url hash as to allow multiple repositories with the same internal plugin names
            val newFile = downloadPluginToFile(activity, pluginUrl, file, pluginHash) ?: return false

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
                loadPlugin(
                    activity,
                    newFile,
                    data
                )
            } else {
                setPluginData(data)
                true
            }
        } catch (e: Exception) {
            logError(e)
            return false
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
