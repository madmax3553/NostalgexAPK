package io.github.madmax3553.staticgroot.channels

/** The fields of tvOS MediaEnrichment that channel rules read (TMDB and OMDb). */
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
)
