package io.github.madmax3553.staticgroot.schedule

import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import org.junit.Assert.*
import org.junit.Test

class GenerationReshufflerTest {
    private fun run(show: String, start: Long): List<Block> {
        val a = MediaItem("$show-1", show, MediaType.EPISODE, duration = 50, seriesId = show)
        val b = a.copy(id = "$show-2")
        return listOf(Block(a, start, start + 3000), Block(b, start + 3000, start + 3600, true),
            Block(b, start + 3600, start + 6600), Block(a, start + 6600, start + 7200, true))
    }
    @Test fun overlappingPairsMoveTogetherWhilePublishedReferenceRemainsFixed() {
        val original = run("A", 0) + run("B", 7200)
        val result = GenerationReshuffler.optimize(mapOf(1 to original, 2 to original), 0, setOf(1))
        assertEquals(original, result.days[1])
        assertEquals(1, result.swaps)
        val moved = result.days.getValue(2)
        assertEquals(listOf("B-1", "B-2", "A-1", "A-2"), moved.filterNot { it.isBumper }.map { it.item.id })
        moved.zipWithNext().forEach { (a, b) -> assertEquals(a.endUnix, b.startUnix) }
        assertEquals(0L, moved.first().startUnix)
        assertEquals(14400L, moved.last().endUnix)
    }
    @Test fun immutableChannelsAndNonMatchingDurationsAreNotChanged() {
        val day = run("A", 0)
        val result = GenerationReshuffler.optimize(mapOf(1 to day, 2 to day), 0, setOf(1, 2))
        assertEquals(0, result.swaps)
        assertEquals(day, result.days[2])
    }
}
