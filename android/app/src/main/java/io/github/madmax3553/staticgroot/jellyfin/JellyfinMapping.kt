package io.github.madmax3553.staticgroot.jellyfin

import io.github.madmax3553.staticgroot.channels.LibrarySource
import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import io.github.madmax3553.staticgroot.channels.MultiPartStack
import io.github.madmax3553.staticgroot.channels.MusicTitleParser
import io.github.madmax3553.staticgroot.channels.MusicYear
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
 *  - A movie whose sources are all numbered parts is one stacked movie (MultiPartStack). Several
 *    sources otherwise mean versions, and the highest bitrate wins (bestMediaSource).
 *  - A music video's title and artist are split out of the file name (MusicTitleParser).
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

        // Music videos: split "Artist - Song" out of the file name, as tvOS does. The year comes
        // from the raw name, where a ripped "(1988)" suffix lives, because the cleaned title no
        // longer has it. tvOS reads it from the cleaned title on this path, so a filename year
        // is probably dropped there; this follows the documented intent instead.
        // Disc-stacked rips arrive as several MediaSources. The schedule must block out the whole
        // runtime, not disc one's, and playback must continue into the next disc.
        val sources = item.MediaSources.orEmpty()
        val stack = MultiPartStack.detect(sources.map { MultiPartStack.SourceInfo(it.Id, it.Name ?: it.Path, it.RunTimeTicks) })
        val stackMinutes = stack?.totalRunTimeTicks?.let { minutesFromTicks(it) }?.takeIf { it > 0 }

        val rawName = item.Name.orEmpty()
        val parsed = if (isMusicVideo) MusicTitleParser.parse(rawName) else null
        val titleYear = if (isMusicVideo) MusicTitleParser.yearInTitle(rawName) else null
        val year = if (isMusicVideo) {
            MusicYear.resolve(titleYear, musicBrainzYear = null, serverYear = item.ProductionYear)
        } else item.ProductionYear

        return MediaItem(
            id = item.Id,
            title = parsed?.song ?: rawName,
            artist = parsed?.artist,
            summary = item.Overview.orEmpty(),
            type = MediaType.MOVIE,
            genres = genres,
            year = year,
            titleYear = titleYear,
            originallyAvailableAt = item.PremiereDate?.take(10),
            contentRating = item.OfficialRating,
            duration = stackMinutes ?: durationMin,
            rating = item.CommunityRating ?: 0.0,
            userRating = 0.0,
            viewCount = item.UserData?.PlayCount ?: 0,
            addedAt = parseEpochSeconds(item.DateCreated),
            studio = item.Studios?.firstOrNull()?.Name,
            tmdbID = providerId(item.ProviderIds, "Tmdb"),
            imdbID = providerId(item.ProviderIds, "Imdb"),
            librarySource = if (isMusicVideo) LibrarySource.MUSIC_VIDEO else LibrarySource.MOVIE,
            mediaSourceId = if (stack != null) stack.parts.first().sourceId else pickedSourceId(sources),
            parts = stack?.parts.orEmpty(),
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
            imdbID = providerId(show.ProviderIds, "Imdb"),
            librarySource = LibrarySource.TV,
            mediaSourceId = pickedSourceId(ep.MediaSources.orEmpty()),
        )
    }

    /**
     * Item types to request from a library. tvOS asks every library for Movie only. A dedicated
     * music-video library can hold items typed MusicVideo, which that request never returns, so
     * Android adds the type when the server reports at least one. A superset: with none, the
     * request is exactly tvOS's.
     */
    fun itemTypesFor(isMusicSection: Boolean, musicVideoCount: Int): String =
        if (isMusicSection && musicVideoCount > 0) "Movie,MusicVideo" else "Movie"

    /**
     * Port of JellyfinItem.bestMediaSource: the highest bitrate, ties broken on video height. With
     * no bitrate on any source it keeps the first, as tvOS does for libraries without that metadata.
     */
    fun bestMediaSource(sources: List<MediaSource>?): MediaSource? {
        if (sources.isNullOrEmpty()) return null
        if (sources.none { it.Bitrate != null }) return sources.first()
        return sources.maxWithOrNull(
            compareBy<MediaSource> { it.Bitrate ?: Int.MIN_VALUE }.thenBy { videoHeight(it) },
        )
    }

    private fun videoHeight(s: MediaSource): Int =
        s.MediaStreams.orEmpty().firstOrNull { it.type.equals("Video", ignoreCase = true) }?.Height ?: 0

    /**
     * The source to ask the server for, or null to leave the choice to it. Null for a single
     * source, which keeps the playback request identical to what single-file items sent before.
     */
    private fun pickedSourceId(sources: List<MediaSource>): String? =
        if (sources.size > 1) bestMediaSource(sources)?.Id else null

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
