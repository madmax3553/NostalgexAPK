package io.github.madmax3553.staticgroot.jellyfin

import io.github.madmax3553.staticgroot.channels.LibrarySource
import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import java.time.Instant

/*
 * Port of tvOS JellyfinAPIService.parseMovieItem, music-video subset.
 *
 * Quirks kept from the Swift code:
 *  - Runtime comes from RunTimeTicks: 10,000,000 ticks a second, so 600,000,000 a minute.
 *  - Items with no runtime are dropped.
 *  - A dedicated music-video library tags every item "Music Video" when it has no music genre.
 *  - Outside a music-video library, an item of 10 minutes or less with a music genre is
 *    treated as a music video, so clips in a normal library can feed the music channels.
 *  - TMDB id comes from ProviderIds, which Jellyfin keys as "Tmdb" but may case differently.
 */
object JellyfinMapping {

    const val TICKS_PER_MINUTE = 600_000_000L

    fun minutesFromTicks(ticks: Long?): Int = ticks?.let { (it / TICKS_PER_MINUTE).toInt() } ?: 0

    /** Returns null for items with no runtime, as the Swift code does. */
    fun toMediaItem(item: JellyfinItem, isMusicSection: Boolean): MediaItem? {
        val durationMin = minutesFromTicks(item.RunTimeTicks)
        if (durationMin <= 0) return null

        val genres = (item.Genres ?: emptyList()).toMutableList()
        if (isMusicSection && genres.none { it.lowercase().contains("music") }) {
            genres.add("Music Video")
        }
        val isMusicVideo = isMusicSection ||
            (durationMin <= 10 && genres.any { it.lowercase().contains("music") })

        return MediaItem(
            id = item.Id,
            title = item.Name.orEmpty(),
            summary = item.Overview.orEmpty(),
            type = MediaType.MOVIE,
            genres = genres,
            year = item.ProductionYear,
            originallyAvailableAt = item.PremiereDate?.take(10),
            contentRating = item.OfficialRating,
            duration = durationMin,
            rating = item.CommunityRating ?: 0.0,
            userRating = 0.0,
            viewCount = item.UserData?.PlayCount ?: 0,
            addedAt = parseEpochSeconds(item.DateCreated),
            studio = item.Studios?.firstOrNull()?.Name,
            tmdbID = providerId(item.ProviderIds, "Tmdb"),
            librarySource = if (isMusicVideo) LibrarySource.MUSIC_VIDEO else LibrarySource.MOVIE,
        )
    }

    /**
     * Port of tvOS JellyfinAPIService.fetchEpisodes. The series supplies the title, genres,
     * TMDB id, year and rating, because episodes seldom carry their own. Items with no runtime
     * are dropped. Season and episode numbers form the SxxExx tag.
     */
    fun toEpisode(ep: JellyfinItem, show: JellyfinItem): MediaItem? {
        val durationMin = minutesFromTicks(ep.RunTimeTicks)
        if (durationMin <= 0) return null
        val s = ep.ParentIndexNumber?.let { "S%02d".format(it) }.orEmpty()
        val e = ep.IndexNumber?.let { "E%02d".format(it) }.orEmpty()
        return MediaItem(
            id = ep.Id,
            title = show.Name ?: ep.SeriesName.orEmpty(),
            seriesId = show.Id,
            summary = ep.Overview ?: show.Overview.orEmpty(),
            type = MediaType.EPISODE,
            genres = show.Genres ?: emptyList(),
            episodeTitle = ep.Name,
            seTag = (s + e).ifEmpty { null },
            year = show.ProductionYear,
            originallyAvailableAt = (ep.PremiereDate ?: show.PremiereDate)?.take(10),
            contentRating = show.OfficialRating ?: ep.OfficialRating,
            duration = durationMin,
            rating = show.CommunityRating ?: 0.0,
            userRating = 0.0,
            viewCount = ep.UserData?.PlayCount ?: 0,
            addedAt = parseEpochSeconds(ep.DateCreated),
            studio = show.Studios?.firstOrNull()?.Name,
            tmdbID = providerId(show.ProviderIds, "Tmdb"),
            librarySource = LibrarySource.TV,
        )
    }

    /** Case-insensitive provider lookup. Jellyfin keys are "Tmdb" and "Imdb" but clients vary. */
    fun providerId(ids: Map<String, String>?, name: String): String? {
        if (ids == null) return null
        ids[name]?.let { return it }
        return ids.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /** DateCreated is ISO-8601 with a variable number of fractional digits. 0 when unreadable. */
    fun parseEpochSeconds(raw: String?): Long {
        if (raw.isNullOrEmpty()) return 0L
        return runCatching { Instant.parse(raw).epochSecond }.getOrDefault(0L)
    }
}
