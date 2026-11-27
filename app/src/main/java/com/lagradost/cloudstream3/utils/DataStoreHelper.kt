package com.lagradost.cloudstream3.utils

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * (v0.2.10) Minimal restore of upstream's DataStoreHelper.
 *
 * Live plugins touch only this surface (proven from their dexes):
 *  - UltimaBackupUtils reads/writes `accounts` and
 *    `selectedKeyIndex` (backup/restore of account metadata) and
 *    constructs/compares `Account` (ctor order Int, String, String?,
 *    Int, String?; getters getKeyIndex/getName/getCustomImage/
 *    getDefaultImageIndex/getLockPin).
 *  - Ultima.getMainPage and the WatchParty overlay read
 *    `ResumeWatchingResult` getters (name/url/apiName/episode/season/
 *    id) via HomeViewModel.getResumeWatching().
 *
 * What is NOT restored: the whole preference-backed store
 * (PreferenceDelegate, flows, watch-state/bookmark/favourite
 * persistence). `accounts` / `selectedKeyIndex` are plain in-memory
 * vars — Zayn Play has no multi-account UI and no account data to
 * persist, so there is nothing to back up across restarts. The data
 * classes are copied verbatim from upstream (same fields, same
 * @JsonProperty names — Ultima parses them with Jackson).
 */
object DataStoreHelper {
    var accounts: Array<Account> = emptyArray()
    var selectedKeyIndex: Int = 0
    val currentAccount: String get() = selectedKeyIndex.toString()

    @Serializable
    data class Account(
        @JsonProperty("keyIndex") @SerialName("keyIndex") val keyIndex: Int,
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("customImage") @SerialName("customImage") val customImage: String? = null,
        @JsonProperty("defaultImageIndex") @SerialName("defaultImageIndex") val defaultImageIndex: Int,
        @JsonProperty("lockPin") @SerialName("lockPin") val lockPin: String? = null,
    )

    @Serializable
    data class PosDur(
        @JsonProperty("position") @SerialName("position") val position: Long,
        @JsonProperty("duration") @SerialName("duration") val duration: Long,
    )

    @Serializable
    data class ResumeWatchingResult(
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType? = null,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("watchPos") @SerialName("watchPos") val watchPos: PosDur?,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("parentId") @SerialName("parentId") val parentId: Int?,
        @JsonProperty("episode") @SerialName("episode") val episode: Int?,
        @JsonProperty("season") @SerialName("season") val season: Int?,
        @JsonProperty("isFromDownload") @SerialName("isFromDownload") val isFromDownload: Boolean,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
    ) : SearchResponse

    /**
     * Library entries (bookmarks / favourites / subscriptions), copied
     * verbatim from upstream. StreamCenter and Wrapped read their
     * getters (and copy()) for library counts, catch-up lists and
     * stats. The `rating` write-only property and the custom
     * serializers of upstream are omitted — nothing references them.
     * Zayn Play keeps no library, so these classes are only ever
     * constructed by plugin code from its own data.
     */
    @Serializable
    abstract class LibrarySearchResponse(
        @Transient override var id: Int? = null,
        @Transient open val latestUpdatedTime: Long = 0L,
        @Transient override val name: String = "",
        @Transient override val url: String = "",
        @Transient override val apiName: String = "",
        @Transient override var type: TvType? = null,
        @Transient override var posterUrl: String? = null,
        @Transient open val year: Int? = null,
        @Transient open val syncData: Map<String, String>? = null,
        @Transient override var quality: SearchQuality? = null,
        @Transient override var posterHeaders: Map<String, String>? = null,
        @Transient open val plot: String? = null,
        @Transient override var score: Score? = null,
        @Transient open val tags: List<String>? = null,
    ) : SearchResponse

    @Serializable
    data class BookmarkedData(
        @JsonProperty("bookmarkedTime") @SerialName("bookmarkedTime") val bookmarkedTime: Long,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") @SerialName("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType?,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") @SerialName("year") override val year: Int?,
        @JsonProperty("syncData") @SerialName("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") @SerialName("plot") override val plot: String? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("tags") @SerialName("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse(
        id, latestUpdatedTime, name, url, apiName, type, posterUrl, year,
        syncData, quality, posterHeaders, plot, score, tags,
    )

    @Serializable
    data class FavoritesData(
        @JsonProperty("favoritesTime") @SerialName("favoritesTime") val favoritesTime: Long,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") @SerialName("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType?,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") @SerialName("year") override val year: Int?,
        @JsonProperty("syncData") @SerialName("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") @SerialName("plot") override val plot: String? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("tags") @SerialName("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse(
        id, latestUpdatedTime, name, url, apiName, type, posterUrl, year,
        syncData, quality, posterHeaders, plot, score, tags,
    )

    @Serializable
    data class SubscribedData(
        @JsonProperty("subscribedTime") @SerialName("subscribedTime") val subscribedTime: Long,
        @JsonProperty("lastSeenEpisodeCount") @SerialName("lastSeenEpisodeCount") val lastSeenEpisodeCount: Map<DubStatus, Int?>,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") @SerialName("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType?,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") @SerialName("year") override val year: Int?,
        @JsonProperty("syncData") @SerialName("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") @SerialName("plot") override val plot: String? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("tags") @SerialName("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse(
        id, latestUpdatedTime, name, url, apiName, type, posterUrl, year,
        syncData, quality, posterHeaders, plot, score, tags,
    )
}
