package io.github.madmax3553.staticgroot

import org.junit.Assert.assertEquals
import org.junit.Test

class CrawlSelectionTest {
    @Test fun fractionalScrollChoosesTheNearestCentralRow() {
        assertEquals(12, CrawlSelection.centerIndex(10.2f, 30))
        assertEquals(13, CrawlSelection.centerIndex(10.8f, 30))
    }
    @Test fun centralSelectionWrapsAtTheEndOfTheGuide() {
        assertEquals(1, CrawlSelection.centerIndex(29f, 30))
    }
}
