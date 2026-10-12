package io.github.madmax3553.staticgroot.enrichment

import io.github.madmax3553.staticgroot.channels.MediaEnrichment
import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import io.github.madmax3553.staticgroot.jellyfin.JellyfinException
import io.github.madmax3553.staticgroot.jellyfin.JellyfinHttp
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

class EnrichmentServiceTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Answers by URL; records every request. Thread-safe, the service runs ten at once. */
    private class FakeHttp(val handler: (String) -> String) : JellyfinHttp {
        val urls = ConcurrentLinkedQueue<String>()
        override fun get(url: String, headers: Map<String, String>): String { urls.add(url); return handler(url) }
        override fun post(url: String, headers: Map<String, String>, body: String) = error("unused")
        fun count(part: String) = urls.count { it.contains(part) }
    }

    private val tmdbBody = """{"keywords":{"keywords":[{"name":"biopic"}],"results":[{"name":"sitcom"}]}}"""
    private val omdbBody = """{"Response":"True","imdbRating":"7.5","imdbVotes":"9,000","Metascore":"70","Ratings":[]}"""

    private fun movie(id: String, tmdb: String?, imdb: String? = null) =
        MediaItem(id = id, title = id, type = MediaType.MOVIE, tmdbID = tmdb, imdbID = imdb)

    private fun episode(id: String, tmdb: String?, imdb: String? = null) =
        MediaItem(id = id, title = "Show", type = MediaType.EPISODE, tmdbID = tmdb, imdbID = imdb)

    private fun store() = EnrichmentStore(File(tmp.root, "cache.json"))

    private fun service(http: FakeHttp, omdb: Boolean = true, store: EnrichmentStore = store(), now: () -> Long = { 1_000_000_000L }, budget: Long = 60_000) =
        EnrichmentService(
            tmdb = TmdbClient(http, "T"), omdb = if (omdb) OmdbClient(http, "O") else null,
            store = store, nowMs = now, maxConcurrency = 4, budgetMs = budget,
        )

    private fun okHttp() = FakeHttp { if (it.contains("themoviedb")) tmdbBody else omdbBody }

    @Test
    fun episodesOfOneShowShareOneRequestOnTheTvEndpoint() {
        val http = okHttp()
        val out = service(http).enrich(listOf(episode("e1", "50", "tt50"), episode("e2", "50"), episode("e3", "50")))
        assertEquals(1, http.count("themoviedb"))
        assertEquals(1, http.count("/tv/50"))
        assertEquals(listOf("sitcom"), out.records.getValue("50").keywords)
        assertEquals(1, out.uniqueTitles)
    }

    @Test
    fun aLaterItemSuppliesAMissingImdbId() {
        val http = okHttp()
        val out = service(http).enrich(listOf(movie("a", "7", null), movie("b", "7", "tt7")))
        assertEquals(1, http.count("omdbapi"))
        assertEquals(7.5, out.records.getValue("7").imdbRating!!, 0.0)
    }

    @Test
    fun itemsWithoutATmdbIdAreIgnored() {
        val http = okHttp()
        val out = service(http).enrich(listOf(movie("a", null, "tt1")))
        assertTrue(http.urls.isEmpty())
        assertTrue(out.records.isEmpty())
    }

    @Test
    fun secondRunIsServedFromDiskWithNoRequests() {
        val items = listOf(movie("a", "1", "tt1"), movie("b", "2", "tt2"))
        val http1 = okHttp()
        service(http1).enrich(items)
        assertEquals(4, http1.urls.size)

        val http2 = okHttp()
        val out = service(http2).enrich(items)
        assertTrue(http2.urls.isEmpty())
        assertEquals(2, out.fromCache)
        assertEquals(2, out.records.size)
    }

    @Test
    fun cachedTitleMissingOmdbIsBackfilledButOnlyOnceAMonth() {
        val s = store()
        s.save(listOf(MediaEnrichment(tmdbID = "1", imdbID = "tt1", keywords = listOf("k"), omdbCheckedAt = 0)))
        val http = okHttp()
        val out = service(http, store = s).enrich(listOf(movie("a", "1", "tt1")))
        assertEquals(0, http.count("themoviedb"))
        assertEquals(1, http.count("omdbapi"))
        assertEquals(1, out.omdbBackfilled)
        assertEquals(listOf("k"), out.records.getValue("1").keywords)

        // A title OMDb has no rating for is not asked again straight away.
        val s2 = store()
        s2.save(listOf(MediaEnrichment(tmdbID = "2", imdbID = "tt2", omdbCheckedAt = 1_000_000L - 10)))
        val http2 = okHttp()
        service(http2, store = s2, now = { 1_000_000_000L }).enrich(listOf(movie("b", "2", "tt2")))
        assertTrue("asked again within the month", http2.urls.isEmpty())
    }

    @Test
    fun omdbQuotaExhaustedStopsOmdbButKeepsTmdb() {
        val http = FakeHttp {
            if (it.contains("themoviedb")) tmdbBody else """{"Response":"False","Error":"Request limit reached!"}"""
        }
        val items = (1..30).map { movie("m$it", "$it", "tt$it") }
        val out = service(http).enrich(items)
        assertTrue(out.omdbDisabled)
        assertEquals(30, out.records.size)
        assertTrue("omdb should stop early, saw ${http.count("omdbapi")}", http.count("omdbapi") < 30)
        assertTrue(out.records.values.all { it.imdbRating == null })
        // Not recorded as answered: a quota failure is not an answer.
        assertTrue(out.records.values.all { it.omdbCheckedAt == 0L })
    }

    @Test
    fun badTmdbKeyStopsTmdbAndNothingIsCached() {
        val http = FakeHttp { throw JellyfinException("HTTP 401", 401) }
        val items = (1..50).map { movie("m$it", "$it") }
        val s = store()
        val out = service(http, store = s).enrich(items)
        assertTrue(out.tmdbDisabled)
        assertTrue(out.records.isEmpty())
        assertTrue("kept hammering a bad key: ${http.urls.size}", http.urls.size < 50)
    }

    @Test
    fun aFailedTitleIsNotCachedSoItRetriesNextLaunch() {
        val http = FakeHttp { if (it.contains("/movie/2")) throw JellyfinException("HTTP 500", 500) else tmdbBody }
        val s = store()
        val out = service(http, omdb = false, store = s).enrich(listOf(movie("a", "1"), movie("b", "2")))
        assertEquals(1, out.fetchedNew)
        assertEquals(1, out.tmdbFailures)
        assertNotNull(s.load()["1"]); assertNull(s.load()["2"])
    }

    @Test
    fun theTimeBudgetDefersTheRestToALaterLaunch() {
        val http = okHttp()
        var t = 0L
        // Every clock read advances one second, so a one-second budget is gone after the start.
        val out = service(http, omdb = false, now = { t += 1000; t }, budget = 1_000).enrich((1..20).map { movie("m$it", "$it") })
        assertTrue("fetched ${out.fetchedNew}", out.fetchedNew < 20)
        assertTrue(out.deferred > 0)
    }

    @Test
    fun withoutKeysTheCacheIsStillUsed() {
        val s = store()
        s.save(listOf(MediaEnrichment(tmdbID = "1", keywords = listOf("k"))))
        val out = EnrichmentService(tmdb = null, omdb = null, store = s).enrich(listOf(movie("a", "1"), movie("b", "2")))
        assertEquals(listOf("k"), out.records.getValue("1").keywords)
        assertFalse(out.records.containsKey("2"))
    }

    @Test
    fun aCorruptCacheFileReadsAsEmpty() {
        File(tmp.root, "cache.json").writeText("{not json")
        assertTrue(store().load().isEmpty())
    }
}
