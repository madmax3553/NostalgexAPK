package io.github.madmax3553.staticgroot

/**
 * What Back does from the guide. Port of tvOS GuideExitAction.
 *
 * When focus is somewhere other than the channel that is playing, Back brings it home. When it is
 * already there, Back must not be consumed, so the viewer can leave the app. Consuming it
 * unconditionally traps them: that was the tvOS bug this type exists to prevent, and on Android
 * the guide used to open Settings on every Back press with no way out but Home.
 */
sealed interface GuideExitAction {
    data class JumpToLiveChannel(val channelId: Int) : GuideExitAction
    data object LetSystemHandle : GuideExitAction

    companion object {
        fun decide(liveChannelId: Int?, focusedChannelId: Int?): GuideExitAction {
            val live = liveChannelId ?: return LetSystemHandle
            return if (focusedChannelId == live) LetSystemHandle else JumpToLiveChannel(live)
        }
    }
}
