package io.github.madmax3553.staticgroot.schedule

import io.github.madmax3553.staticgroot.channels.MediaType

/**
 * Movies use quarter-hours; TV uses half-hours, with repeated-show blocks opening hourly. A break
 * never runs past [MAX_BREAK_SECONDS], though: when the next grid line is further away than that
 * (a 46 minute episode, a 60 minute episode against a half-hour grid), the next programme opens on
 * the next 5 minute mark instead. Measured on published days before this rule, 213 of 5,282 breaks
 * ran 20 to 60 minutes, all of them TV episodes pushed to the next :00 or :30. Programmes that end
 * soon enough before a grid line still land on it, so the schedule re-syncs.
 */
object StationTiming {
    const val MAX_BREAK_SECONDS = 600L
    private const val FINE_GRID = 300L

    /**
     * Shifts a channel's whole grid by a multiple of 5 minutes, so that programmes on different
     * channels start (and their breaks end) at different times. Without it every channel snaps to
     * the same :00 and :30, and channels with the same runtimes all go to break together.
     */
    fun gridOffset(channelId: Int): Long = Math.floorMod(channelId, PHASES) * FINE_GRID

    private const val PHASES = 6

    fun nextStart(endUnix: Long, dayStart: Long, minimumBreak: Long = 30,
        nextType: MediaType = MediaType.MOVIE, startsRepeatedShowBlock: Boolean = false,
        maxBreak: Long = MAX_BREAK_SECONDS, gridOffset: Long = 0): Long {
        require(minimumBreak >= 0)
        require(gridOffset >= 0 && gridOffset % FINE_GRID == 0L)
        // An hour back from the shifted origin keeps elapsed positive and every grid (15, 30, 60
        // minutes) aligned to it; starts land on origin + k * grid, which is offset past the grid line.
        val origin = dayStart + gridOffset - 3600
        val elapsed = endUnix - origin + minimumBreak
        val base = if (nextType == MediaType.MOVIE) 900L else 1800L
        val preferred = if (nextType != MediaType.MOVIE && startsRepeatedShowBlock) 3600L else base
        // Step down until the wait is short enough: the preferred grid (hourly for a show block),
        // then the base grid (half hour for TV, quarter hour for movies), then 5 minutes.
        for (grid in listOf(preferred, base)) {
            val start = origin + ceil(elapsed, grid)
            if (start - endUnix <= maxBreak) return start
        }
        return origin + ceil(elapsed, FINE_GRID)
    }

    private fun ceil(value: Long, grid: Long) = ((value + grid - 1) / grid) * grid
}
