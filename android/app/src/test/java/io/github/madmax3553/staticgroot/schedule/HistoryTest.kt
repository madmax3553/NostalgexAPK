package io.github.madmax3553.staticgroot.schedule

import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneOffset.UTC
    private val pool = (1..8).map {
        MediaItem(id = "m$it", title = "Film $it", type = MediaType.MOVIE, duration = 90)
    }

    @Test
    fun recordedDayReadsBackInOrder() {
        val h = FileDayHistory(tmp.root)
        h.record(7, "2026-10-10", listOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), h.airedKeys(7, "2026-10-10"))
        assertEquals("c", h.lastAired(7, "2026-10-10"))
    }

    @Test
    fun unknownDayIsEmpty() {
        val h = FileDayHistory(tmp.root)
        assertTrue(h.airedKeys(7, "1999-01-01").isEmpty())
        assertEquals(null, h.lastAired(7, "1999-01-01"))
    }

    @Test
    fun aRecordedDayIsReplacedNotAppended() {
        val h = FileDayHistory(tmp.root)
        h.record(1, "2026-10-10", listOf("a", "b"))
        h.record(1, "2026-10-10", listOf("x"))
        assertEquals(listOf("x"), h.airedKeys(1, "2026-10-10").toList())
    }

    /**
     * The seam rule across days, through the real scheduler: the first item of today must not be
     * the last item of yesterday, when the pool has more than one item. Yesterday is packed and
     * recorded first, the way a device packs days as the guide reaches them.
     */
    @Test
    fun todayDoesNotOpenWithYesterdaysClosingTitle() {
        val sched = ChannelScheduler(zone, FileDayHistory(tmp.root))
        val yesterday = 1_791_590_400L - 86400 + 3600 // 2026-10-09, 01:00 UTC
        val today = yesterday + 86400
        val lastYesterday = sched.dayBlocks(3, pool, false, yesterday).last().item.id
        val firstToday = sched.dayBlocks(3, pool, false, today).first().item.id
        assertNotEquals(lastYesterday, firstToday)
    }

    @Test
    fun packedDaysAreWrittenToTheHistoryDirectory() {
        val sched = ChannelScheduler(zone, FileDayHistory(tmp.root))
        sched.dayBlocks(3, pool, false, 1_791_633_600L)
        assertTrue(tmp.root.listFiles()!!.any { it.name == "3_2026-10-10.txt" })
    }
}
