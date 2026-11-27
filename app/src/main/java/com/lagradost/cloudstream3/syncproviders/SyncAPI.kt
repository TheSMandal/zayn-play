package com.lagradost.cloudstream3.syncproviders

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.UiText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Date

enum class SyncIdName {
    Anilist,
    MyAnimeList,
    Kitsu,
    Trakt,
    Imdb,
    Simkl,
    LocalList,
}

/**
 * (v0.2.10) Minimal faithful restore of upstream's sync stack, vendored
 * because live plugins reference it:
 *  - StreamPlayAnime / TorraStreamAnime <init> do
 *    `SyncRepo(AccountManager.getAniListApi())` with a check-cast to
 *    SyncAPI — without these classes both providers cannot instantiate.
 *  - Their getMainPage calls SyncRepo.authUser() (null = no account
 *    linked; the plugin then takes its non-Personal branch) and
 *    SyncRepo.library(), reading LibraryMetadata.allLibraryLists and
 *    LibraryList name/items.
 *
 * What is NOT restored: login, token refresh, status updates, search —
 * Zayn Play has no sync account at all, so authUser() always returns
 * null and library() returns an empty library. No plugin in the sweep
 * calls any other SyncAPI/AuthAPI member. SyncWatchType and
 * ListSorting-adjacent UI (Page.sort) are not restored; ListSorting
 * itself is vendored as a plain enum because LibraryMetadata's
 * constructor takes a Set<ListSorting>.
 *
 * Copied shapes are verbatim from upstream (recloudstream/cloudstream
 * master, syncproviders/): AuthUser, AuthAPI's plain vals, SyncAPI's
 * nested Library* data classes.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class AuthUser(
    @JsonProperty("name") @SerialName("name")
    val name: String?,
    @JsonProperty("id") @SerialName("id")
    val id: Int,
    @JsonProperty("profilePicture") @SerialName("profilePicture")
    val profilePicture: String? = null,
    @JsonProperty("profilePictureHeaders") @JsonAlias("profilePictureHeader")
    @SerialName("profilePictureHeaders")
    @kotlinx.serialization.json.JsonNames("profilePictureHeader")
    val profilePictureHeaders: Map<String, String>? = null,
)

data class AuthLoginRequirement(
    val password: Boolean = false,
    val username: Boolean = false,
    val email: Boolean = false,
    val server: Boolean = false,
)

@Serializable
data class AuthToken(
    @JsonProperty("accessToken") @SerialName("accessToken")
    val accessToken: String? = null,
    @JsonProperty("refreshToken") @SerialName("refreshToken")
    val refreshToken: String? = null,
    @JsonProperty("accessTokenLifetime") @SerialName("accessTokenLifetime")
    val accessTokenLifetime: Long? = null,
    @JsonProperty("refreshTokenLifetime") @SerialName("refreshTokenLifetime")
    val refreshTokenLifetime: Long? = null,
    @JsonProperty("payload") @SerialName("payload")
    val payload: String? = null,
)

@Serializable
data class AuthData(
    @JsonProperty("user") @SerialName("user") val user: AuthUser,
    @JsonProperty("token") @SerialName("token") val token: AuthToken,
)

/** Stateless authentication class. No login is implemented here. */
abstract class AuthAPI {
    open val name: String = "NONE"
    open val idPrefix: String = "NONE"
    open val icon: Int? = null
    open val requiresLogin: Boolean = true
    open val createAccountUrl: String? = null
    open val redirectUrlIdentifier: String? = null
    open val hasOAuth2: Boolean = false
    open val hasPin: Boolean = false
    open val hasInApp: Boolean = false
    open val inAppLoginRequirement: AuthLoginRequirement? = null
}

/**
 * Stateless synchronization class. Minimal: only the members the
 * vendored SyncRepo and the plugins touch. All login/status/search
 * members of upstream SyncAPI are omitted — no plugin calls them.
 */
abstract class SyncAPI : AuthAPI() {
    open var requireLibraryRefresh: Boolean = true
    open val syncIdName: SyncIdName? = null

    data class SyncSearchResult(
        override val name: String,
        override val apiName: String,
        var syncId: String,
        override val url: String,
        override var posterUrl: String?,
        override var type: TvType? = null,
        override var quality: SearchQuality? = null,
        override var posterHeaders: Map<String, String>? = null,
        override var id: Int? = null,
        override var score: Score? = null,
    ) : SearchResponse

    abstract class AbstractSyncStatus {
        abstract var status: SyncWatchType
        abstract var score: Score?
        abstract var watchedEpisodes: Int?
        abstract var isFavorite: Boolean?
        abstract var maxEpisodes: Int?
    }

    data class SyncResult(
        var id: String,
        var totalEpisodes: Int? = null,
        var title: String? = null,
        var publicScore: Score? = null,
        /** In minutes */
        var duration: Int? = null,
        var synopsis: String? = null,
        var airStatus: ShowStatus? = null,
        var nextAiring: NextAiring? = null,
        var studio: List<String>? = null,
        var genres: List<String>? = null,
        var synonyms: List<String>? = null,
        var trailers: List<String>? = null,
        var isAdult: Boolean? = null,
        var posterUrl: String? = null,
        var backgroundPosterUrl: String? = null,
        /** In unixtime */
        var startDate: Long? = null,
        /** In unixtime */
        var endDate: Long? = null,
        var recommendations: List<SyncSearchResult>? = null,
        var nextSeason: SyncSearchResult? = null,
        var prevSeason: SyncSearchResult? = null,
        var actors: List<ActorData>? = null,
    )

    data class LibraryMetadata(
        val allLibraryLists: List<LibraryList>,
        val supportedListSorting: Set<ListSorting>
    )

    data class LibraryList(
        val name: UiText,
        val items: List<LibraryItem>
    )

    data class LibraryItem(
        override val name: String,
        override val url: String,
        val syncId: String,
        val episodesCompleted: Int?,
        val episodesTotal: Int?,
        val personalRating: Score?,
        val lastUpdatedUnixTime: Long?,
        override val apiName: String,
        override var type: TvType?,
        override var posterUrl: String?,
        override var posterHeaders: Map<String, String>?,
        override var quality: SearchQuality?,
        val releaseDate: Date?,
        override var id: Int? = null,
        val plot: String? = null,
        override var score: Score? = null,
        val tags: List<String>? = null
    ) : SearchResponse
}
