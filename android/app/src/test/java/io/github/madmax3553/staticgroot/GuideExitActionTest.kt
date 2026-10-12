package io.github.madmax3553.staticgroot

import org.junit.Assert.assertEquals
import org.junit.Test

/* The cases of tvOS GuideExitActionTests: decide() must never swallow Back when there is nothing to jump to. */
class GuideExitActionTest {
    @Test
    fun focusAwayFromTheLiveChannelJumpsToIt() {
        assertEquals(GuideExitAction.JumpToLiveChannel(7), GuideExitAction.decide(liveChannelId = 7, focusedChannelId = 3))
        assertEquals(GuideExitAction.JumpToLiveChannel(7), GuideExitAction.decide(liveChannelId = 7, focusedChannelId = null))
    }

    @Test
    fun focusAlreadyOnTheLiveChannelLetsTheSystemHandleIt() {
        assertEquals(GuideExitAction.LetSystemHandle, GuideExitAction.decide(liveChannelId = 7, focusedChannelId = 7))
    }

    @Test
    fun noLiveChannelAlwaysLetsTheSystemHandleIt() {
        assertEquals(GuideExitAction.LetSystemHandle, GuideExitAction.decide(liveChannelId = null, focusedChannelId = 3))
        assertEquals(GuideExitAction.LetSystemHandle, GuideExitAction.decide(liveChannelId = null, focusedChannelId = null))
    }
}
