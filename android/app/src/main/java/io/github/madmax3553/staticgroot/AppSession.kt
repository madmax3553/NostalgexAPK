package io.github.madmax3553.staticgroot

import io.github.madmax3553.staticgroot.channels.ChannelRuntime
import io.github.madmax3553.staticgroot.jellyfin.JellyfinClient
import io.github.madmax3553.staticgroot.schedule.ChannelSchedule
import io.github.madmax3553.staticgroot.schedule.ChannelScheduler
import io.github.madmax3553.staticgroot.schedule.FileDayHistory
import java.io.File
import java.time.ZoneId

/**
 * In-memory session shared by the activities. Nothing is persisted apart from the day history
 * the schedule needs. The token is not stored, so the user signs in again after the app closes.
 * Storing it needs the Android Keystore first (CLAUDE.md: secrets stay out of plain storage).
 */
object AppSession {
    @Volatile var client: JellyfinClient? = null
    @Volatile var channels: List<ChannelRuntime> = emptyList()
    @Volatile var scheduler: ChannelScheduler? = null

    fun init(historyDir: File) {
        scheduler = ChannelScheduler(ZoneId.systemDefault(), FileDayHistory(historyDir))
    }

    /** What channel [runtime] is showing at [nowUnix]. Null when its pool is empty. */
    fun scheduleFor(runtime: ChannelRuntime, nowUnix: Long): ChannelSchedule? {
        val sched = scheduler ?: return null
        return sched.schedule(runtime.id, runtime.pool, runtime.isPremiere, nowUnix)
    }
}
