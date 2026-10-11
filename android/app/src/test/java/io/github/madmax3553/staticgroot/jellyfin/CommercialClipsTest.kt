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
}
