package com.lagradost.cloudstream3.ui.settings.extensions

/**
 * (v1.0.2) RepositoryData lives HERE, at the upstream location
 * (upstream ExtensionsViewModel.kt:22) — NOT in plugins.
 *
 * Why: plugins compiled against upstream CloudStream (e.g. MegaPlugin,
 * MegaProvider.cs3) call RepositoryManager.getRepositories() /
 * addRepository() with THIS type and construct
 * `new com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData(...)`.
 * Our host had moved the class to plugins.RepositoryData, so the
 * UI-package type did not exist in the APK at all: Mega's load()
 * coroutine died with NoSuchMethodError/NoClassDefFoundError at its
 * first host call (getRepositories), swallowed silently by launchSafe —
 * the Mega row said Load ✓ while zero repos were ever added.
 * Restoring the upstream location makes those compiled calls resolve
 * exactly (same JVM descriptors). The plugins package keeps a typealias
 * below so all existing host references keep compiling unchanged, and
 * the stored DataStore JSON is field-identical (plain objects, no type
 * tag), so installed repos survive the move.
 */

import kotlinx.serialization.Serializable

@Serializable
data class RepositoryData(
    val iconUrl: String?,
    val name: String,
    val url: String,
    val description: String? = null,
) {
    constructor(
        name: String,
        url: String,
    ) : this(null, name, url, null)

    constructor(
        iconUrl: String?,
        name: String,
        url: String,
    ) : this(iconUrl, name, url, null)
}
