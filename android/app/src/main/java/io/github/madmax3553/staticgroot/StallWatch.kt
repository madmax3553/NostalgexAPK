package io.github.madmax3553.staticgroot

/**
 * When buffering counts as a failed stream. Before the first frame the limit runs from the start
 * of the tune, which includes the server's work. Once frames have played it runs from the start of
 * this buffering stretch: measuring from the tune made a stream that started at 28 seconds and then
 * rebuffered for a moment look like a 30 second failure (seen on a 4K remux with a transcoded
 * audio track), and the watchdog stopped working playback.
 */
object StallWatch {
    const val LIMIT_MS = 30_000L

    fun stalled(firstFrameShown: Boolean, sinceTuneMs: Long, bufferingForMs: Long): Boolean =
        if (firstFrameShown) bufferingForMs > LIMIT_MS else sinceTuneMs > LIMIT_MS
}
