package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.utils.DataStoreHelper

/**
 * (v0.2.10) Minimal restore of upstream's HomeViewModel.
 *
 * Live plugins (Ultima, WatchParty) call only
 * `HomeViewModel.Companion.getResumeWatching()` and then `firstOrNull`
 * on the result, to show the resume-watching strip in their overlays.
 * Upstream's real implementation reads the persisted resume-watching
 * list from the DataStore; Zayn Play keeps no resume-watching state,
 * so this returns an empty list — never null, so the plugins' null
 * handling is never even reached. The full ViewModel (homepage lists,
 * library, search) is NOT restored: no plugin instantiates this class
 * or calls any instance member.
 *
 * ExpandableHomepageList is restored because StreamCenter's lazy
 * home constructs it (ctor HomePageList, Int, Boolean) and reads /
 * writes its list — a plain data holder, copied verbatim from
 * upstream.
 */
class HomeViewModel {
    data class ExpandableHomepageList(
        var list: HomePageList,
        var currentPage: Int,
        var hasNext: Boolean,
    )

    companion object {
        suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? =
            emptyList()
    }
}
