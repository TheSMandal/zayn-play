package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.annotation.JsonProperty
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
 * annotations). The parent class is a plain class here: upstream's
 * is `class AniListApi : SyncAPI()`, but the SyncAPI stack is NOT
 * vendored in this engine, and no plugin references a method on
 * AniListApi itself — only the nested types. AniList sync (login,
 * library, GraphQL) is NOT restored by this file.
 */
class AniListApi {

    @Serializable
    data class Title(
        @JsonProperty("english") @SerialName("english") val english: String?,
        @JsonProperty("romaji") @SerialName("romaji") val romaji: String?,
    )

    @Serializable
    data class CoverImage(
        @JsonProperty("medium") @SerialName("medium") val medium: String?,
        @JsonProperty("large") @SerialName("large") val large: String?,
        @JsonProperty("extraLarge") @SerialName("extraLarge") val extraLarge: String?,
    )

    @Serializable
    data class LikePageInfo(
        @JsonProperty("total") @SerialName("total") val total: Int?,
        @JsonProperty("currentPage") @SerialName("currentPage") val currentPage: Int?,
        @JsonProperty("lastPage") @SerialName("lastPage") val lastPage: Int?,
        @JsonProperty("perPage") @SerialName("perPage") val perPage: Int?,
        @JsonProperty("hasNextPage") @SerialName("hasNextPage") val hasNextPage: Boolean?,
    )

    @Serializable
    data class RecommendationConnection(
        @JsonProperty("edges") @SerialName("edges") val edges: List<RecommendationEdge> = emptyList(),
        @JsonProperty("nodes") @SerialName("nodes") val nodes: List<Recommendation> = emptyList(),
    )

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
    )
}
