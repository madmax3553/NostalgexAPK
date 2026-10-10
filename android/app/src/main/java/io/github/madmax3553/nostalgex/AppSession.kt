package io.github.madmax3553.nostalgex

import io.github.madmax3553.nostalgex.channels.ChannelPool
import io.github.madmax3553.nostalgex.jellyfin.JellyfinClient

/**
 * In-memory session shared by the activities. Nothing is persisted yet, so the user signs
 * in again after the app is closed. Storing the token needs the Android Keystore first
 * (CLAUDE.md: secrets stay out of plain storage).
 */
object AppSession {
    @Volatile var client: JellyfinClient? = null
    @Volatile var pools: List<ChannelPool> = emptyList()
}
