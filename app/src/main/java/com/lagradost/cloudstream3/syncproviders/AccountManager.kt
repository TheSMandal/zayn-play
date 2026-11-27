package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.AniListApi

/**
 * (v0.2.10) Minimal restore of upstream's AccountManager.
 *
 * The only member live plugins touch is the companion's
 * `aniListApi` (StreamPlayAnime / TorraStreamAnime <init> call
 * AccountManager.Companion.getAniListApi()). Upstream's full manager
 * also holds the other sync APIs (MAL, Kitsu, Simkl, LocalList),
 * subtitle APIs and the persisted account store — none of that is
 * restored: Zayn Play has no sync accounts, and no plugin references
 * those members.
 */
abstract class AccountManager {
    companion object {
        const val NONE_ID: Int = -1
        val aniListApi = AniListApi()
    }
}
