package io.github.madmax3553.staticgroot

import io.github.madmax3553.staticgroot.GuideNav.Reveal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* Mirrors tvOS ChannelGuideWrapFocusTests: Down on the last row wraps to the first, Up on the first leaves for Settings. */
class GuideNavTest {

    /** Plays a key sequence like GuideGrid.onKeyDown does and returns (index, firstRow, settingsOpened). */
    private class Sim(val size: Int) {
        var index = 0; var firstRow = 0; var settings = 0
        fun down() { index = GuideNav.next(index, size); firstRow = GuideNav.firstRowFor(index, firstRow, size, Reveal.DOWN) }
        fun up() {
            val p = GuideNav.previous(index)
            if (p == null) { settings++; return }
            index = p; firstRow = GuideNav.firstRowFor(index, firstRow, size, Reveal.UP)
        }
        fun visible() = (0 until GuideNav.VISIBLE).map { GuideNav.rowIndex(firstRow, it, size) }
    }

    @Test
    fun downOnTheLastChannelWrapsToTheFirstAfterAFullPass() {
        for (size in listOf(6, 20, 131)) {
            val s = Sim(size)
            repeat(size - 1) { s.down() }
            assertEquals("arriving at the bottom lands on the last row, no wrap yet", size - 1, s.index)
            s.down()
            assertEquals("the next press wraps", 0, s.index)
            assertTrue("selection stays on screen after the wrap, size $size", 0 in s.visible())
        }
    }

    @Test
    fun theWindowScrollsThroughTheSeamSoChannelOneFollowsTheLast() {
        val s = Sim(20)
        repeat(19) { s.down() }
        assertEquals(listOf(15, 16, 17, 18, 19), s.visible())
        s.down()
        assertEquals("channel 1 appears under the last one", listOf(16, 17, 18, 19, 0), s.visible())
        s.down()
        assertEquals(listOf(17, 18, 19, 0, 1), s.visible())
    }

    @Test
    fun upOnTheFirstChannelGoesToSettingsAndDoesNotWrap() {
        val s = Sim(20)
        s.up()
        assertEquals(1, s.settings)
        assertEquals(0, s.index)
        assertNull(GuideNav.previous(0))
    }

    @Test
    fun upFromJustAfterTheSeamWalksBackThroughIt() {
        val s = Sim(20)
        repeat(20) { s.down() }       // wrapped to channel 1, window 16..19,0
        repeat(2) { s.down() }        // channel 3
        repeat(2) { s.up() }          // back to channel 1
        assertEquals(0, s.index)
        assertTrue(0 in s.visible())
        s.up()
        assertEquals("Up from channel 1 is Settings, never the bottom", 1, s.settings)
    }

    @Test
    fun aFullLoopVisitsEveryChannelAndSelectionIsAlwaysVisible() {
        val s = Sim(13)
        val seen = HashSet<Int>()
        repeat(40) { s.down(); seen.add(s.index); assertTrue(s.index in s.visible()) }
        assertEquals(13, seen.size)
    }

    @Test
    fun aShortListNeverScrollsOrWrapsTheWindow() {
        val s = Sim(3)
        repeat(7) { s.down(); assertEquals(0, s.firstRow) }
        assertEquals(listOf(0, 1, 2, 3, 4), s.visible())
    }

    @Test
    fun aJumpPutsTheChannelThirdFromTheTopWithoutStraddlingTheSeam() {
        assertEquals(8, GuideNav.firstRowFor(10, 0, 131, Reveal.JUMP))
        assertEquals(0, GuideNav.firstRowFor(1, 60, 131, Reveal.JUMP))
        assertEquals(126, GuideNav.firstRowFor(130, 0, 131, Reveal.JUMP))
        assertEquals("already visible stays put", 40, GuideNav.firstRowFor(42, 40, 131, Reveal.JUMP))
    }
}
