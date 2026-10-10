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
}
