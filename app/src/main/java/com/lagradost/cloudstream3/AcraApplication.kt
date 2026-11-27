package com.lagradost.cloudstream3

import android.content.Context

/**
 * (v0.2.10) Deprecated alias for CloudStreamApp, vendored because
 * live plugins still call it. Upstream's own AcraApplication.kt says:
 * "Deprecated alias for CloudStreamApp for backwards compatibility
 * with plugins. Use CloudStreamApp instead." The companion delegates
 * every member to CloudStreamApp.Companion, verbatim from upstream
 * (annotations omitted — the dex-visible API is identical).
 *
 * This is LOAD-BREAKING without it: three vietnamese plugins
 * (StremioProvider, ViStreamProvider <clinit>; SyncProvider load)
 * touch AcraApplication.Companion, as do the anicompat provider's
 * account helpers. They call getContext() (app context), setKey()
 * and removeKeys() — all already implemented in the host's
 * CloudStreamApp companion (v0.2.6 shim).
 */
class AcraApplication {
    companion object {
        val context get() = CloudStreamApp.context

        fun removeKeys(folder: String): Int? =
            CloudStreamApp.removeKeys(folder)

        fun <T> setKey(path: String, value: T) =
            CloudStreamApp.setKey(path, value)

        fun <T> setKey(folder: String, path: String, value: T) =
            CloudStreamApp.setKey(folder, path, value)

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? =
            CloudStreamApp.getKey(path, defVal)

        inline fun <reified T : Any> getKey(path: String): T? =
            CloudStreamApp.getKey(path)

        inline fun <reified T : Any> getKey(folder: String, path: String): T? =
            CloudStreamApp.getKey(folder, path)

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? =
            CloudStreamApp.getKey(folder, path, defVal)
    }
}
