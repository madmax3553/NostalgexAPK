package io.github.madmax3553.staticgroot.enrichment

import io.github.madmax3553.staticgroot.channels.LibrarySource
import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import io.github.madmax3553.staticgroot.channels.MusicVideoEnrichment
import io.github.madmax3553.staticgroot.channels.applyingMusicEnrichment
import io.github.madmax3553.staticgroot.channels.displayTitle
import io.github.madmax3553.staticgroot.jellyfin.JellyfinException
import io.github.madmax3553.staticgroot.jellyfin.JellyfinHttp
import io.github.madmax3553.staticgroot.jellyfin.JellyfinMapping
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/* Genre maps, bestHit and the settled rules are the tvOS MusicMetadataTests cases. */
class MusicEnrichmentTest {

    @get:Rule val tmp = TemporaryFolder()

    // -- Vocabulary maps (tvOS tests) ----------------------------------------------------

    @Test
    fun musicBrainzTagsMapToChannelGenres() {
        val g = MusicBrainzClient.mapTagsToChannelGenres(listOf("pop rock", "hip hop", "soul"))
        assertTrue(g.containsAll(listOf("Pop", "Hip-Hop", "Soul")))
    }

    @Test
    fun deezerGenresMapOntoChannelVocabulary() {
        assertEquals(listOf("Hip-Hop", "Hip Hop", "Rap"), DeezerClient.mapGenres(listOf("Rap/Hip Hop")))
        assertEquals(listOf("Soul", "Funk"), DeezerClient.mapGenres(listOf("Soul & Funk")))
        assertEquals(listOf("Latin"), DeezerClient.mapGenres(listOf("Latin Music")))
        assertTrue(DeezerClient.mapGenres(listOf("Metal")).contains("Metal"))
        assertTrue(DeezerClient.mapGenres(listOf("Dance")).contains("Electronic"))
        assertTrue(DeezerClient.mapGenres(listOf("Films/Games", "Film Scores", "Kids")).isEmpty())
        assertEquals(listOf("Pop", "Rock"), DeezerClient.mapGenres(listOf("Pop", "International Pop", "Rock")))
    }

    @Test
    fun bestHitPrefersTheArtistWeAskedFor() {
        val hits = listOf(
            DeezerClient.SearchHit(1, 1, "Afternoon Delight", "Starland Vocal Band"),
            DeezerClient.SearchHit(2, 2, "Afternoon Delight", "Will Ferrell"),
        )
        assertEquals(2L, DeezerClient.bestHit(hits, "Will Ferrell")?.trackID)
        assertEquals(1L, DeezerClient.bestHit(hits, null)?.trackID)
        assertEquals(1L, DeezerClient.bestHit(hits, "Nobody Here")?.trackID)
        assertNull(DeezerClient.bestHit(emptyList(), "x"))
    }

    // -- Settled rules (tvOS cachedRowsWithANameButNoGenreGetTheDeezerPass) -------------

    private val NOW = 1_800_000_000L
    private fun row(genres: List<String>, artist: String?, mbid: String?, checked: Long?, fetched: Long = NOW) =
        MusicVideoEnrichment("1", "Song", artist, mbid, genres, null, fetched, deezerCheckedAt = checked)

    @Test
    fun rowsWithANameButNoGenreGetTheDeezerPass() {
        val named = row(listOf("Music Video"), "Incubus", "mb-1", null)
        assertFalse(MusicEnrichmentService.isSettled(named, NOW))
        assertTrue(MusicEnrichmentService.needsOnlyGenres(named))

        assertTrue(MusicEnrichmentService.isSettled(row(listOf("Alternative"), "Incubus", "mb-1", null), NOW))

        val asked = row(listOf("Music Video"), "Incubus", "mb-1", NOW)
        assertTrue(MusicEnrichmentService.isSettled(asked, NOW))
        assertFalse(MusicEnrichmentService.needsOnlyGenres(asked))

        assertFalse("nothing to search with", MusicEnrichmentService.needsOnlyGenres(row(emptyList(), null, null, null)))

        val stale = row(listOf("Alternative"), "Incubus", "mb-1", NOW, fetched = NOW - 100L * 86_400)
        assertFalse(MusicEnrichmentService.isSettled(stale, NOW))
    }

    // -- Applying a row (tvOS applyingMusicEnrichmentUpdatesDisplayFields) --------------

    private fun clip(title: String = "Thriller.mp4", year: Int? = null, titleYear: Int? = null, genres: List<String> = listOf("Music Video")) =
        MediaItem(id = "1", title = title, type = MediaType.MOVIE, genres = genres, year = year, titleYear = titleYear,
            duration = 5, librarySource = LibrarySource.MUSIC_VIDEO)

