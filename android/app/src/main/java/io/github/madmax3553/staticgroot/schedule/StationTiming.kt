package io.github.madmax3553.staticgroot.schedule

import io.github.madmax3553.staticgroot.channels.MediaType

/** Movies use quarter-hours; TV uses half-hours, with repeated-show blocks opening hourly. */
object StationTiming {
    fun nextStart(endUnix: Long, dayStart: Long, minimumBreak: Long = 30,
        nextType: MediaType = MediaType.MOVIE, startsRepeatedShowBlock: Boolean = false): Long {
        require(minimumBreak >= 0)
        val elapsed = endUnix - dayStart + minimumBreak
        val slot = when {
            nextType == MediaType.MOVIE -> 900L
            startsRepeatedShowBlock -> 3600L
            else -> 1800L
        }
        return dayStart + ((elapsed + slot - 1) / slot) * slot
    }
}
