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

    fun nextStart(endUnix: Long, dayStart: Long, minimumBreak: Long = 30,
        nextType: MediaType = MediaType.MOVIE, startsRepeatedShowBlock: Boolean = false,
        maxBreak: Long = MAX_BREAK_SECONDS): Long {
        require(minimumBreak >= 0)
        val elapsed = endUnix - dayStart + minimumBreak
        val base = if (nextType == MediaType.MOVIE) 900L else 1800L
        val preferred = if (nextType != MediaType.MOVIE && startsRepeatedShowBlock) 3600L else base
        // Step down until the wait is short enough: the preferred grid (hourly for a show block),
        // then the base grid (half hour for TV, quarter hour for movies), then 5 minutes.
        for (grid in listOf(preferred, base)) {
            val start = dayStart + ceil(elapsed, grid)
            if (start - endUnix <= maxBreak) return start
        }
        return dayStart + ceil(elapsed, FINE_GRID)
    }

    private fun ceil(value: Long, grid: Long) = ((value + grid - 1) / grid) * grid
}
