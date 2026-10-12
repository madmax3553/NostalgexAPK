package io.github.madmax3553.staticgroot.channels

import io.github.madmax3553.staticgroot.jellyfin.JellyfinHttp
import io.github.madmax3553.staticgroot.jellyfin.JellyfinItem
import io.github.madmax3553.staticgroot.jellyfin.JellyfinMapping
import io.github.madmax3553.staticgroot.jellyfin.MediaSource
import io.github.madmax3553.staticgroot.jellyfin.MediaStream
import io.github.madmax3553.staticgroot.jellyfin.PlaybackInfoResponse
import io.github.madmax3553.staticgroot.jellyfin.PlaybackResolver
import io.github.madmax3553.staticgroot.jellyfin.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * The detect cases are the tvOS MultiPartStackTests, one for one. The locate and mapping
 * tests cover what Android adds: placing a tune-in offset inside the right disc.
 */
class MultiPartStackTest {

    private val HOUR_TICKS = 36_000_000_000L // one hour, as the Swift fixtures use
    private fun src(id: String, name: String, ticks: Long? = HOUR_TICKS) = MultiPartStack.SourceInfo(id, name, ticks)

    @Test
    fun partOneAndTwoDetectedInOrder() {
        val s = MultiPartStack.detect(listOf(src("a", "Titanic (1997) part 1.mkv"), src("b", "Titanic (1997) part 2.mkv")))!!
        assertEquals(listOf("a", "b"), s.parts.map { it.sourceId })
        assertEquals(72_000_000_000L, s.totalRunTimeTicks)
    }

    @Test
    fun ptDotDiscAndCdVariantsDetected() {
        assertEquals(2, MultiPartStack.detect(listOf(src("a", "Apocalypse Now Redux pt. 1.mp4"), src("b", "Apocalypse Now Redux pt. 2.mp4")))?.parts?.size)
        assertNotNull(MultiPartStack.detect(listOf(src("a", "LOTR Extended disc 1.mkv"), src("b", "LOTR Extended disc 2.mkv"))))
        assertNotNull(MultiPartStack.detect(listOf(src("a", "Movie cd1.avi"), src("b", "Movie cd2.avi"))))
    }

    @Test
    fun outOfOrderSourcesAreSortedByOrdinal() {
        val s = MultiPartStack.detect(listOf(src("b", "Titanic part 2.mkv"), src("a", "Titanic part 1.mkv")))!!
        assertEquals("a", s.parts.first().sourceId)
    }

    @Test
    fun titleContainingPartWordUsesTheLastMarker() {
        val s = MultiPartStack.detect(listOf(
            src("a", "The Hunger Games Mockingjay Part 1 - part 1.mkv"),
            src("b", "The Hunger Games Mockingjay Part 1 - part 2.mkv"),
        ))!!
        assertEquals(listOf("a", "b"), s.parts.map { it.sourceId })
    }

    @Test
    fun alternateVersionsAreNotParts() {
        assertNull(MultiPartStack.detect(listOf(src("a", "Titanic (1997) 1080p.mkv"), src("b", "Titanic (1997) 2160p.mkv"))))
    }

    @Test
    fun oneMarkedOneNotDuplicateOrdinalsAndSingleSourceAreNotStacks() {
        assertNull(MultiPartStack.detect(listOf(src("a", "Titanic part 1.mkv"), src("b", "Titanic.mkv"))))
        assertNull(MultiPartStack.detect(listOf(src("a", "Titanic part 1.mkv"), src("b", "Titanic part 1 (director cut).mkv"))))
        assertNull(MultiPartStack.detect(listOf(src("a", "Titanic part 1.mkv"))))
    }

    @Test
    fun markerInsideAWordDoesNotMatch() {
        assertNull(MultiPartStack.detect(listOf(src("a", "Department 2.mkv"), src("b", "Department 2 sequel.mkv"))))
    }

    @Test
    fun missingRuntimeOnOnePartKeepsTheTotalNull() {
        val s = MultiPartStack.detect(listOf(src("a", "Titanic part 1.mkv"), src("b", "Titanic part 2.mkv", ticks = null)))!!
        assertNull(s.totalRunTimeTicks)
    }

    // -- Locating an offset --------------------------------------------------------------

    private val twoParts = listOf(MediaPart("a", 3600), MediaPart("b", 4800))

    @Test
    fun offsetInsidePartOneStaysThere() {
        assertEquals(MultiPartStack.Position(0, 1200, 0), MultiPartStack.locate(twoParts, 1200))
    }

