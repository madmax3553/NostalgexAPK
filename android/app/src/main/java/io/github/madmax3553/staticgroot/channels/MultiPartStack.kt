package io.github.madmax3553.staticgroot.channels

/** One file of a disc-stacked movie. [runtimeSeconds] is null when the server did not report one. */
data class MediaPart(val sourceId: String, val runtimeSeconds: Long?)

/**
 * Port of tvOS MultiPartStack. Servers group "Titanic part 1" and "Titanic part 2" rips into one
 * movie with several MediaSources, but several sources also mean alternate versions (a 1080p and
 * a 4K of one film). Playing versions as parts would show the same movie twice, so sources count
 * as a stack only when every one of them carries an explicit part marker (part 2, pt. 2, cd2,
 * disc 2) with distinct numbers. Anything else falls through to the best-version pick.
 */
object MultiPartStack {

    data class SourceInfo(val id: String?, val name: String?, val runTimeTicks: Long?)

    data class Stack(
        val parts: List<MediaPart>,
        /** Sum across parts. Null when any part has no runtime: a partial sum would schedule a slot shorter than the movie. */
        val totalRunTimeTicks: Long?,
    )

    // The word boundary keeps "Department 2" from matching "part". The two-digit cap keeps
    // years and resolutions ("2160") out.
    private val MARKER = Regex("""\b(?:part|pt\.?|cd|disc|disk)[\s._-]*([0-9]{1,2})\b""", RegexOption.IGNORE_CASE)

    private const val TICKS_PER_SECOND = 10_000_000L

    fun detect(sources: List<SourceInfo>): Stack? {
        if (sources.size < 2) return null
        val numbered = ArrayList<Triple<Int, String, Long?>>()
        for (s in sources) {
            val id = s.id ?: return null
            val name = s.name ?: return null
            // The LAST marker decides: "Part II - The Return part 2.mkv" is disc 2.
            val ordinal = MARKER.findAll(name).lastOrNull()?.groupValues?.get(1)?.toInt() ?: return null
            numbered.add(Triple(ordinal, id, s.runTimeTicks))
        }
        // Two files claiming one disc are more likely versions of a part than a stack.
        if (numbered.map { it.first }.toSet().size != numbered.size) return null

        val ordered = numbered.sortedBy { it.first }
        val ticks = ordered.map { it.third }
        val total = if (ticks.all { it != null }) ticks.sumOf { it!! } else null
        return Stack(ordered.map { MediaPart(it.second, it.third?.let { t -> t / TICKS_PER_SECOND }) }, total)
    }

    /** Where a whole-movie offset lands: which part to open and how far into that part to seek. */
    data class Position(val partIndex: Int, val seekSeconds: Long, val partStartSeconds: Long)

    /**
     * Maps [offsetSeconds] into the whole stacked movie onto a part. Needs every part's runtime;
     * without them it keeps the offset in part one, as tvOS does. An offset past the end lands at
     * the start of the last part's tail, so the player ends that part and the schedule moves on.
     */
    fun locate(parts: List<MediaPart>, offsetSeconds: Long): Position {
        if (parts.size < 2 || parts.any { it.runtimeSeconds == null }) return Position(0, offsetSeconds, 0)
        var start = 0L
        for ((i, p) in parts.withIndex()) {
            val len = p.runtimeSeconds!!
            if (offsetSeconds < start + len || i == parts.lastIndex) {
                return Position(i, (offsetSeconds - start).coerceAtLeast(0), start)
            }
            start += len
        }
        return Position(0, offsetSeconds, 0)
    }

    /** Seconds before part [index] begins in the whole movie. 0 when any runtime is unknown. */
    fun startOf(parts: List<MediaPart>, index: Int): Long {
        if (parts.any { it.runtimeSeconds == null }) return 0
        return parts.take(index).sumOf { it.runtimeSeconds!! }
    }
}
