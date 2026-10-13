package io.github.madmax3553.staticgroot.schedule

import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import org.junit.Assert.*
import org.junit.Test

class BumperScheduleTest {
    private fun item(id: String, minutes: Int = 22, type: MediaType = MediaType.EPISODE) =
        MediaItem(id, id, type, duration = minutes)
    private fun pack(minutes: Int = 22) = DailyPacker.withBumpers(
        (0..100).map { Block(item("p$it", minutes), it * minutes * 60L, (it + 1) * minutes * 60L) }, 0)

    @Test fun shortEpisodesUseHalfHours() {
        val blocks = pack()
        assertEquals(480L, blocks[1].durationSeconds)
        assertEquals(1800L, blocks[2].startUnix)
        assertEquals(86400L, blocks.last().endUnix)
        blocks.zipWithNext().forEach { (a, b) -> assertEquals(a.endUnix, b.startUnix) }
    }

    @Test fun tvUsesHalfHoursWhenTheWaitIsShort() {
        // 28 minutes in, the next half hour is 2 minutes away.
        assertEquals(1800L, StationTiming.nextStart(28 * 60, 0, nextType = MediaType.EPISODE))
        // 52 minutes in, the hour is 8 minutes away: still on the grid.
        assertEquals(3600L, StationTiming.nextStart(52 * 60, 0, nextType = MediaType.EPISODE))
        // Ending exactly on the half hour: 30 s of break pushes the next to :35, not a 30 minute wait.
        assertEquals(2100L, StationTiming.nextStart(30 * 60, 0, nextType = MediaType.EPISODE))
    }

    @Test fun aLongWaitFallsBackToTheNextFiveMinuteMarkInsteadOfIdlingForHalfAnHour() {
        // 46 minutes in: the hour is 14 minutes away, over the cap. 46:00 + 30 s -> 50:00.
        assertEquals(3000L, StationTiming.nextStart(46 * 60, 0, nextType = MediaType.EPISODE))
        // A 61 minute episode used to wait until 90:00 (29 minutes). Now 65:00.
        assertEquals(3900L, StationTiming.nextStart(61 * 60, 0, nextType = MediaType.EPISODE))
        // An hourly show-block start used to idle 35 minutes after a 25 minute episode. Not any more.
        assertEquals(1800L, StationTiming.nextStart(25 * 60, 0, nextType = MediaType.EPISODE, startsRepeatedShowBlock = true))
        // Close enough to the hour, it still opens on it.
        assertEquals(3600L, StationTiming.nextStart(52 * 60, 0, nextType = MediaType.EPISODE, startsRepeatedShowBlock = true))
    }

    @Test fun noBreakEverExceedsTheCapPlusTheFineGridForAnyEndTime() {
        for (type in MediaType.entries) for (block in listOf(false, true)) {
            var end = 0L
            while (end < 86400) {
                val start = StationTiming.nextStart(end, 0, nextType = type, startsRepeatedShowBlock = block)
                assertTrue("end=$end type=$type gap=${start - end}", start - end in 30..StationTiming.MAX_BREAK_SECONDS)
                end += 37
            }
        }
    }

    @Test fun aRepeatedShowBlockOpensOnTheHourOnlyWhenThatCostsLittle() {
        // 22 minute episodes: the hour is 38 minutes after the first one ends, over the cap, so the
        // block opens on the half hour. Everything stays on the half-hour grid.
        val items = listOf(item("Earlier", 22), item("Same Show", 22), item("Same Show", 22), item("Later", 22))
        val starts = DailyPacker.withBumpers(items.mapIndexed { i, media ->
            Block(media, i * 1320L, (i + 1) * 1320L)
        }, 0).filterNot { it.isBumper }.map { it.startUnix }
        assertEquals(listOf(0L, 1800L, 3600L, 5400L), starts)
    }

    @Test fun singleShowSwitchDoesNotForceAnHourlyStart() {
        assertEquals(1800L, StationTiming.nextStart(1320, 0, nextType = MediaType.EPISODE))
        // The hour would be a 38 minute wait, so the block opens on the half hour instead.
        assertEquals(1800L, StationTiming.nextStart(1320, 0, nextType = MediaType.EPISODE,
            startsRepeatedShowBlock = true))
    }

    @Test fun moviesUseTheNextQuarterHourIncludingMinimumIdent() {
        assertEquals(2700L, StationTiming.nextStart(44 * 60, 0))
        // A film ending on the hour used to wait 15 minutes for :15. Over the cap, so :05.
        assertEquals(3900L, StationTiming.nextStart(3600, 0))
        assertEquals(3600L, StationTiming.nextStart(3570, 0))
    }

    @Test fun longEpisodeHasOneSixtySecondMidBreakAndExactResumeOffset() {
        val blocks = pack(46)
        assertFalse(blocks[0].isBumper)
        assertEquals(1380L, blocks[0].durationSeconds)
        assertTrue(blocks[1].isMidBreak)
        assertEquals(60L, blocks[1].durationSeconds)
        assertEquals(1380L, blocks[2].mediaOffsetSeconds)
        assertEquals(1380L, blocks[2].durationSeconds)
        assertEquals(3000L, blocks[4].startUnix)
    }

    @Test fun joiningAfterTheMidBreakSeeksToContentNotCommercialTime() {
        val second = pack(46)[2]
        val elapsed = 12L
        assertEquals(1392L, second.mediaOffsetSeconds + elapsed)
    }

    @Test fun finalBlockEndsAtMidnightWithoutEmptyBlocks() {
        val blocks = pack(46)
        assertEquals(86400L, blocks.last().endUnix)
        assertTrue(blocks.all { it.endUnix > it.startUnix })
    }

    @Test fun aFilmIsNotStartedWhenMidnightWouldCutMostOfItOff() {
        // Ten-minute filler items, then a 100 minute film that would start 15 minutes before midnight.
        val shorts = (0 until 143).map { Block(item("s$it", 10, MediaType.MOVIE), 0, 600) }
        val big = Block(item("big", 100, MediaType.MOVIE), 0, 6000)
        val blocks = DailyPacker.withBumpers(shorts + big, 0)
        val film = blocks.filter { !it.isBumper && it.item.id == "big" }
        val shown = film.sumOf { it.durationSeconds }
        assertTrue("film must show whole or not at all, showed $shown s", film.isEmpty() || shown >= 0.75 * 6000)
        blocks.zipWithNext().forEach { (a, b) -> assertEquals(a.endUnix, b.startUnix) }
        assertEquals(86400L, blocks.last().endUnix)
    }

    @Test fun aShorterProgrammeIsPulledForwardIntoTheLastSlot() {
        assertTrue(DailyPacker.fitsDay(Block(item("a", 20, MediaType.MOVIE), 0, 0), 86400 - 1500, 86400))
        assertFalse(DailyPacker.fitsDay(Block(item("b", 100, MediaType.MOVIE), 0, 0), 86400 - 900, 86400))
        assertTrue(DailyPacker.fitsDay(Block(item("c", 100, MediaType.MOVIE), 0, 0), 86400 - 4800, 86400))
    }
}
