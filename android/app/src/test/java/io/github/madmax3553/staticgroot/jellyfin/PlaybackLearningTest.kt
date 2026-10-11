package io.github.madmax3553.staticgroot.jellyfin

import org.junit.Assert.*
import org.junit.Test

class PlaybackLearningTest {
    @Test fun startupFrameAndShortSampleDoNotEstablishSuccess() {
        assertFalse(PlaybackLearning.sustained(1000, 1000))
        assertFalse(PlaybackLearning.sustained(29000, 30000))
    }
    @Test fun bufferingIsNotMistakenForEncodingSpeedOrSuccessfulPlayback() {
        assertFalse(PlaybackLearning.sustained(30000, 60000))
        assertTrue(PlaybackLearning.sustained(31000, 32000))
    }
}