    @Test
    fun applyingUpdatesDisplayFields() {
        val m = clip().applyingMusicEnrichment(MusicVideoEnrichment("1", "Thriller", "Michael Jackson", "mbid", listOf("Pop", "Rock"), 1982))
        assertEquals("Thriller", m.title)
        assertEquals("Michael Jackson", m.artist)
        assertEquals(1982, m.year)
        assertEquals("Michael Jackson \u00B7 Thriller", m.displayTitle)
        assertTrue(m.genres.containsAll(listOf("Music Video", "Pop", "Rock")))
    }

    @Test
    fun filenameYearBeatsMusicBrainzAndMusicBrainzBeatsTheServer() {
        val row = MusicVideoEnrichment("1", "S", releaseYear = 2009)
        assertEquals(1988, clip(year = 1988, titleYear = 1988).applyingMusicEnrichment(row).year)
        assertEquals(2009, clip(year = 2001).applyingMusicEnrichment(row).year)
        assertEquals(2001, clip(year = 2001).applyingMusicEnrichment(MusicVideoEnrichment("1", "S")).year)
    }

    @Test
    fun aMovieIsNeverTouchedAndGenresAreNotDuplicated() {
        val movie = clip().copy(librarySource = LibrarySource.MOVIE)
        assertEquals(movie, movie.applyingMusicEnrichment(MusicVideoEnrichment("1", "X", "Y", genres = listOf("Pop"))))
        val m = clip(genres = listOf("pop")).applyingMusicEnrichment(MusicVideoEnrichment("1", "S", genres = listOf("Pop")))
        assertEquals(listOf("pop"), m.genres)
    }

    // -- Service behaviour ---------------------------------------------------------------

    private class Http(val handler: (String) -> String) : JellyfinHttp {
        val urls = ConcurrentLinkedQueue<String>()
        override fun get(url: String, headers: Map<String, String>): String {
            assertTrue("MusicBrainz and Deezer need a User-Agent", headers.containsKey("User-Agent"))
            urls.add(url); return handler(url)
        }
        override fun post(url: String, headers: Map<String, String>, body: String) = error("unused")
        fun count(p: String) = urls.count { it.contains(p) }
    }

    private val mbSearch = """{"recordings":[
        {"id":"wrong","title":"Billie Jean","score":100,"artist-credit":[{"name":"Cover Band"}],"releases":[{"date":"2001-01-01"}]},
        {"id":"mb1","title":"Billie Jean","score":90,"artist-credit":[{"name":"Michael Jackson"}],"releases":[{"date":"1983-01-02"}]}]}"""
    private val mbLookup = """{"title":"Billie Jean","artist-credit":[{"artist":{"name":"Michael Jackson"}}],
        "tags":[{"name":"pop"},{"name":"soul"}],"releases":[{"date":"1982-11-30"}]}"""
    private val dzSearch = """{"data":[{"id":5,"title":"Billie Jean","artist":{"name":"Michael Jackson"},"album":{"id":9}}]}"""
    private val dzAlbum = """{"genres":{"data":[{"name":"Pop"},{"name":"Films/Games"}]},"release_date":"1982-11-30"}"""

    private fun happy() = Http {
        when {
            it.contains("/recording?query") -> mbSearch
            it.contains("/recording/") -> mbLookup
            it.contains("/search") -> dzSearch
            it.contains("/album/") -> dzAlbum
            else -> error(it)
        }
    }

    private fun store() = MusicEnrichmentStore(File(tmp.root, "m.json"))
    private fun service(http: Http, store: MusicEnrichmentStore = store(), stop: Int = Int.MAX_VALUE) =
        MusicEnrichmentService(MusicBrainzClient(http, "ua", Throttle(0)), DeezerClient(http, "ua", Throttle(0)), store, saveEvery = 2)

    private fun video(id: String, title: String, artist: String? = "Michael Jackson") =
        MediaItem(id = id, title = title, type = MediaType.MOVIE, artist = artist, genres = listOf("Music Video"),
            duration = 4, librarySource = LibrarySource.MUSIC_VIDEO)

