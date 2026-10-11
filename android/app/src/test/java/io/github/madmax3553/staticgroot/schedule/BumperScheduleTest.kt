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

    @Test fun tvUsesHalfHoursRegardlessOfWaitLength() {
        assertEquals(3600L, StationTiming.nextStart(46 * 60, 0, nextType = MediaType.EPISODE))
        assertEquals(3600L, StationTiming.nextStart(44 * 60, 0, nextType = MediaType.EPISODE))
        assertEquals(3600L, StationTiming.nextStart(45 * 60 + 60, 0, nextType = MediaType.EPISODE))
    }

    @Test fun aRepeatedShowBlockStartsHourlyButItsSecondEpisodeUsesHalfHours() {
        val items = listOf(item("Earlier", 22), item("Same Show", 22),
            item("Same Show", 22), item("Later", 22))
        val blocks = DailyPacker.withBumpers(items.mapIndexed { i, media ->
            Block(media, i * 1320L, (i + 1) * 1320L)
        }, 0).filterNot { it.isBumper }
        assertEquals(listOf(0L, 3600L, 5400L, 7200L), blocks.map { it.startUnix })
    }

    @Test fun singleShowSwitchDoesNotForceAnHourlyStart() {
        assertEquals(1800L, StationTiming.nextStart(1320, 0, nextType = MediaType.EPISODE))
        assertEquals(3600L, StationTiming.nextStart(1320, 0, nextType = MediaType.EPISODE,
            startsRepeatedShowBlock = true))
    }

    @Test fun moviesUseTheNextQuarterHourIncludingMinimumIdent() {
        assertEquals(2700L, StationTiming.nextStart(44 * 60, 0))
        assertEquals(4500L, StationTiming.nextStart(3600, 0))
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
        assertEquals(3600L, blocks[4].startUnix)
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
}
