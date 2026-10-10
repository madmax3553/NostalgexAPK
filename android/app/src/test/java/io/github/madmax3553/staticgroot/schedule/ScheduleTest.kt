package io.github.madmax3553.staticgroot.schedule

import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Random-number golden vectors come from an independent Python implementation of the Swift
 * algorithm (see SwiftRandom.kt). Schedule tests check properties, not exact lineups.
 */
class SwiftRandomTest {

    @Test
    fun splitmixMatchesTheReferenceVector() {
        assertEquals(10451216379200822465uL, SwiftRandom.splitmix64(1).toULong())
    }

    @Test
    fun seededStreamMatchesTheReferenceVector() {
        val rng = SeededRng(42)
        val first = List(3) { rng.next().toULong() }
        assertEquals(listOf(18108192690585582856uL, 6830302529404445810uL, 7514410519785295290uL), first)
    }

    @Test
    fun boundedDrawMatchesTheReferenceVector() {
        val rng = SeededRng(42)
        assertEquals(listOf(9, 3, 4, 5, 3), List(5) { rng.uniform(10) })
    }

    @Test
    fun shuffleMatchesTheReferenceVector() {
        val a = (0..9).toMutableList()
        a.swiftShuffle(SeededRng(7))
        assertEquals(listOf(0, 7, 6, 1, 5, 9, 2, 3, 4, 8), a)
    }
}

class PoolOrderingTest {

    private fun movie(id: String, title: String = id, minutes: Int = 90, addedAt: Long = 0) = MediaItem(
        id = id, title = title, type = MediaType.MOVIE, duration = minutes, addedAt = addedAt,
    )

    private fun episode(id: String, show: String, minutes: Int = 30) = MediaItem(
        id = id, title = show, type = MediaType.EPISODE, duration = minutes,
    )

    @Test
    fun sameShowNeverRunsMoreThanTwoInARow() {
        // Three shows of four episodes each. With equal sizes every show is still in rotation
        // when the others run out, so runs stay at two. With one show left, Swift takes its
        // items in pairs back to back, so the rule only holds while several shows remain.
        val items = (1..4).flatMap { k -> listOf("A", "B", "C").map { episode("$it$k", it) } }
        val ordered = PoolOrdering.interleaveByShow(items, seed = 5, dayNumber = 20000)
        var run = 1
        for (i in 1 until ordered.size) {
            run = if (ordered[i].title == ordered[i - 1].title) run + 1 else 1
            assertTrue("run longer than 2 at $i", run <= 2)
        }
        assertEquals(items.size, ordered.size)
    }

    @Test
    fun interleaveIsDeterministicForTheSameDay() {
        val items = (1..8).map { episode("e$it", "S${it % 2}") }
        assertEquals(
            PoolOrdering.interleaveByShow(items, 3, 100).map { it.id },
            PoolOrdering.interleaveByShow(items, 3, 100).map { it.id },
        )
    }

    @Test
    fun premiereOffsetsFallInsideTheEveningWindowOnFifteenMinuteSlots() {
        for (k in 1..200) {
            val off = PoolOrdering.premiereOffset(channelId = k, ratingKey = "rk$k")
            assertTrue(off >= 19 * 3600 && off < 24 * 3600)
            assertEquals(0L, (off - 19 * 3600) % (15 * 60))
        }
    }

    @Test
    fun sequelPartsAreAdjacentAndInPartOrder() {
        val items = listOf(
            movie("x", "Alien"),
            movie("p2", "Mockingjay Part 2"),
            movie("y", "Heat"),
            movie("p1", "Mockingjay Part 1"),
        )
        val ids = PoolOrdering.groupSequelParts(items).map { it.id }
        assertEquals(ids.size, 4)
        // Part 1 directly before Part 2, and the family sits where its earliest member was.
        assertEquals(ids.indexOf("p1") + 1, ids.indexOf("p2"))
        assertEquals(1, ids.indexOf("p1"))
    }

    @Test
    fun loneSequelMarkerStaysPut() {
        val items = listOf(movie("a", "Heat"), movie("b", "Rocky Part 2"), movie("c", "Alien"))
        assertEquals(listOf("a", "b", "c"), PoolOrdering.groupSequelParts(items).map { it.id })
    }
}

class DailyPackerTest {

    /** 2026-10-10 00:00 UTC. */
    private val dayStart = 1_791_590_400L

