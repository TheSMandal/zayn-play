package com.lagradost.cloudstream3.syncproviders

/**
 * (v0.2.10) Minimal faithful restore of upstream's SyncRepo.
 *
 * Upstream's library() calls api.library(freshAuth()) against the
 * linked account. Zayn Play has no sync account, so library() returns
 * an empty library wrapped in Result.success — the same shape the
 * plugins read (LibraryMetadata.allLibraryLists), with zero lists.
 *
 * NOTE on the JVM name: this suspend fun returns Result<...>, a value
 * class, so kotlinc mangles the JVM name (upstream's plugins call
 * `library-IoAF18A(Continuation)`). The mangled suffix is a stable
 * hash of the signature; the built dex is verified to carry exactly
 * `library-IoAF18A` (see VERIFY-0.2.10.md) — if kotlinc ever mangles
 * it differently, pin it with @JvmName("library-IoAF18A").
 */
class SyncRepo(override val api: SyncAPI) : AuthRepo(api) {
    val syncIdName = api.syncIdName
    var requireLibraryRefresh: Boolean
        get() = api.requireLibraryRefresh
        set(value) {
            api.requireLibraryRefresh = value
        }

    suspend fun library(): Result<SyncAPI.LibraryMetadata?> =
        Result.success(SyncAPI.LibraryMetadata(emptyList(), emptySet()))
}
