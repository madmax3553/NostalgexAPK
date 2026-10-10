package io.github.madmax3553.staticgroot.schedule

import io.github.madmax3553.staticgroot.channels.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Port of tvOS DailyManifestScheduler.packDay, blocksInRange and ChannelScheduleBuilder.
 *
 * Times are Unix seconds, as in the Swift code. Each day is packed from local midnight.
 * Yesterday's airings come in as [AiredHistory], so the cross-day seam rules match tvOS
 * when that history exists. Persistence of past days is not ported yet, so on a fresh
 * install the seam rules see no history.
 */

/** One programme block on a channel's day. */
data class Block(val item: MediaItem, val startUnix: Long, val endUnix: Long) {
    val durationSeconds: Long get() = endUnix - startUnix
}

/** What a channel aired on a past day, and the day's packed order. Empty when unknown. */
interface AiredHistory {
    fun airedKeys(channelId: Int, dayKey: String): Set<String>
    fun lastAired(channelId: Int, dayKey: String): String?
    /** Saves one packed day, in airing order, so the next day can read it. */
    fun record(channelId: Int, dayKey: String, orderedItemIds: List<String>)
}

object NoHistory : AiredHistory {
    override fun airedKeys(channelId: Int, dayKey: String): Set<String> = emptySet()
    override fun lastAired(channelId: Int, dayKey: String): String? = null
    override fun record(channelId: Int, dayKey: String, orderedItemIds: List<String>) = Unit
}

/** Local-day helpers. Matches DailyManifestStore in Swift, for the device's time zone. */
object LocalDays {
    fun startOfDayUnix(unix: Long, zone: ZoneId): Long {
        val date = ZonedDateTime.ofInstant(Instant.ofEpochSecond(unix), zone).toLocalDate()
        return date.atStartOfDay(zone).toEpochSecond()
    }

    /** Swift: Int(startOfLocalDay.timeIntervalSince1970 / 86400). Truncates like Swift does. */
    fun dayNumber(unix: Long, zone: ZoneId): Long = startOfDayUnix(unix, zone) / 86400

    fun dayKey(unix: Long, zone: ZoneId): String =
        ZonedDateTime.ofInstant(Instant.ofEpochSecond(unix), zone).toLocalDate().toString()

    fun dayKeyOf(date: LocalDate): String = date.toString()
}

object DailyPacker {

    /** Packs one local day for a channel's pool. Pure: the same inputs give the same blocks. */
    fun packDay(
        pool: List<MediaItem>,
        dayStartUnix: Long,
        dayNumber: Long,
        channelId: Int,
        isPremiereChannel: Boolean,
        yesterdayAiredKeys: Set<String>,
        yesterdayLastItemId: String?,
    ): List<Block> {
        val dayEnd = dayStartUnix + 86400
        val totalPoolSec = pool.sumOf { it.duration * 60L }
        if (totalPoolSec <= 0) return emptyList()

        val unplayed = pool.filter { it.id !in yesterdayAiredKeys }
        val interleaved = PoolOrdering.interleaveByShow(unplayed, channelId, dayNumber)
        val promoted = if (isPremiereChannel) {
            PoolOrdering.promoteRecentlyAdded(interleaved, dayStartUnix, channelId)
        } else {
            interleaved
        }
        val unplayedOrdered = PoolOrdering.groupSequelParts(promoted).toMutableList()

        // Cross-day seam: do not open the day with the title that closed yesterday.
        if (yesterdayLastItemId != null && unplayedOrdered.size > 1 &&
            unplayedOrdered.first().id == yesterdayLastItemId
        ) {
            unplayedOrdered.add(unplayedOrdered.removeAt(0))
        }

        val blocks = ArrayList<Block>()
        var t = dayStartUnix
        for (item in unplayedOrdered) {
            if (t >= dayEnd) break
            val dur = item.duration * 60L
            if (dur <= 0) continue
            val end = t + dur
            blocks.add(Block(item, t, end))
            t = end
        }

        if (t < dayEnd) {
            // Wraparound: fill the rest of the day from the full pool, starting at a
            // golden-ratio offset into the loop.
            val orderedFull = PoolOrdering.groupSequelParts(
                PoolOrdering.interleaveByShow(pool, channelId, dayNumber + 1),
            )
            val goldenShift = (totalPoolSec.toDouble() * 0.6180339887).toLong()
            val offsetSec = (dayNumber * goldenShift) % totalPoolSec

            var posInLoop = offsetSec
            var cycleIndex = 0
            var elapsed = 0L
            for (i in orderedFull.indices) {
                val d = orderedFull[i].duration * 60L
                if (elapsed + d > offsetSec) {
                    cycleIndex = i
                    posInLoop = offsetSec - elapsed
                    break
                }
                elapsed += d
            }

            // Do not start the wraparound on the item that just played.
            val lastPlayed = blocks.lastOrNull()?.item?.id ?: yesterdayLastItemId
            if (orderedFull.size > 1 && lastPlayed != null && orderedFull[cycleIndex].id == lastPlayed) {
                cycleIndex = (cycleIndex + 1) % orderedFull.size
                posInLoop = 0
            }

            var idx = cycleIndex
            var offsetInItem = posInLoop
            while (t < dayEnd) {
                val item = orderedFull[idx % orderedFull.size]
                val fullDur = item.duration * 60L
                if (fullDur <= 0) {
                    idx += 1
                    offsetInItem = 0
                    continue
                }
                val remainingInItem = fullDur - offsetInItem
                val remainingDay = dayEnd - t
                val playSec = minOf(remainingInItem, remainingDay)
                if (playSec <= 0) {
                    idx += 1
                    offsetInItem = 0
                    continue
                }
                val end = t + playSec
                blocks.add(Block(item, t, end))
                t = end
                if (offsetInItem + playSec >= fullDur) {
                    idx += 1
                    offsetInItem = 0
                } else {
                    offsetInItem += playSec
                }
            }
        }
        return blocks
    }
}