    private fun pool(n: Int, minutes: Int = 90) = (1..n).map {
        MediaItem(id = "m$it", title = "Film $it", type = MediaType.MOVIE, duration = minutes)
    }

    private fun pack(
        items: List<MediaItem>,
        yesterdayLast: String? = null,
        aired: Set<String> = emptySet(),
        premiere: Boolean = false,
    ) = DailyPacker.packDay(
        pool = items, dayStartUnix = dayStart, dayNumber = 20_000, channelId = 9,
        isPremiereChannel = premiere, yesterdayAiredKeys = aired, yesterdayLastItemId = yesterdayLast,
    )

    @Test
    fun aDayIsFilledWithContiguousBlocks() {
        val blocks = pack(pool(10))
        assertEquals(dayStart, blocks.first().startUnix)
        for (i in 1 until blocks.size) assertEquals(blocks[i - 1].endUnix, blocks[i].startUnix)
        assertTrue(blocks.last().endUnix >= dayStart + 86400)
    }

    @Test
    fun aSmallPoolWrapsAroundToFillTheWholeDay() {
        val blocks = pack(pool(2, minutes = 30))
        assertEquals(dayStart + 86400, blocks.last().endUnix)
        // 24 hours of 30-minute films. The wraparound may open partway into a film, which
        // makes the first block partial, so 48 or 49 blocks both cover the day.
        assertTrue(blocks.size == 48 || blocks.size == 49)
        for (i in 1 until blocks.size) assertEquals(blocks[i - 1].endUnix, blocks[i].startUnix)
    }

    @Test
    fun theDayOpensWithADifferentTitleThanYesterdayEnded() {
        val first = pack(pool(10)).first().item.id
        val seamSafe = pack(pool(10), yesterdayLast = first).first().item.id
        assertNotEquals(first, seamSafe)
    }

    @Test
    fun airedTitlesAreNotRepeatedWhileUnplayedOnesRemain() {
        val items = pool(6)
        val aired = setOf("m1", "m2")
        val blocks = pack(items, aired = aired)
        val firstFour = blocks.take(4).map { it.item.id }
        assertTrue(firstFour.none { it in aired })
    }

    @Test
    fun packingIsDeterministic() {
        assertEquals(pack(pool(7)), pack(pool(7)))
    }

    @Test
    fun aPremiereLandsInTheEveningOnPremiereChannels() {
        val fresh = MediaItem(
            id = "new", title = "New Film", type = MediaType.MOVIE, duration = 90,
            addedAt = dayStart - 86_400L,
        )
        // A pool longer than 19 hours, so the premiere target falls inside the day. Shorter
        // pools append the premiere at the end of the primary pass, as the Swift code does.
        val long = pool(15) + fresh
        val blocks = pack(long, premiere = true)
        val start = blocks.first { it.item.id == "new" }.startUnix - dayStart
        assertTrue("premiere at ${start / 3600} h", start >= 18 * 3600 && start < 24 * 3600)
    }
}

class ChannelSchedulerTest {

    private val zone = ZoneOffset.UTC
    private val pool = (1..6).map {
        MediaItem(id = "m$it", title = "Film $it", type = MediaType.MOVIE, duration = 60)
    }

    @Test
    fun nowPlayingContainsNowAndUpNextFollowsIt() {
        val now = 1_791_633_600L + 17 * 60 // 12:17 UTC on the test day
        val s = ChannelScheduler(zone).schedule(channelId = 1, pool = pool, isPremiere = false, nowUnix = now)!!
        val np = s.nowPlaying!!
        assertTrue(np.startUnix <= now && now < np.endUnix)
        assertEquals(now - np.startUnix, s.elapsedSeconds)
        assertEquals(s.entries[s.entries.indexOf(np) + 1], s.upNext)
        assertTrue(s.isCurrent(now))
    }

    @Test
    fun aStaleScheduleFailsTheCurrentCheck() {
        val now = 1_791_633_600L
        val s = ChannelScheduler(zone).schedule(channelId = 1, pool = pool, isPremiere = false, nowUnix = now)!!
        assertTrue(!s.isCurrent(now + 3 * 3600))
    }

    @Test
    fun emptyPoolHasNoSchedule() {
        assertEquals(null, ChannelScheduler(zone).schedule(1, emptyList(), false, 1_791_633_600L))
    }
}
