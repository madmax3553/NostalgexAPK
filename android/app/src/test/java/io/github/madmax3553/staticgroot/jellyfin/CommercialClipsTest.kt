package io.github.madmax3553.staticgroot.jellyfin

import org.junit.Assert.*
import org.junit.Test

class CommercialClipsTest {
    @Test fun commercialsAreRecognizedByLibraryName() {
        assertTrue(CommercialClips.isLibrary(" Commercials "))
        assertTrue(CommercialClips.isLibrary("COMMERCIALS"))
        assertFalse(CommercialClips.isLibrary("Movies"))
    }
    @Test fun thirtySecondAdsAreNotLostToMinuteRounding() {
        val clip = CommercialClips.fromItem(JellyfinItem(Id = "ad", RunTimeTicks = 300_000_000))!!
        assertEquals(30L, clip.durationSeconds)
    }
    @Test fun breakFitsWholeClipsAndReservesTheStationIdent() {
        val clips = listOf(CommercialClip("a", "A", 30), CommercialClip("b", "B", 15), CommercialClip("c", "C", 30))
        assertEquals(listOf("a", "b"), CommercialClips.fit(clips, 60).map { it.id })
        assertTrue(CommercialClips.fit(clips, 10).isEmpty())
    }

    @Test fun aLongBreakRepeatsTheLibraryWithoutBackToBackRepeatsAndFillsTheTime() {
        val clips = listOf(CommercialClip("a", "A", 30), CommercialClip("b", "B", 30), CommercialClip("c", "C", 15))
        val fit = CommercialClips.fit(clips, 3600, java.util.Random(7))
        assertTrue("only ${fit.size}", fit.size > 3 * 10)
        assertEquals(clips.map { it.id }, fit.take(3).map { it.id })
        fit.zipWithNext().forEach { (x, y) -> assertNotEquals(x.id, y.id) }
        val total = fit.sumOf { it.durationSeconds }
        assertTrue(total <= 3590)
        assertTrue("left ${3590 - total}s unfilled", 3590 - total < 30)
    }

    @Test fun aSingleClipLibraryPlaysOnceBecauseItCannotAvoidARepeat() {
        assertEquals(1, CommercialClips.fit(listOf(CommercialClip("a", "A", 30)), 3600).size)
        assertTrue(CommercialClips.fit(emptyList(), 3600).isEmpty())
    }
}
