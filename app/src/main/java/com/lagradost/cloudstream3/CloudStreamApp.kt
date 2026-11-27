package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import app.netlify.zaynplay.engine.EngineApp
import java.lang.ref.WeakReference

/**
 * (v0.2.6) Host-compatibility shim for the CloudStream host classes
 * that every Phisher plugin expects but the vendored engine dropped.
 *
 * PROVEN CAUSE (dex-level, 88 live .cs3 files, 2026-11-27): the
 * v0.2.5 APK dex has NO CloudStreamApp / CommonActivity, while all 88
 * plugins reference them (transitive ART verification from the plugin
 * class reaches them — e.g. AnichiPlugin.load() touches AnichiUtils,
 * whose getAppContext() reads CommonActivity.INSTANCE; every other
 * plugin reaches DonationManager, which reads CommonActivity.INSTANCE
 * and CloudStreamApp.Companion.getContext()). Resolving those types
 * during plugin-class verification throws NoClassDefFoundError, which
 * PluginManager maps to load_class_missing — so EVERY provider failed
 * to load on the phone even though the .cs3 files are valid.
 *
 * This mirrors upstream's public surface only as far as plugins use
 * it (member surface proven from the 88 dexes: Companion.getContext()
 * and Companion.setKey(String, Object) / setKey(String, String,
 * Object)). The context itself comes from EngineApp (set by
 * MainActivity via EngineApp.init at app start); this class is NOT
 * registered as the <application> in the manifest, so attachBaseContext
 * below only fills the local reference if it ever is.
 *
 * What is deliberately NOT restored here: the CloudStream UI
 * (MainActivity UI, HomeViewModel, player, settings screens) and the
 * real Cloudflare bypass (see CloudflareKiller stub). The sync stack
 * (SyncAPI/AccountManager/AuthRepo/SyncRepo) IS minimally restored
 * since v0.2.10 — no account, no login; authUser() is null and
 * library() is empty — because StreamPlay/TorraStream construct it.
 * Plugins that need the rest still fail — but the forensic detail
 * names the exact missing class.
 */
class CloudStreamApp : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        _context = WeakReference(base)
    }

    companion object {
        private var _context: WeakReference<Context>? = null

        /** The application context plugins expect. */
        var context: Context?
            get() = _context?.get() ?: EngineApp.context
            private set(value) {
                _context = WeakReference(value)
            }

        /** Use to get Activity from Context. */
        tailrec fun Context.getActivity(): Activity? {
            return when (this) {
                is Activity -> this
                is ContextWrapper -> baseContext.getActivity()
                else -> null
            }
        }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            return EngineApp.getKeyClass(path, valueType)
        }

        fun <T : Any> setKeyClass(path: String, value: T) {
            EngineApp.setKeyClass(path, value)
        }

        fun removeKeys(folder: String): Int? {
            return EngineApp.removeKeys(folder)
        }

        fun <T> setKey(path: String, value: T) {
            EngineApp.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            EngineApp.setKey(folder, path, value)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
            return EngineApp.getKey(path, defVal)
        }

        inline fun <reified T : Any> getKey(path: String): T? {
            return EngineApp.getKey(path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String): T? {
            return EngineApp.getKey(folder, path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
            return EngineApp.getKey(folder, path, defVal)
        }

        fun getKeys(folder: String): List<String>? {
            return EngineApp.getKeys(folder)
        }

        fun removeKey(folder: String, path: String) {
            EngineApp.removeKey(folder, path)
        }

        fun removeKey(path: String) {
            EngineApp.removeKey(path)
        }
    }
}