    @Test
    fun musicBrainzNamesTheVideoAndTagsFillGenresWithoutDeezer() {
        val http = happy(); val s = store()
        val out = service(http, s).enrich(listOf(video("a", "Billie Jean")))
        assertEquals(1, out.resolved)
        val row = s.load().getValue("a")
        assertEquals("mb1", row.musicBrainzID)
        assertEquals(1982, row.releaseYear)
        assertTrue(row.genres.containsAll(listOf("Pop", "Soul")))
        assertEquals("pop and soul tags are enough, Deezer is not asked", 0, http.count("deezer"))
        assertEquals(0, http.count("/search"))
    }

    @Test
    fun anArtistMatchOutranksAHigherScoreFromADifferentArtist() {
        val s = store()
        service(happy(), s).enrich(listOf(video("a", "Billie Jean")))
        assertEquals("mb1", s.load().getValue("a").musicBrainzID)
    }

    @Test
    fun deezerFillsGenresWhenMusicBrainzTagsAreEmpty() {
        val http = Http {
            when {
                it.contains("/recording?query") -> mbSearch
                it.contains("/recording/") -> """{"title":"Billie Jean","tags":[],"releases":[]}"""
                it.contains("/search") -> dzSearch
                else -> dzAlbum
            }
        }
        val s = store()
        service(http, s).enrich(listOf(video("a", "Billie Jean")))
        val row = s.load().getValue("a")
        assertTrue(row.genres.contains("Pop"))
        assertNotNull(row.deezerCheckedAt)
        assertEquals(5L, row.deezerID)
        assertNull("Deezer's year must never become the release year", row.releaseYear)
    }

    @Test
    fun settledRowsAreNotAskedAgain() {
        val s = store()
        service(happy(), s).enrich(listOf(video("a", "Billie Jean")))
        val http2 = happy()
        val out = service(http2, s).enrich(listOf(video("a", "Billie Jean")))
        assertTrue(http2.urls.isEmpty())
        assertEquals(0, out.resolved)
    }

    @Test
    fun anUnreachableServiceCachesNothingSoTheTitleRetriesNextLaunch() {
        val s = store()
        val out = service(Http { throw JellyfinException("HTTP 503", 503) }, s).enrich(listOf(video("a", "Billie Jean")))
        assertEquals(1, out.skippedFailed)
        assertTrue(s.load().isEmpty())
    }

    @Test
    fun fiveFailuresInARowEndThePass() {
        val http = Http { throw JellyfinException("HTTP 503", 503) }
        val out = service(http).enrich((1..30).map { video("v$it", "Song $it") })
        assertTrue(out.aborted)
        assertEquals(5, http.urls.size)
    }

    @Test
    fun noMatchStillCachesAParsedRowAndStampsDeezer() {
        val http = Http {
            when {
                it.contains("/recording?query") -> """{"recordings":[]}"""
                it.contains("/search") -> """{"data":[]}"""
                else -> error(it)
            }
        }
        val s = store()
        service(http, s).enrich(listOf(video("a", "Obscure Song", artist = null)))
        val row = s.load().getValue("a")
        assertEquals("Obscure Song", row.recordingTitle)
        assertNotNull("a dead lookup must not repeat every launch", row.deezerCheckedAt)
    }

    @Test
    fun nonMusicItemsAreIgnoredAndAppliedCacheChangesOnlyMatchingItems() {
        val http = happy()
        val movie = video("m", "Heat").copy(librarySource = LibrarySource.MOVIE)
        assertEquals(0, service(http).enrich(listOf(movie)).candidates)
        assertTrue(http.urls.isEmpty())

        val cache = mapOf("a" to MusicVideoEnrichment("a", "Billie Jean", "Michael Jackson", genres = listOf("Pop")))
        val applied = MusicEnrichmentService.apply(listOf(video("a", "x.mp4"), movie), cache)
        assertEquals("Billie Jean", applied[0].title)
        assertEquals(movie, applied[1])
    }

    // -- Throttle, item types ------------------------------------------------------------

    @Test
    fun throttleSpacesCalls() {
        var t = 0L; val slept = ArrayList<Long>()
        val th = Throttle(1000, nowMs = { t }, sleepMs = { slept.add(it); t += it })
        th.await(); t += 300; th.await(); th.await()
        assertEquals(listOf(700L, 1000L), slept)
    }

    @Test
    fun musicVideoTypeIsRequestedOnlyWhenTheServerHasSome() {
        assertEquals("Movie", JellyfinMapping.itemTypesFor(isMusicSection = true, musicVideoCount = 0))
        assertEquals("Movie,MusicVideo", JellyfinMapping.itemTypesFor(isMusicSection = true, musicVideoCount = 3))
        assertEquals("Movie", JellyfinMapping.itemTypesFor(isMusicSection = false, musicVideoCount = 3))
    }
}
