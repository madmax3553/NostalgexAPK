package io.github.madmax3553.staticgroot.enrichment

import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MusicTitleParser
import io.github.madmax3553.staticgroot.channels.MusicVideoEnrichment
import io.github.madmax3553.staticgroot.channels.applyingMusicEnrichment
import io.github.madmax3553.staticgroot.channels.isMusicVideo
import io.github.madmax3553.staticgroot.jellyfin.JellyfinException

data class MusicEnrichmentOutcome(
    val candidates: Int,
    val resolved: Int,
    val genrePassed: Int,
    val skippedFailed: Int,
    val aborted: Boolean,
)

/**
 * Port of tvOS MusicEnrichmentService: MusicBrainz names a video, Deezer supplies genres when
 * MusicBrainz's sparse tags do not. At one MusicBrainz request a second this takes tens of minutes
 * for a large library, so it runs in the background after the guide opens (tvOS also defers it
 * until after tune-in). Results are cached as they arrive and applied on the next launch.
 *
 * Different from tvOS, whose failures pin a degraded row for 90 days:
 *  - A MusicBrainz or Deezer call that fails (not "no match") caches nothing for that title, so
 *    it is retried on a later launch. Five failures in a row end the pass.
 *  - Deezer's checked-stamp is set only when Deezer actually answered.
 */
class MusicEnrichmentService(
    private val musicBrainz: MusicBrainzClient?,
    private val deezer: DeezerClient?,
    private val store: MusicEnrichmentStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val saveEvery: Int = 25,
    private val log: (String) -> Unit = {},
) {

    fun enrich(items: List<MediaItem>, shouldStop: () -> Boolean = { false }): MusicEnrichmentOutcome {
        val cache = HashMap(store.load())
        val candidates = items.filter { it.isMusicVideo }
        val now = nowMs() / 1000
        val work = ArrayList<MediaItem>()
        val genrePass = ArrayList<MediaItem>()
        for (item in candidates) {
            val row = cache[item.id]
            when {
                row != null && isSettled(row, now) -> Unit
                row != null && needsOnlyGenres(row) -> genrePass.add(item)
                else -> work.add(item)
            }
        }
        log("music videos=${candidates.size} toResolve=${work.size} genrePass=${genrePass.size}")

        var resolved = 0; var passed = 0; var failed = 0; var streak = 0; var sinceSave = 0
        fun tick() { if (++sinceSave >= saveEvery) { store.save(cache.values); sinceSave = 0 } }
        fun fail(): Boolean { failed++; streak++; return streak >= GIVE_UP_AFTER }

        var aborted = false
        for (item in genrePass) {
            if (shouldStop()) { aborted = true; break }
            val row = cache[item.id] ?: continue
            val next = try { withDeezer(row, item).also { streak = 0 } } catch (e: JellyfinException) {
                if (fail()) { aborted = true; break }; continue
            }
            cache[item.id] = next; passed++; tick()
        }
        if (!aborted) for (item in work) {
            if (shouldStop()) { aborted = true; break }
            val row = try { resolve(item).also { streak = 0 } } catch (e: JellyfinException) {
                if (fail()) { aborted = true; break }; continue
            }
            cache[item.id] = row; resolved++; tick()
        }
        store.save(cache.values)
        val outcome = MusicEnrichmentOutcome(candidates.size, resolved, passed, failed, aborted)
        log("done $outcome")
        return outcome
    }

    private fun resolve(item: MediaItem): MusicVideoEnrichment {
        val parsed = MusicTitleParser.parse(item.title)
        val song = parsed.song
        val artistHint = item.artist ?: parsed.artist
        val now = nowMs() / 1000

        var row: MusicVideoEnrichment? = null
        if (musicBrainz != null) {
            val match = musicBrainz.resolveRecording(song, artistHint, item.year) // throws on failure
            if (match != null) {
                row = MusicVideoEnrichment(
                    itemId = item.id, recordingTitle = match.recordingTitle, artist = match.artist ?: artistHint,
                    musicBrainzID = match.mbid, genres = mergeGenres(item.genres, match.genres),
                    releaseYear = match.releaseYear, fetchedAt = now,
                )
            }
        }
        val base = row ?: MusicVideoEnrichment(
            itemId = item.id, recordingTitle = song, artist = artistHint, genres = item.genres, fetchedAt = now,
        )
        return if (base.hasUsefulGenres) base else withDeezer(base, item)
    }

    /** One Deezer round trip for a row with a name but no genre. Stamps the check only when Deezer answered. */
    private fun withDeezer(row: MusicVideoEnrichment, item: MediaItem): MusicVideoEnrichment {
        if (deezer == null) return row
        val parsed = MusicTitleParser.parse(item.title)
        val song = row.recordingTitle.ifEmpty { parsed.song }
        val artist = row.artist ?: parsed.artist
        val match = deezer.resolve(song, artist) // throws on failure
        // Deezer's year is the album's (remasters, compilations), measured wrong by years on tvOS.
        // It never becomes releaseYear.
        return row.copy(
            artist = row.artist ?: match?.artist,
            genres = if (match != null) mergeGenres(row.genres, match.genres) else row.genres,
            deezerID = match?.trackID ?: row.deezerID,
            deezerCheckedAt = nowMs() / 1000,
        )
    }

    companion object {
        private const val GIVE_UP_AFTER = 5
        private const val NINETY_DAYS = 90L * 24 * 3600

        /** Nothing left to ask anyone: it has genres, or Deezer was already consulted. Stale after 90 days. */
        fun isSettled(row: MusicVideoEnrichment, nowSeconds: Long): Boolean {
            if (nowSeconds - row.fetchedAt >= NINETY_DAYS) return false
            return row.hasUsefulGenres || row.deezerCheckedAt != null
        }

        /** Named, genreless and never shown to Deezer. */
        fun needsOnlyGenres(row: MusicVideoEnrichment): Boolean =
            !row.hasUsefulGenres && row.deezerCheckedAt == null && (row.artist != null || row.musicBrainzID != null)

        fun mergeGenres(base: List<String>, extra: List<String>): List<String> {
            val out = base.toMutableList()
            for (g in extra) if (out.none { it.equals(g, ignoreCase = true) }) out.add(g)
            return out
        }

        /** Applies cached rows to items, as itemsWithDisplayMetadata does. Items without a row are unchanged. */
        fun apply(items: List<MediaItem>, cache: Map<String, MusicVideoEnrichment>): List<MediaItem> =
            if (cache.isEmpty()) items else items.map { cache[it.id]?.let(it::applyingMusicEnrichment) ?: it }
    }
}
