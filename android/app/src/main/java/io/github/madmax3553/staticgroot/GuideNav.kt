package io.github.madmax3553.staticgroot

/**
 * The guide's row arithmetic, kept out of the view so it can be tested. The list is circular:
 * Down from the last channel lands on the first, and the window scrolls through the seam, so
 * channel 1 appears under the last channel instead of the list jumping back to the top. Up from
 * the first channel is not a wrap: it leaves the grid for Settings, as on tvOS.
 */
object GuideNav {
    const val VISIBLE = 5

    enum class Reveal { DOWN, UP, JUMP }

    fun next(index: Int, size: Int): Int = (index + 1) % size

    /** The row above, or null from the first row, which is where Up goes to Settings. */
    fun previous(index: Int): Int? = if (index <= 0) null else index - 1

    /** Channel index shown on screen row [row], counting from the window's first row. */
    fun rowIndex(firstRow: Int, row: Int, size: Int): Int =
        if (size > VISIBLE) (firstRow + row) % size else firstRow + row

    private fun offset(index: Int, firstRow: Int, size: Int) = Math.floorMod(index - firstRow, size)

    /**
     * The window's first row after [index] became selected. Selection already inside the window
     * leaves it alone. Moving down puts the new row at the bottom, moving up at the top. A jump
     * (a programmatic select) puts it third from the top, clamped so the window never straddles
     * the seam for no reason.
     */
    fun firstRowFor(index: Int, firstRow: Int, size: Int, reveal: Reveal): Int {
        if (size <= VISIBLE) return 0
        if (offset(index, firstRow, size) < VISIBLE) return firstRow
        return when (reveal) {
            Reveal.DOWN -> Math.floorMod(index - VISIBLE + 1, size)
            Reveal.UP -> index
            Reveal.JUMP -> (index - 2).coerceIn(0, size - VISIBLE)
        }
    }
}