/** One entry in a channel's guide. */
data class ScheduleEntry(
    val block: Block,
    val isNowPlaying: Boolean,
) {
    val item: MediaItem get() = block.item
    val startUnix: Long get() = block.startUnix
    val endUnix: Long get() = block.endUnix
}

/** What a channel is showing now, as the guide needs it. Port of ChannelSchedule. */
data class ChannelSchedule(
    val entries: List<ScheduleEntry>,
    val nowPlaying: ScheduleEntry?,
    val upNext: ScheduleEntry?,
    /** Seconds into the current programme. */
    val elapsedSeconds: Long,
) {
    /** True while [nowUnix] is inside the current programme. A stale schedule fails this. */
    fun isCurrent(nowUnix: Long): Boolean {
        val np = nowPlaying ?: return false
        return np.startUnix <= nowUnix && nowUnix < np.endUnix
    }
}

/**
 * Builds a channel's schedule from its packed days. Caches each day per channel, since the
 * guide asks for the same days many times. Invalidate with [clear] after the pool changes.
 */
class ChannelScheduler(
    private val zone: ZoneId,
    private val history: AiredHistory = NoHistory,
) {
    private val dayCache = HashMap<String, List<Block>>()

    fun clear() = dayCache.clear()

    /** The blocks of the local day containing [unix], for one channel's pool. */
    fun dayBlocks(channelId: Int, pool: List<MediaItem>, isPremiere: Boolean, unix: Long): List<Block> {
        val dayStart = LocalDays.startOfDayUnix(unix, zone)
        val key = "$channelId|$dayStart|${pool.size}|${pool.firstOrNull()?.id}|${pool.lastOrNull()?.id}"
        return dayCache.getOrPut(key) {
            val yesterdayStart = dayStart - 86400
            val yKey = LocalDays.dayKey(yesterdayStart, zone)
            val packed = DailyPacker.packDay(
                pool = pool.filter { it.duration > 0 },
                dayStartUnix = dayStart,
                dayNumber = LocalDays.dayNumber(unix, zone),
                channelId = channelId,
                isPremiereChannel = isPremiere,
                yesterdayAiredKeys = history.airedKeys(channelId, yKey),
                yesterdayLastItemId = history.lastAired(channelId, yKey),
            )
            history.record(channelId, LocalDays.dayKey(unix, zone), packed.map { it.item.id })
            packed
        }
    }

    /** Blocks that overlap [fromUnix, toUnix), across every local day in the range. */
    fun blocksInRange(channelId: Int, pool: List<MediaItem>, isPremiere: Boolean, fromUnix: Long, toUnix: Long): List<Block> {
        val out = ArrayList<Block>()
        var cursor = LocalDays.startOfDayUnix(fromUnix, zone)
        val endDay = LocalDays.startOfDayUnix(toUnix, zone)
        while (cursor <= endDay) {
            // Swift asks for the day at cursor + 1 hour, which lands inside that local day.
            for (b in dayBlocks(channelId, pool, isPremiere, cursor + 3600)) {
                if (b.endUnix > fromUnix && b.startUnix < toUnix) out.add(b)
            }
            cursor = nextDayStart(cursor)
        }
        return out.sortedBy { it.startUnix }
    }

    /**
     * The guide window the Swift app builds: a 24-hour horizon from the current half-hour.
     */
    fun schedule(channelId: Int, pool: List<MediaItem>, isPremiere: Boolean, nowUnix: Long): ChannelSchedule? {
        val windowStart = (nowUnix / 1800) * 1800
        val windowEnd = windowStart + 86400
        val blocks = blocksInRange(channelId, pool, isPremiere, windowStart, windowEnd)
        if (blocks.isEmpty()) return null

        val entries = blocks
            .filter { it.endUnix > windowStart && it.startUnix < windowEnd }
            .map { ScheduleEntry(it, isNowPlaying = it.startUnix <= nowUnix && nowUnix < it.endUnix) }
        val nowIdx = entries.indexOfFirst { it.isNowPlaying }
        val nowEntry = entries.getOrNull(nowIdx)
        val upNext = if (nowIdx >= 0) entries.getOrNull(nowIdx + 1) else null
        val elapsed = if (nowEntry != null) maxOf(0L, nowUnix - nowEntry.startUnix) else 0L
        return ChannelSchedule(entries, nowEntry, upNext, elapsed)
    }

    private fun nextDayStart(dayStart: Long): Long {
        val date = ZonedDateTime.ofInstant(Instant.ofEpochSecond(dayStart), zone).toLocalDate().plusDays(1)
        return date.atStartOfDay(zone).toEpochSecond()
    }
}
