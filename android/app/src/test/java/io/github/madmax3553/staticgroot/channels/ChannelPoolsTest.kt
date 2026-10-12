package io.github.madmax3553.staticgroot.channels

import io.github.madmax3553.staticgroot.jellyfin.JellyfinItem
import io.github.madmax3553.staticgroot.jellyfin.JellyfinMapping
import io.github.madmax3553.staticgroot.jellyfin.Studio
import java.io.File
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPoolsTest {

    private val config: ChannelConfig by lazy {
        ChannelConfigLoader.parse(File("../../channels.json").readText())
    }

    private val clock = FilterClock(nowEpochSeconds = 1_791_633_600L, zone = ZoneOffset.UTC)

    @Test
    fun everyConfiguredChannelGetsAPool() {
        val pools = ChannelPools.buildAll(config, items = emptyList(), clock = clock)
        assertEquals(config.channels.size, pools.size)
        assertTrue(pools.all { it.pool.isEmpty() })
    }

    @Test
    fun aMovieIsOnlyScheduledOnChannelsWhoseRulesAcceptIt() {
        val film = MediaItem(
            id = "f1", title = "Plain Drama", type = MediaType.MOVIE, duration = 100,
            genres = listOf("Drama"), year = 1990, contentRating = "PG",
        )
        val pools = ChannelPools.buildAll(config, listOf(film), clock = clock)
        val holders = pools.filter { p -> p.pool.any { it.id == "f1" } }
        assertTrue("film should land on at least one channel", holders.isNotEmpty())
        // Music-video channels must never take a movie.
        assertFalse(holders.any { it.definition.rules?.source == "musicVideo" })
    }

    @Test
    fun episodeTakesTheSeriesTitleGenresAndSeTag() {
        val show = JellyfinItem(
            Id = "series1", Name = "Sample Show", Genres = listOf("Comedy"),
            ProductionYear = 1995, ProviderIds = mapOf("Tmdb" to "4242"),
            Studios = listOf(Studio("Sample Studio")),
        )
        val ep = JellyfinItem(
            Id = "ep1", Name = "Pilot", RunTimeTicks = 30 * JellyfinMapping.TICKS_PER_MINUTE,
            ParentIndexNumber = 1, IndexNumber = 4, PremiereDate = "1995-09-22T00:00:00Z",
        )
        val m = JellyfinMapping.toEpisode(ep, show)!!
        assertEquals(MediaType.EPISODE, m.type)
        assertEquals("Sample Show", m.title)
        assertEquals("Pilot", m.episodeTitle)
        assertEquals("S01E04", m.seTag)
        assertEquals(listOf("Comedy"), m.genres)
        assertEquals("4242", m.tmdbID)
        assertEquals(LibrarySource.TV, m.librarySource)
        assertEquals("1995-09-22", m.originallyAvailableAt)
    }

    @Test
    fun episodeWithoutRuntimeIsDropped() {
        val show = JellyfinItem(Id = "s", Name = "S")
        assertEquals(null, JellyfinMapping.toEpisode(JellyfinItem(Id = "e", Name = "E"), show))
    }

    // -- Enrichment, keyed by TMDB id as on tvOS ---------------------------------------

    private fun poolIds(pools: List<ChannelRuntime>, channelId: Int) =
        pools.first { it.id == channelId }.pool.map { it.id }

    @Test
    fun keywordChannelFillsOnlyWhenEnrichmentArrivesKeyedByTmdbId() {
        val film = MediaItem(id = "jf-item-1", title = "Some Film", type = MediaType.MOVIE, duration = 100,
            genres = listOf("Drama"), year = 1990, contentRating = "PG", tmdbID = "555")
        // BIOPICS (201) matches on keywords alone.
        assertEquals(emptyList<String>(), poolIds(ChannelPools.buildAll(config, listOf(film), clock = clock), 201))

        val byTmdb = mapOf("555" to MediaEnrichment(keywords = listOf("biopic")))
        assertEquals(listOf("jf-item-1"), poolIds(ChannelPools.buildAll(config, listOf(film), enrichment = byTmdb, clock = clock), 201))

        // Keyed by the server's item id, as the old lookup did, finds nothing.
        val byItemId = mapOf("jf-item-1" to MediaEnrichment(keywords = listOf("biopic")))
        assertEquals(emptyList<String>(), poolIds(ChannelPools.buildAll(config, listOf(film), enrichment = byItemId, clock = clock), 201))
    }

    @Test
    fun omdbGatedChannelNeedsRatingAndVotes() {
        val film = MediaItem(id = "f", title = "Foreign Thing", type = MediaType.MOVIE, duration = 100,
            genres = listOf("Drama"), year = 1990, contentRating = "R", tmdbID = "9")
        val kw = listOf("world cinema")
        // FOREIGN FILMS (212): keyword plus imdbRatingMin 6.5 and imdbVotesMin 5000.
        fun run(e: MediaEnrichment) = poolIds(ChannelPools.buildAll(config, listOf(film), enrichment = mapOf("9" to e), clock = clock), 212)
        assertEquals(listOf("f"), run(MediaEnrichment(keywords = kw, imdbRating = 7.0, imdbVotes = 6000)))
        assertEquals(emptyList<String>(), run(MediaEnrichment(keywords = kw, imdbRating = 7.0, imdbVotes = 100)))
        assertEquals(emptyList<String>(), run(MediaEnrichment(keywords = kw)))
    }

    @Test
    fun episodesOfAShowAllSeeTheShowsEnrichment() {
        val show = JellyfinItem(Id = "s", Name = "Sample Show", Genres = listOf("Comedy"),
            ProviderIds = mapOf("Tmdb" to "77", "Imdb" to "tt77"))
        val eps = (1..2).mapNotNull {
            JellyfinMapping.toEpisode(JellyfinItem(Id = "e$it", Name = "E$it", RunTimeTicks = 22 * JellyfinMapping.TICKS_PER_MINUTE,
                ParentIndexNumber = 1, IndexNumber = it), show)
        }
        assertEquals(listOf("tt77", "tt77"), eps.map { it.imdbID })
        val e = mapOf("77" to MediaEnrichment(keywords = listOf("x")))
        assertEquals(2, eps.count { e.forItem(it) != null })
    }
}
