package io.github.madmax3553.staticgroot

import kotlin.math.roundToInt

/** Nearest row centre to the middle of the five-row viewport, including fractional scroll. */
object CrawlSelection {
    fun centerIndex(scrollRows: Float, count: Int): Int {
        require(count > 0)
        return Math.floorMod((scrollRows + 2f).roundToInt(), count)
    }
}
