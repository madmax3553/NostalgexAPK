package io.github.madmax3553.staticgroot.schedule

import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Golden vectors copied from scripts/nostalgex-schedule-order-smoke.mjs. That file says they
 * were produced by running the real Swift algorithms (Apple Swift 6.2.1) on the same fixture,
 * so these are the tvOS outputs. The fixture below is the same pool, built the same way.
 *
 * This is the one parity check against tvOS that runs without a Mac.
 */
class SwiftGoldenTest {

    private val pool: List<MediaItem> = buildList {
        for ((si, show) in listOf("Seinfeld", "Friends", "The Office").withIndex()) {
            for (e in 1..5) {
                add(MediaItem(id = "e$si$e", title = show, type = MediaType.EPISODE, duration = 22, addedAt = 0))
            }
        }
        val titles = listOf(
            "Alien", "Mockingjay Part 2", "Heat", "Mockingjay Part 1", "Deathly Hallows: Part 2",
            "Jaws", "Deathly Hallows: Part 1", "Rocky", "Kill Bill Vol 1", "Dune",
        )
        for ((mi, title) in titles.withIndex()) {
            add(
                MediaItem(
                    id = "m$mi", title = title, type = MediaType.MOVIE,
                    duration = 95 + mi * 3,
                    addedAt = if (mi % 4 == 0) 1_700_000_000L else 0L,
                ),
            )
        }
    }

    private val dayStart = 1_700_100_000L

    private fun ids(items: List<MediaItem>) = items.joinToString(",") { it.id }

    private fun assertGolden(key: String, expected: String, actual: String) {
        assertEquals(key, expected, actual)
    }

    @Test
    fun interleaveMatchesSwift() {
        assertGolden(
            "INTER:1:20000",
            "m8,m3,m5,m4,m7,m2,m6,m9,m0,e22,e25,e03,e01,m1,e15,e13,e23,e21,e04,e05,e14,e12,e24,e02,e11",
            ids(PoolOrdering.interleaveByShow(pool, seed = 1, dayNumber = 20000)),
        )
        assertGolden(
            "INTER:49:20431",
            "m1,e23,e24,m7,m3,m2,m9,m0,m5,m4,e03,e05,e14,e12,m8,m6,e22,e21,e02,e04,e11,e15,e25,e01,e13",
            ids(PoolOrdering.interleaveByShow(pool, seed = 49, dayNumber = 20431)),
        )
        assertGolden(
            "INTER:141:20500",
            "m8,m9,m6,m2,m4,m3,m1,e21,e22,m5,e04,e05,m7,e12,e11,m0,e25,e24,e02,e01,e15,e13,e23,e03,e14",
            ids(PoolOrdering.interleaveByShow(pool, seed = 141, dayNumber = 20500)),
        )
    }

    @Test
    fun promoteMatchesSwift() {
        val cases = listOf(
            Triple(1, 20000L, "m3,m5,m7,m2,m6,m9,e22,e25,e03,e01,m1,e15,e13,e23,e21,e04,e05,e14,e12,e24,e02,e11,m0,m4,m8"),
            Triple(49, 20431L, "m1,e23,e24,m7,m3,m2,m9,m5,e03,e05,e14,e12,m6,e22,e21,e02,e04,e11,e15,e25,e01,e13,m0,m8,m4"),
            Triple(141, 20500L, "m9,m6,m2,m3,m1,e21,e22,m5,e04,e05,m7,e12,e11,e25,e24,e02,e01,e15,e13,e23,e03,e14,m4,m8,m0"),
        )
        for ((seed, day, expected) in cases) {
            val a = PoolOrdering.interleaveByShow(pool, seed, day)
            assertGolden("PROMO:$seed:$day", expected, ids(PoolOrdering.promoteRecentlyAdded(a, dayStart, seed)))
        }
    }

    @Test
    fun sequelGroupingMatchesSwift() {
        val cases = listOf(
            Triple(1, 20000L, "m3,m1,m5,m7,m2,m6,m4,m9,e22,e25,e03,e01,e15,e13,e23,e21,e04,e05,e14,e12,e24,e02,e11,m0,m8"),
            Triple(49, 20431L, "m3,m1,e23,e24,m7,m2,m9,m5,e03,e05,e14,e12,m6,m4,e22,e21,e02,e04,e11,e15,e25,e01,e13,m0,m8"),
            Triple(141, 20500L, "m9,m6,m4,m2,m3,m1,e21,e22,m5,e04,e05,m7,e12,e11,e25,e24,e02,e01,e15,e13,e23,e03,e14,m8,m0"),
        )
        for ((seed, day, expected) in cases) {
            val a = PoolOrdering.interleaveByShow(pool, seed, day)
            val promoted = PoolOrdering.promoteRecentlyAdded(a, dayStart, seed)
            assertGolden("SEQ:$seed:$day", expected, ids(PoolOrdering.groupSequelParts(promoted)))
        }
    }

    @Test
    fun premiereOffsetsMatchSwift() {
        val want = mapOf(
            Triple(1, "m0", 0) to 72900L, Triple(1, "m4", 0) to 79200L, Triple(1, "m8", 0) to 81000L,
            Triple(1, "e01", 0) to 84600L,
            Triple(49, "m0", 0) to 68400L, Triple(49, "m4", 0) to 85500L, Triple(49, "m8", 0) to 78300L,
            Triple(49, "e01", 0) to 76500L,
            Triple(223, "m0", 0) to 83700L, Triple(223, "m4", 0) to 73800L, Triple(223, "m8", 0) to 69300L,
            Triple(223, "e01", 0) to 72000L,
        )
        for ((key, offset) in want) {
            assertEquals("premiereOffset(${key.first}, ${key.second})", offset,
                PoolOrdering.premiereOffset(key.first, key.second))
        }
    }
}
