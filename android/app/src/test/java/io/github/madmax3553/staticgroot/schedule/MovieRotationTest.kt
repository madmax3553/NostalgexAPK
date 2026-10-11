package io.github.madmax3553.staticgroot.schedule

import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import org.junit.Assert.*
import org.junit.Test

class MovieRotationTest {
    @Test fun movieVersionsAreDeduplicatedAndRepeatsAlternateEvenly() {
        val a = MediaItem("a", "Film A", MediaType.MOVIE, duration = 90, tmdbID = "1")
        val b = MediaItem("b", "Film B", MediaType.MOVIE, duration = 90, tmdbID = "2")
        val otherVersion = a.copy(id = "a-hd")
        val raw = listOf(a, b, otherVersion, a, b).mapIndexed { i, item -> Block(item, i * 5400L, (i + 1) * 5400L) }
        val packed = DailyPacker.withBumpers(raw, 0)
        val starts = packed.filter { !it.isBumper && it.mediaOffsetSeconds == 0L }
        assertTrue(starts.size > 2)
        assertTrue(starts.all { it.item.id in listOf("a", "b") })
        starts.zipWithNext().forEach { (x, y) -> assertNotEquals(x.item.tmdbID, y.item.tmdbID) }
        val counts = starts.groupingBy { it.item.id }.eachCount().values
        assertTrue(counts.max() - counts.min() <= 1)
        assertEquals(86400L, packed.last().endUnix)
        packed.zipWithNext().forEach { (x, y) -> assertEquals(x.endUnix, y.startUnix) }
    }

    @Test fun singleMovieHasAShortStationBreakBeforeItRepeats() {
        val movie = MediaItem("a", "Only Film", MediaType.MOVIE, duration = 90)
        val packed = DailyPacker.withBumpers(listOf(Block(movie, 0, 5400)), 0)
        val starts = packed.filter { !it.isBumper && it.mediaOffsetSeconds == 0L }
        assertTrue(starts.size > 1)
        val contentEnd = packed.first { it.mediaOffsetSeconds > 0 && !it.isBumper }.endUnix
        assertTrue(starts[1].startUnix - contentEnd >= 300)
        assertTrue(starts[1].startUnix - contentEnd < 1200)
    }

    @Test fun publishedDayWinsOverNewPoolAndHistory() {
        val scheduler = ChannelScheduler(java.time.ZoneOffset.UTC)
        val movie = MediaItem("a", "Film", MediaType.MOVIE, duration = 90)
        val published = listOf(Block(movie, 0, 5400))
        scheduler.installPublished(1, 0, published)
        assertEquals(published, scheduler.dayBlocks(1, listOf(movie.copy(id = "new")), false, 3600))
    }
}
