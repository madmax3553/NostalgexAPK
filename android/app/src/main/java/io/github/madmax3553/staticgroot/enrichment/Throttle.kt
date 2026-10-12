package io.github.madmax3553.staticgroot.enrichment

/** Spaces calls at least [minIntervalMs] apart across threads. MusicBrainz allows one a second. */
class Throttle(
    private val minIntervalMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var last = Long.MIN_VALUE / 2

    @Synchronized
    fun await() {
        val wait = minIntervalMs - (nowMs() - last)
        if (wait > 0) sleepMs(wait)
        last = nowMs()
    }
}
