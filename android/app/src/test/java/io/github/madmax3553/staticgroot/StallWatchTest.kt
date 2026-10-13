package io.github.madmax3553.staticgroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallWatchTest {
    @Test fun beforeTheFirstFrameTheLimitRunsFromTheTune() {
        assertFalse(StallWatch.stalled(false, 29_000, 29_000))
        assertTrue(StallWatch.stalled(false, 31_000, 0))
    }

    @Test fun aShortRebufferAfterALateStartIsNotAStall() {
        // First frame at 28 s, a one second rebuffer at 32 s since the tune: this killed working playback.
        assertFalse(StallWatch.stalled(true, 32_000, 1_000))
    }

    @Test fun aLongRebufferAfterPlaybackStartedIsAStall() {
        assertTrue(StallWatch.stalled(true, 120_000, 31_000))
        assertFalse(StallWatch.stalled(true, 120_000, 29_000))
    }
}
