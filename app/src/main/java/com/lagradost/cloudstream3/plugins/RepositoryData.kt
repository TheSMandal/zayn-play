package com.lagradost.cloudstream3.plugins

/**
 * Phase B: RepositoryData + REPOSITORIES_KEY, copied verbatim from upstream
 * ExtensionsViewModel.kt:22-42 (the class lived in the UI layer there;
 * RepositoryManager references it, so it now lives next to it).
 */

import kotlinx.serialization.Serializable

const val REPOSITORIES_KEY = "REPOSITORIES_KEY"

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
