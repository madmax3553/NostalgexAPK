package io.github.madmax3553.staticgroot.schedule

/** Schedule estimates may trigger a planned intermission, never truncate a program ending. */
object ProgramBoundary {
    fun shouldInterrupt(current: Block, next: Block?, mediaPositionSeconds: Long): Boolean =
        !current.isBumper && next?.isMidBreak == true && next.item.id == current.item.id &&
            mediaPositionSeconds >= current.mediaOffsetSeconds + current.durationSeconds

    /** Late program completion consumes filler, preserving the next advertised start. */
    fun elapsedInBreak(next: Block, nowUnix: Long): Long =
        if (next.isBumper && !next.isMidBreak) (nowUnix - next.startUnix).coerceAtLeast(0) else 0
}
