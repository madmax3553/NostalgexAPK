package io.github.madmax3553.staticgroot.channels

import kotlinx.serialization.Serializable

/**
 * The fields of tvOS MediaEnrichment that channel rules read (TMDB and OMDb), plus the
 * identifiers the on-disk cache needs. tvOS keys enrichment by TMDB id, and TV episodes share
 * their show's id, so one record serves every episode of a show.
 */
@Serializable
data class MediaEnrichment(
    val keywords: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val productionCompanies: List<String> = emptyList(),
    val imdbRating: Double? = null,
    val imdbVotes: Int? = null,
    val rottenTomatoesScore: Int? = null,
    val metacriticScore: Int? = null,
    /** Raw OMDb "Awards" string. */
    val awards: String? = null,
    val tmdbID: String = "",
    val imdbID: String? = null,
    /** "movie" or "tv", as tvOS stores it. */
    val mediaType: String = "movie",
    /** Unix seconds. */
    val fetchedAt: Long = 0,
    /**
     * Unix seconds when OMDb last answered or said it had no such title, 0 if never asked.
     * Not in tvOS: without tvOS's shared cache, titles OMDb will never rate would otherwise
     * spend the daily quota again on every launch.
     */
    val omdbCheckedAt: Long = 0,
) {
    /** True once OMDb has answered for this title. tvOS treats a null imdbRating as "not yet". */
    val hasOmdb: Boolean get() = imdbRating != null

    /** Port of MediaEnrichment.merging(omdb:). */
    fun merging(omdb: OmdbResult, nowEpochSeconds: Long): MediaEnrichment = copy(
        imdbID = imdbID ?: omdb.imdbID,
        imdbRating = omdb.imdbRating,
        imdbVotes = omdb.imdbVotes,
        rottenTomatoesScore = omdb.rottenTomatoesScore,
        metacriticScore = omdb.metacriticScore,
        awards = omdb.awards,
        fetchedAt = nowEpochSeconds,
        omdbCheckedAt = nowEpochSeconds,
    )
}

/** Port of tvOS OMDBResult: transient, merged into [MediaEnrichment]. */
data class OmdbResult(
    val imdbID: String,
    val imdbRating: Double?,
    val imdbVotes: Int?,
    val rottenTomatoesScore: Int?,
    val metacriticScore: Int?,
    val awards: String?,
)

/** Enrichment for [item], looked up by TMDB id as tvOS does. Null when the item has none. */
fun Map<String, MediaEnrichment>.forItem(item: MediaItem): MediaEnrichment? =
    item.tmdbID?.let { this[it] }
