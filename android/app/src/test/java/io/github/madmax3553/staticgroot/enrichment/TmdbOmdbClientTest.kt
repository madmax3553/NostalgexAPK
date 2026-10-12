package io.github.madmax3553.staticgroot.enrichment

import io.github.madmax3553.staticgroot.jellyfin.JellyfinException
import io.github.madmax3553.staticgroot.jellyfin.JellyfinHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/* Synthetic JSON shaped like TMDB and OMDb responses. Each test names the tvOS behaviour it mirrors. */
class TmdbOmdbClientTest {

    private class Http(private val body: String? = null, private val status: Int? = null) : JellyfinHttp {
        val urls = ArrayList<String>()
        override fun get(url: String, headers: Map<String, String>): String {
            urls.add(url)
            if (status != null) throw JellyfinException("HTTP $status", status)
            return body!!
        }
        override fun post(url: String, headers: Map<String, String>, body: String) = error("unused")
    }

    private val movieJson = """{"id":1,"genres":[{"name":"Drama"}],
        "production_companies":[{"name":"Sample Pictures"},{"name":"Other Co"}],
        "keywords":{"keywords":[{"name":"Biopic"},{"name":"war"}]},
        "networks":[{"name":"Ignored Network"}]}"""

    private val tvJson = """{"networks":[{"name":"Sample Network"}],
        "production_companies":[{"name":"TV Co"}],
        "keywords":{"results":[{"name":"sitcom"}]}}"""

    @Test
    fun movieKeywordsAreUnderKeywordsKeywords() {
        val http = Http(movieJson)
        val e = TmdbClient(http, "K").fetch("1", "tt1", "movie", 100)
        assertEquals(listOf("Biopic", "war"), e.keywords)
        assertEquals(listOf("Sample Pictures", "Other Co"), e.productionCompanies)
        assertEquals("tt1", e.imdbID)
        assertEquals(100L, e.fetchedAt)
        assertTrue(http.urls.single().contains("/movie/1?"))
        assertTrue(http.urls.single().contains("append_to_response=keywords"))
    }

    @Test
    fun tvKeywordsAreUnderResultsAndUseTheTvEndpoint() {
        val http = Http(tvJson)
        val e = TmdbClient(http, "K").fetch("9", null, "tv", 5)
        assertEquals(listOf("sitcom"), e.keywords)
        assertEquals(listOf("Sample Network"), e.networks)
        assertTrue(http.urls.single().contains("/tv/9?"))
    }

    @Test
    fun missingSectionsReadAsEmptyNotAnError() {
        val e = TmdbClient(Http("{}"), "K").fetch("1", null, "movie", 0)
        assertTrue(e.keywords.isEmpty() && e.networks.isEmpty() && e.productionCompanies.isEmpty())
    }

    @Test
    fun tmdb429IsRateLimitedAndOtherStatusesPassThrough() {
        try { TmdbClient(Http(status = 429), "K").fetch("1", null, "movie", 0); fail() } catch (_: TmdbRateLimited) {}
        try {
            TmdbClient(Http(status = 401), "K").fetch("1", null, "movie", 0); fail()
        } catch (e: JellyfinException) { assertEquals(401, e.status) }
    }

    @Test
    fun omdbParsesRatingsVotesAwardsAndStripsPercentAndCommas() {
        val body = """{"Response":"True","imdbRating":"7.8","imdbVotes":"1,234,567","Metascore":"81",
            "Awards":"Won 2 Oscars. 14 wins total.",
            "Ratings":[{"Source":"Internet Movie Database","Value":"7.8/10"},
                       {"Source":"Rotten Tomatoes","Value":"93%"}]}"""
        val r = OmdbClient(Http(body), "K").fetch("tt1")
        assertEquals(7.8, r.imdbRating!!, 0.0)
        assertEquals(1_234_567, r.imdbVotes)
        assertEquals(93, r.rottenTomatoesScore)
        assertEquals(81, r.metacriticScore)
        assertEquals("Won 2 Oscars. 14 wins total.", r.awards)
    }

    @Test
    fun omdbNaFieldsAreAbsent() {
        val body = """{"Response":"True","imdbRating":"N/A","imdbVotes":"N/A","Metascore":"N/A","Awards":"N/A","Ratings":[]}"""
        val r = OmdbClient(Http(body), "K").fetch("tt1")
        assertNull(r.imdbRating); assertNull(r.imdbVotes); assertNull(r.metacriticScore)
        assertNull(r.awards); assertNull(r.rottenTomatoesScore)
    }

    @Test
    fun omdbErrorTextDecidesRateLimitVersusNotFound() {
        try {
            OmdbClient(Http("""{"Response":"False","Error":"Request limit reached!"}"""), "K").fetch("tt1"); fail()
        } catch (_: OmdbRateLimited) {}
        try {
            OmdbClient(Http("""{"Response":"False","Error":"Incorrect IMDb ID."}"""), "K").fetch("tt1"); fail()
        } catch (_: OmdbNotFound) {}
        try {
            OmdbClient(Http("""{"Response":"False","Error":"Something else"}"""), "K").fetch("tt1"); fail()
        } catch (e: JellyfinException) { assertFalse(e.message.orEmpty().contains("K")) }
    }
}
