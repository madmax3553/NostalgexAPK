package io.github.madmax3553.staticgroot.schedule

import io.github.madmax3553.staticgroot.channels.MediaType

/** Generation-only, conservative optimizer: swap equal-length series/programme blocks.
 * Equal lengths preserve clock alignment, day coverage and all internal break offsets.
 * Published channels passed as locked are scoring references, never modified.
 */
object GenerationReshuffler {
    data class Result(val days: Map<Int, List<Block>>, val swaps: Int)
    private data class Run(val blocks: List<Block>) {
        val start get() = blocks.first().startUnix
        val end get() = blocks.last().endUnix
        val duration get() = end - start
        val programmes get() = blocks.filterNot { it.isBumper }
    }

    private fun identity(block: Block): String = if (block.item.type == MediaType.EPISODE)
        "tv:${block.item.seriesId ?: block.item.title.lowercase()}" else
        "movie:${block.item.tmdbID ?: (block.item.title.lowercase() + ":" + block.item.year)}"

    private fun runs(blocks: List<Block>): List<Run> {
        val result = ArrayList<Run>()
        var current = ArrayList<Block>()
        var key: String? = null
        for (block in blocks) {
            if (!block.isBumper) {
                val next = identity(block)
                if (key != null && next != key) {
                    result.add(Run(current)); current = ArrayList()
                }
                key = next
            }
            current.add(block)
        }
        if (current.isNotEmpty()) result.add(Run(current))
        return result
    }

    private fun mix(blocks: List<Block>, start: Long): List<Map<String, Long>> {
        val windows = List(12) { HashMap<String, Long>() }
        for (block in blocks) {
            if (block.isBumper) continue
            val key = identity(block)
            for (i in windows.indices) {
                val from = start + i * 7200
                val seconds = (minOf(block.endUnix, from + 7200) - maxOf(block.startUnix, from)).coerceAtLeast(0)
                if (seconds > 0) windows[i][key] = (windows[i][key] ?: 0) + seconds
            }
        }
        return windows
    }

    /** Penalize shared airtime at/above 60% of each two-hour wall-clock window. */
    private fun score(mix: List<Map<String, Long>>, others: List<List<Map<String, Long>>>): Long =
        others.sumOf { other -> mix.indices.sumOf { i ->
            val shared = mix[i].entries.sumOf { (key, seconds) -> minOf(seconds, other[i][key] ?: 0) }
            if (shared >= 4320) shared - 4319 else 0L
        } }

    private fun move(run: Run, destination: Long): List<Block> = run.blocks.map {
        val shift = destination - run.start
        it.copy(startUnix = it.startUnix + shift, endUnix = it.endUnix + shift)
    }

    private fun repointBumpers(blocks: List<Block>): List<Block> = blocks.mapIndexed { index, block ->
        if (!block.isBumper || block.isMidBreak) block else
            blocks.drop(index + 1).firstOrNull { !it.isBumper }?.let { block.copy(item = it.item) } ?: block
    }

    fun optimize(days: Map<Int, List<Block>>, dayStart: Long, locked: Set<Int> = emptySet()): Result {
        val output = days.toSortedMap().toMutableMap()
        val mixes = output.mapValues { mix(it.value, dayStart) }.toMutableMap()
        var swaps = 0
        for ((channel, original) in output.toMap()) {
            if (channel in locked) continue
            val groups = runs(original).toMutableList()
            val others = mixes.filterKeys { it != channel }.values.toList()
            var currentScore = score(mixes.getValue(channel), others)
            if (currentScore == 0L) continue
            // Bounded work on TV hardware. This is a heuristic, not exhaustive optimisation.
            var attempts = 0
            for (a in groups.indices) for (b in a + 1 until groups.size) {
                if (++attempts > 80 || currentScore == 0L) break
                val x = groups[a]; val y = groups[b]
                if (x.duration != y.duration || x.programmes.isEmpty() || y.programmes.isEmpty()) continue
                // Paired TV blocks open hourly; never relocate them to a half-hour.
                fun aligned(run: Run, destination: Long): Boolean =
                    run.programmes.first().item.type != MediaType.EPISODE || (destination - dayStart) % 3600 == 0L
                if (!aligned(x, y.start) || !aligned(y, x.start)) continue
                val candidate = groups.flatMapIndexed { i, run -> when (i) {
                    a -> move(y, x.start); b -> move(x, y.start); else -> run.blocks
                } }
                val programmes = candidate.filter { !it.isBumper && it.mediaOffsetSeconds == 0L }
                if (programmes.zipWithNext().any { (p, q) -> p.item.type == MediaType.MOVIE &&
                    q.item.type == MediaType.MOVIE && identity(p) == identity(q) }) continue
                val candidateMix = mix(candidate, dayStart)
                val candidateScore = score(candidateMix, others)
                if (candidateScore < currentScore) {
                    groups[a] = Run(move(y, x.start)); groups[b] = Run(move(x, y.start))
                    output[channel] = repointBumpers(candidate)
                    mixes[channel] = candidateMix
                    currentScore = candidateScore
                    swaps++
                }
            }
        }
        return Result(output, swaps)
    }
}
