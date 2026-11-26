package com.lagradost.cloudstream3.metaproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ProviderType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * episode and season starting from 1
 * they are null if movie
 */
@Serializable
data class TmdbLink(
    @JsonProperty("imdbID") @SerialName("imdbID") val imdbID: String?,
    @JsonProperty("tmdbID") @SerialName("tmdbID") val tmdbID: Int?,
    @JsonProperty("episode") @SerialName("episode") val episode: Int?,
    @JsonProperty("season") @SerialName("season") val season: Int?,
    @JsonProperty("movieName") @SerialName("movieName") val movieName: String? = null,
)

/**
 * (v0.2.6) Minimal host-compatibility stub for upstream's
 * TmdbProvider. 4 Phisher plugins construct it in their load closure
 * (member surface proven from their dexes: only the no-arg <init>),
 * so without this class their plugin classes fail verification with
 * load_class_missing.
 *
 * HONEST LIMIT — this is NOT upstream's TMDB client (400+ lines,
 * live api.themoviedb.org calls, API key). It is a MainAPI with
 * upstream's public flags, so plugins can construct it and the load
 * succeeds; metadata calls fall through to MainAPI's defaults
 * (search/load return null), i.e. TMDB-backed metadata is absent
 * for those providers in this build. MainAPI has no abstract
 * members, so no overrides are needed for the stub to compile.
 */
open class TmdbProvider : MainAPI() {
    // This should always be false, but might as well make it easier for forks
    open val includeAdult = false

    // Use the LoadResponse from the metadata provider
    open val useMetaLoadResponse = false
    open val apiName = "TMDB"

    // As some sites don't support s0
    open val disableSeasonZero = true

    override val hasMainPage = true
    override val providerType = ProviderType.MetaProvider
}
