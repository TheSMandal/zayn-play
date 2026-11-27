package com.lagradost.cloudstream3.ui.library

/**
 * (v0.2.10) Minimal restore of upstream's ListSorting enum (library
 * list sort methods). Only the enum itself is needed: SyncAPI's
 * LibraryMetadata constructor takes a Set<ListSorting>. The sort
 * implementation (SyncAPI.Page.sort) is NOT restored — no plugin
 * calls it.
 */
enum class ListSorting {
    Query,
    RatingHigh,
    RatingLow,
    AlphabeticalA,
    AlphabeticalZ,
    UpdatedNew,
    UpdatedOld,
    ReleaseDateNew,
    ReleaseDateOld,
}
