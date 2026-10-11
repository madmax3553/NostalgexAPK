package io.github.madmax3553.staticgroot.schedule

import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import org.junit.Assert.*
import org.junit.Test

class ProgrammeBoundaryTest {
    private val item = MediaItem("episode", "Synthetic Show", MediaType.EPISODE, duration = 41)

    @Test fun finalSegmentMustNotCutOffThreeRemainingMinutes() {
        val final = Block(item, 1290, 2520, mediaOffsetSeconds = 1230)
        val filler = Block(item.copy(id = "next"), 2520, 3600, isBumper = true)
        // Player has reached the 41-minute metadata estimate, but actual file ends at 44.
        assertFalse(ProgrammeBoundary.shouldInterrupt(final, filler, 2460))
        assertFalse(ProgrammeBoundary.shouldInterrupt(final, filler, 2639))
    }

    @Test fun intentionalMidBreakStillPausesAtThePlannedContentOffset() {
        val first = Block(item, 0, 1230)
        val mid = Block(item, 1230, 1290, isBumper = true, isMidBreak = true)
        assertFalse(ProgrammeBoundary.shouldInterrupt(first, mid, 1229))
        assertTrue(ProgrammeBoundary.shouldInterrupt(first, mid, 1230))
    }

    @Test fun overrunConsumesStationFillerButNotTheMidpointIdent() {
        val filler = Block(item, 2520, 3600, isBumper = true)
        assertEquals(180L, ProgrammeBoundary.elapsedInBreak(filler, 2700))
        assertEquals(0L, ProgrammeBoundary.elapsedInBreak(filler.copy(isMidBreak = true), 2700))
    }
}
