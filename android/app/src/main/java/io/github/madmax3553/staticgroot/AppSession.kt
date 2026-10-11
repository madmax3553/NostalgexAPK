package io.github.madmax3553.staticgroot

import io.github.madmax3553.staticgroot.channels.ChannelRuntime
import io.github.madmax3553.staticgroot.channels.filteredPool
import io.github.madmax3553.staticgroot.jellyfin.JellyfinClient
import io.github.madmax3553.staticgroot.schedule.ChannelSchedule
import io.github.madmax3553.staticgroot.schedule.ChannelScheduler
import io.github.madmax3553.staticgroot.schedule.FileDayHistory
import java.io.File
import java.time.ZoneId
import java.time.Instant

/**
 * In-memory session shared by the activities. Nothing is persisted apart from the day history
 * the schedule needs. The token is not stored, so the user signs in again after the app closes.
 * Storing it needs the Android Keystore first (CLAUDE.md: secrets stay out of plain storage).
 */
object AppSession {
    @Volatile var client: JellyfinClient? = null
    @Volatile var channels: List<ChannelRuntime> = emptyList()
    @Volatile var commercials: List<io.github.madmax3553.staticgroot.jellyfin.CommercialClip> = emptyList()
    @Volatile var scheduler: ChannelScheduler? = null
    @Volatile var sharedStation: io.github.madmax3553.staticgroot.schedule.SharedStation? = null
    @Volatile var lastChannelId: Int? = null
    var stationZone: ZoneId = ZoneId.systemDefault()

    fun init(historyDir: File) {
        scheduler = ChannelScheduler(stationZone, FileDayHistory(historyDir))
    }

    /** What channel [runtime] is showing at [nowUnix]. Null when its pool is empty. */
    fun scheduleFor(runtime: ChannelRuntime, nowUnix: Long): ChannelSchedule? {
        val sched = scheduler ?: return null
        val hour = Instant.ofEpochSecond(nowUnix).atZone(stationZone).hour
        val pool = filteredPool(runtime.unrestrictedPool, runtime.definition.timeRestrictions, hour)
        return sched.schedule(runtime.id, pool, runtime.isPremiere, nowUnix)
    }
}
