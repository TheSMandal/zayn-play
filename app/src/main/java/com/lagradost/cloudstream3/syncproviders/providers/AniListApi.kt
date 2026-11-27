package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * (v0.2.6) Host-compatibility shim for upstream AniListApi's nested
 * response types. Anichi / StreamPlay / TorraStream carry their own
 * AnilistAPIResponse data classes whose FIELDS are typed with these
 * (Title, CoverImage, LikePageInfo, RecommendationConnection,
 * SeasonNextAiringEpisode, RecommendationEdge, Recommendation,
 * RecommendedMedia, MediaTitle, MediaCoverImage) — proven from
 * their dexes, including field types and the getters plugins call
 * (getEnglish/getRomaji, getMedium/getLarge/getExtraLarge,
 * getHasNextPage, getEdges, getEpisode, getNode,
 * getMediaRecommendation, getId/getTitle/getCoverImage). Field types
 * must resolve when those plugin classes are verified, so without
 * these types Anichi fails to load with load_class_missing.
 *
 * The 10 nested classes below are copied verbatim from upstream's
 * AniListApi.kt (same fields, same @Serializable/@JsonProperty
 * annotations). The parent class is `class AniListApi : SyncAPI()`,
 * as upstream: the minimal SyncAPI stack (syncproviders/SyncAPI.kt,
 * AuthRepo, SyncRepo, AccountManager) was restored in v0.2.10 because
 * StreamPlay/TorraStream construct SyncRepo(AccountManager
 * .getAniListApi()) with a check-cast to SyncAPI. AniList sync
 * itself (login, library, GraphQL) is NOT restored — the host has no
 * sync account, so SyncRepo returns no user and an empty library.
 */
class AniListApi : SyncAPI() {

    // (v0.2.9) Fix A: `$stable`, as on Plugin (see plugins/Plugin.kt).
    // Live plugin dexes read these fields in their <clinit> (the
    // AniListApi nested types are the field types of the plugins' own
    // AnilistAPIResponse classes); Kotlin 1.9.24 emits none.
    @Serializable
    data class Title(
        @JsonProperty("english") @SerialName("english") val english: String?,
        @JsonProperty("romaji") @SerialName("romaji") val romaji: String?,
    ) {
        companion object {
            @JvmField
            val `$stable`: Int = 8
        }
    }

    @Serializable
    data class CoverImage(
        @JsonProperty("medium") @SerialName("medium") val medium: String?,
        @JsonProperty("large") @SerialName("large") val large: String?,
        @JsonProperty("extraLarge") @SerialName("extraLarge") val extraLarge: String?,
    ) {
        companion object {
            @JvmField
            val `$stable`: Int = 8
        }
    }

    @Serializable
    data class LikePageInfo(
        @JsonProperty("total") @SerialName("total") val total: Int?,
        @JsonProperty("currentPage") @SerialName("currentPage") val currentPage: Int?,
        @JsonProperty("lastPage") @SerialName("lastPage") val lastPage: Int?,
        @JsonProperty("perPage") @SerialName("perPage") val perPage: Int?,
        @JsonProperty("hasNextPage") @SerialName("hasNextPage") val hasNextPage: Boolean?,
    ) {
        companion object {
            @JvmField
            val `$stable`: Int = 8
        }
    }

    @Serializable
    data class RecommendationConnection(
        @JsonProperty("edges") @SerialName("edges") val edges: List<RecommendationEdge> = emptyList(),
        @JsonProperty("nodes") @SerialName("nodes") val nodes: List<Recommendation> = emptyList(),
    ) {
        companion object {
            @JvmField
            val `$stable`: Int = 8
        }
    }

    @Serializable
    data class RecommendationEdge(
        @JsonProperty("node") @SerialName("node") val node: Recommendation,
    )

    @Serializable
    data class Recommendation(
        @JsonProperty("mediaRecommendation") @SerialName("mediaRecommendation") val mediaRecommendation: RecommendedMedia?,
    )

    @Serializable
    data class RecommendedMedia(
        @JsonProperty("id") @SerialName("id") val id: Int?,
        @JsonProperty("title") @SerialName("title") val title: MediaTitle?,
        @JsonProperty("coverImage") @SerialName("coverImage") val coverImage: MediaCoverImage?,
    )

    @Serializable
    data class MediaTitle(
        @JsonProperty("romaji") @SerialName("romaji") val romaji: String?,
        @JsonProperty("english") @SerialName("english") val english: String?,
        @JsonProperty("native") @SerialName("native") val native: String?,
        @JsonProperty("userPreferred") @SerialName("userPreferred") val userPreferred: String?,
    )

    @Serializable
    data class MediaCoverImage(
        @JsonProperty("extraLarge") @SerialName("extraLarge") val extraLarge: String?,
        @JsonProperty("large") @SerialName("large") val large: String?,
        @JsonProperty("medium") @SerialName("medium") val medium: String?,
        @JsonProperty("color") @SerialName("color") val color: String?,
    )

    @Serializable
    data class SeasonNextAiringEpisode(
        @JsonProperty("episode") @SerialName("episode") val episode: Int?,
        @JsonProperty("timeUntilAiring") @SerialName("timeUntilAiring") val timeUntilAiring: Int?,
    ) {
        companion object {
            @JvmField
            val `$stable`: Int = 8
        }
    }
}
