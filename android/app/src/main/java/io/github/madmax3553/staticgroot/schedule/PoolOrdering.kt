package io.github.madmax3553.staticgroot.schedule

import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType

/*
 * Port of tvOS SchedulePoolOrdering.swift. Deterministic: the same pool, channel and day
 * always give the same order. See SwiftRandom for the verification status.
 */
object PoolOrdering {

    private const val MAX_CONSECUTIVE = 2

    /** Interleave items so the same show does not run more than two episodes in a row. */
    fun interleaveByShow(items: List<MediaItem>, seed: Int, dayNumber: Long): List<MediaItem> {
        if (items.size <= 1) return items

        // Swift: SeededRNG(seed: UInt64(abs(seed) + 1) &+ UInt64(dayNumber))
        val rng = SeededRng(Math.abs(seed.toLong()) + 1 + dayNumber)

        // Group by show title for episodes, by id for movies, keeping first-seen order.
        val groups = ArrayList<MutableList<MediaItem>>()
        val indexByKey = HashMap<String, Int>()
        for (item in items) {
            val key = if (item.type == MediaType.EPISODE) item.title else "movie_${item.id}"
            val idx = indexByKey[key]
            if (idx != null) {
                groups[idx].add(item)
            } else {
                indexByKey[key] = groups.size
                groups.add(mutableListOf(item))
            }
        }

        for (g in groups) g.swiftShuffle(rng)
        groups.swiftShuffle(rng)

        val result = ArrayList<MediaItem>(items.size)
        val positions = IntArray(groups.size)
        var groupIndex = 0
        var exhausted = 0

        while (exhausted < groups.size) {
            var attempts = 0
            while (positions[groupIndex] >= groups[groupIndex].size) {
                groupIndex = (groupIndex + 1) % groups.size
                attempts += 1
                if (attempts > groups.size) break
            }
            if (attempts > groups.size) break

            val group = groups[groupIndex]
            val pos = positions[groupIndex]
            val take = minOf(MAX_CONSECUTIVE, group.size - pos)
            for (i in 0 until take) result.add(group[pos + i])
            positions[groupIndex] = pos + take

            if (positions[groupIndex] >= group.size) exhausted += 1
            groupIndex = (groupIndex + 1) % groups.size
        }
        return result
    }

    // MARK: - Premieres

    const val PREMIERE_WINDOW_SECONDS = 7L * 86400
    const val PREMIERE_ANCHOR_SECONDS = 19L * 3600
    const val PREMIERE_SPREAD_SECONDS = 5L * 3600
    const val PREMIERE_SLOT_SECONDS = 15L * 60

    /** Target airtime for one premiere, as seconds from midnight. Same hash as the Swift code. */
    fun premiereOffset(channelId: Int, ratingKey: String): Long {
        val slots = maxOf(1L, PREMIERE_SPREAD_SECONDS / PREMIERE_SLOT_SECONDS)
        var x = channelId.toLong() + 0x9E37_79B9_7F4A_7C15uL.toLong()
        for (b in ratingKey.toByteArray(Charsets.UTF_8)) {
            x = (x xor (b.toLong() and 0xFF)) * 0x0000_0100_0000_01B3L
        }
        x = x xor (x ushr 33)
        x *= 0xFF51_AFD7_ED55_8CCDuL.toLong()
        x = x xor (x ushr 33)
        return PREMIERE_ANCHOR_SECONDS + java.lang.Long.remainderUnsigned(x, slots) * PREMIERE_SLOT_SECONDS
    }

    /** Slots items added inside the window into the ordering at their premiere airtime. */
    fun promoteRecentlyAdded(
        items: List<MediaItem>,
        dayStartUnix: Long,
        channelId: Int,
        windowSeconds: Long = PREMIERE_WINDOW_SECONDS,
    ): List<MediaItem> {
        if (items.size <= 1) return items
        val cutoff = dayStartUnix - windowSeconds
        fun isRecent(i: MediaItem) = i.addedAt > 0 && i.addedAt >= cutoff
        val recent = items.filter(::isRecent)
        if (recent.isEmpty() || recent.size >= items.size) return items
        val rest = items.filterNot(::isRecent)

        class Target(val item: MediaItem, val offset: Long)
        val targets = recent.map { Target(it, premiereOffset(channelId, it.id)) }
            .sortedWith(compareBy<Target>({ it.offset }, { it.item.id }))

        val out = ArrayList(rest)
        for (target in targets) {
            var insertAt = out.size
            var cumulative = 0L
            for ((index, existing) in out.withIndex()) {
                if (cumulative >= target.offset) {
                    insertAt = index
                    break
                }
                cumulative += existing.duration * 60L
            }
            out.add(insertAt, target.item)
        }
        return out
    }

    // MARK: - Sequel parts

    private val SEQUEL_MARKER = Regex(
        "^(.*?)[\\s:,-]+(?:part|pt\\.?)\\s*([0-9]{1,2})\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** Puts films that are parts of one story together, in part order, where the earliest sat. */
    fun groupSequelParts(items: List<MediaItem>): List<MediaItem> {
        if (items.size <= 1) return items

        class Marked(val index: Int, val base: String, val ordinal: Int)
        val marked = ArrayList<Marked>()
        for ((index, item) in items.withIndex()) {
            if (item.type != MediaType.MOVIE) continue
            val match = SEQUEL_MARKER.find(item.title) ?: continue
            val base = match.groupValues[1].lowercase().trim()
            val ordinal = match.groupValues[2].toIntOrNull() ?: continue
            if (base.isEmpty()) continue
            marked.add(Marked(index, base, ordinal))
        }

        val groups = marked.groupBy { it.base }
        // Only real multi-part stories move. A lone "Part 2" stays where it is.
        val families = groups.values.filter { it.size > 1 }
        if (families.isEmpty()) return items

        val pulled = HashSet<Int>()
        val insertions = HashMap<Int, List<MediaItem>>()
        for (family in families) {
            val anchor = family.minOf { it.index }
            insertions[anchor] = family.sortedBy { it.ordinal }.map { items[it.index] }
            for (m in family) pulled.add(m.index)
        }

        val result = ArrayList<MediaItem>(items.size)
        for ((index, item) in items.withIndex()) {
            val run = insertions[index]
            if (run != null) result.addAll(run)
            else if (index !in pulled) result.add(item)
        }
        return result
    }
}