    @Test
    fun offsetPastPartOneSeeksIntoPartTwo() {
        assertEquals(MultiPartStack.Position(1, 900, 3600), MultiPartStack.locate(twoParts, 4500))
        assertEquals(MultiPartStack.Position(1, 0, 3600), MultiPartStack.locate(twoParts, 3600))
    }

    @Test
    fun offsetPastTheEndLandsInTheLastPart() {
        val p = MultiPartStack.locate(twoParts, 99_999)
        assertEquals(1, p.partIndex)
    }

    @Test
    fun unknownRuntimeKeepsTheOffsetInPartOneLikeTvOS() {
        val parts = listOf(MediaPart("a", 3600), MediaPart("b", null))
        assertEquals(MultiPartStack.Position(0, 5000, 0), MultiPartStack.locate(parts, 5000))
    }

    @Test
    fun nonStackNeverMoves() {
        assertEquals(MultiPartStack.Position(0, 777, 0), MultiPartStack.locate(emptyList(), 777))
    }

    @Test
    fun startOfSumsEarlierParts() {
        assertEquals(0, MultiPartStack.startOf(twoParts, 0))
        assertEquals(3600, MultiPartStack.startOf(twoParts, 1))
    }

    // -- Mapping and source choice -------------------------------------------------------

    private fun movie(sources: List<MediaSource>, ticks: Long = HOUR_TICKS) = JellyfinItem(
        Id = "m", Name = "Big Film", RunTimeTicks = ticks, MediaSources = sources, Genres = listOf("Drama"),
    )

    @Test
    fun stackedMovieBlocksOutTheWholeRuntimeAndKeepsItsParts() {
        val m = JellyfinMapping.toMediaItem(movie(listOf(
            MediaSource(Id = "a", Name = "Big Film part 1.mkv", RunTimeTicks = HOUR_TICKS),
            MediaSource(Id = "b", Name = "Big Film part 2.mkv", RunTimeTicks = 2 * HOUR_TICKS),
        )), isMusicSection = false)!!
        assertEquals(180, m.duration)
        assertEquals(listOf("a", "b"), m.parts.map { it.sourceId })
        assertEquals(listOf(3600L, 7200L), m.parts.map { it.runtimeSeconds })
        assertEquals("a", m.mediaSourceId)
    }

    @Test
    fun versionsPickTheHighestBitrateAndTieBreakOnHeight() {
        fun v(id: String, br: Int?, h: Int) = MediaSource(Id = id, Name = id, Bitrate = br,
            MediaStreams = listOf(MediaStream(type = "Video", Height = h)))
        assertEquals("b", JellyfinMapping.bestMediaSource(listOf(v("a", 8_000_000, 1080), v("b", 40_000_000, 2160)))?.Id)
        assertEquals("b", JellyfinMapping.bestMediaSource(listOf(v("a", 8_000_000, 720), v("b", 8_000_000, 1080)))?.Id)
        assertEquals("a", JellyfinMapping.bestMediaSource(listOf(v("a", null, 480), v("b", null, 2160)))?.Id)
        assertNull(JellyfinMapping.bestMediaSource(emptyList()))

        val item = JellyfinMapping.toMediaItem(movie(listOf(v("a", 8_000_000, 1080), v("b", 40_000_000, 2160))), isMusicSection = false)!!
        assertEquals("b", item.mediaSourceId)
        assertEquals(emptyList<MediaPart>(), item.parts)
        assertEquals(60, item.duration)
    }

    @Test
    fun singleSourceItemsAskForNoSpecificSource() {
        val m = JellyfinMapping.toMediaItem(movie(listOf(MediaSource(Id = "only", Name = "Film.mkv", Bitrate = 5_000_000))), isMusicSection = false)!!
        assertNull("single-file playback requests must stay as they were", m.mediaSourceId)
        assertNull(JellyfinMapping.toMediaItem(movie(emptyList()), isMusicSection = false)!!.mediaSourceId)
    }

    @Test
    fun playbackUsesTheRequestedSourceElseTheFirst() {
        val info = PlaybackInfoResponse(
            MediaSources = listOf(
                PlaybackSource(Id = "a", SupportsDirectPlay = true),
                PlaybackSource(Id = "b", SupportsDirectPlay = true),
            ),
            PlaySessionId = "s",
        )
        assertEquals("b", PlaybackResolver.pickSource(info, "b")?.Id)
        assertEquals("a", PlaybackResolver.pickSource(info, null)?.Id)
        assertEquals("a", PlaybackResolver.pickSource(info, "gone")?.Id)
        val url = PlaybackResolver.resolve(info, "http://h", "item", "key", mediaSourceId = "b")!!.url
        assertEquals(true, url.contains("MediaSourceId=b"))
    }
}
