package io.github.madmax3553.staticgroot.channels

/*
 * The fields of tvOS PlexMediaItem that channel rules read. Playback, artwork and
 * server routing fields come with the server clients, not with the filter.
 */

enum class MediaType(val raw: String) {
    MOVIE("movie"),
    EPISODE("episode");

    companion object {
        /** Unknown raw values return null, like Swift's `MediaType(rawValue:)`. */
        fun fromRaw(raw: String?): MediaType? = entries.firstOrNull { it.raw == raw }
    }
}

enum class LibrarySource(val raw: String) {
    MOVIE("movie"),
    TV("tv"),
    MUSIC_VIDEO("musicVideo");

    companion object {
        fun fromRaw(raw: String?): LibrarySource? = entries.firstOrNull { it.raw == raw }
    }
}

data class MediaItem(
    val id: String,
    val title: String,
    val type: MediaType,
    val genres: List<String> = emptyList(),
    val artist: String? = null,
    val episodeTitle: String? = null,
    /** "S01E04" for episodes. Null for movies and music videos. */
    val seTag: String? = null,
    val year: Int? = null,
    /** A music video's "(YYYY)" filename suffix. It beats every other year source. See [MusicYear]. */
    val titleYear: Int? = null,
    /** YYYY-MM-DD from the server. More precise than [year] when present. */
    val originallyAvailableAt: String? = null,
    val contentRating: String? = null,
    /** Minutes. */
    val duration: Int = 0,
    /** Critic-style rating from the server, 0-10. */
    val rating: Double = 0.0,
    /** The user's own rating, 0-10. */
    val userRating: Double = 0.0,
    val viewCount: Int = 0,
    /** Unix seconds when the item was added to the library. */
    val addedAt: Long = 0,
    val studio: String? = null,
    val tmdbID: String? = null,
    val imdbID: String? = null,
    val librarySource: LibrarySource? = null,
    val summary: String = "",
    val seriesId: String? = null,
    /**
     * The server file to play. Set only when the item has more than one source, so single-file
     * items ask the server exactly what they always did. tvOS keeps this in `partKey`.
     */
    val mediaSourceId: String? = null,
    /** The files of a disc-stacked movie, in order. Empty for everything else. */
    val parts: List<MediaPart> = emptyList(),
)
