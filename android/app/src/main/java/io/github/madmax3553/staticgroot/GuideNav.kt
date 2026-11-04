package io.github.madmax3553.staticgroot

/**
 * The guide's row arithmetic, kept out of the view so it can be tested. The list is circular in
 * both directions: Down from the last channel lands on the first, and Up from the first channel
 * lands on the last, scrolling smoothly across the seam so overshooting by one row in either
 * direction is undone by a single opposite press.
 */
object GuideNav {
    const val VISIBLE = 5

    enum class Reveal { DOWN, UP, JUMP }

    fun next(index: Int, size: Int): Int = Math.floorMod(index + 1, size)

    /** The row above, wrapping from the first channel to the last. */
    fun previous(index: Int, size: Int): Int = Math.floorMod(index - 1, size)

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
