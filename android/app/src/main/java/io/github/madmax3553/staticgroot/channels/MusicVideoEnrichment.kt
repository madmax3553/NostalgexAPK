package io.github.madmax3553.staticgroot.channels

import kotlinx.serialization.Serializable

/**
 * Metadata resolved outside the media server for one music video (MusicBrainz, Deezer and the
 * filename parser). Port of tvOS MusicVideoEnrichment, keyed by the server's item id.
 */
@Serializable
data class MusicVideoEnrichment(
    val itemId: String,
    /** The song title used for lookup. */
    val recordingTitle: String,
    val artist: String? = null,
    val musicBrainzID: String? = null,
    /** Genres mapped into the vocabulary channels.json rules use. */
    val genres: List<String> = emptyList(),
    val releaseYear: Int? = null,
    /** Unix seconds. */
    val fetchedAt: Long = 0,
    val deezerID: Long? = null,
    /** Unix seconds when Deezer was last asked, matched or not. Stops a dead lookup repeating each launch. */
    val deezerCheckedAt: Long? = null,
) {
    val hasUsefulGenres: Boolean
        get() = genres.any { g ->
            val l = g.lowercase()
            !l.contains("music video") && l != "music" && l != "musical"
        }
}

/** Port of PlexMediaItem.applyingMusicEnrichment. Items that are not music videos pass through. */
fun MediaItem.applyingMusicEnrichment(music: MusicVideoEnrichment): MediaItem {
    if (!isMusicVideo) return this
    val resolved = music.recordingTitle.trim()
    val merged = genres.toMutableList()
    for (g in music.genres) if (merged.none { it.equals(g, ignoreCase = true) }) merged.add(g)
    return copy(
        title = resolved.ifEmpty { title },
        artist = music.artist ?: artist,
        genres = merged,
        // The filename year was already resolved into [year] when the item was mapped, and it
        // still outranks MusicBrainz. Without one, MusicBrainz outranks the server's year.
        year = MusicYear.resolve(titleYear, music.releaseYear, serverYear = year),
    )
}
