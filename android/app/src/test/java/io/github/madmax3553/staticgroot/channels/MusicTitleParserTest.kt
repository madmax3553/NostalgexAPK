package io.github.madmax3553.staticgroot.channels

import io.github.madmax3553.staticgroot.jellyfin.JellyfinItem
import io.github.madmax3553.staticgroot.jellyfin.JellyfinMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The inputs and expectations below are copied from tvOS MusicMetadataTests.swift
 * (MusicMetadataTests, DeezerEnrichmentTests, MusicYearTests). Titles there are real ripped
 * filename shapes, not library contents.
 */
class MusicTitleParserTest {

    private fun parse(s: String) = MusicTitleParser.parse(s)

    @Test
    fun artistDashTitleAndTitleByArtist() {
        assertEquals(MusicTitleParser.Parsed("Michael Jackson", "Billie Jean"), parse("Michael Jackson - Billie Jean"))
        assertEquals(MusicTitleParser.Parsed("Michael Jackson", "Billie Jean"), parse("Billie Jean by Michael Jackson"))
        assertEquals(MusicTitleParser.Parsed("Michael Jackson", "Billie Jean"), parse("Michael Jackson - Billie Jean (1983)"))
    }

    @Test
    fun cleanerTakesRippedFilenamesDownToSearchableText() {
        assertEquals("Ruff Ryders - Get Wild", MusicTitleParser.clean("Ruff_Ryders_-_Get_Wild_[MMV]"))
        assertEquals("Avril Lavigne - Don't Tell Me", MusicTitleParser.clean("Avril Lavigne - Don't Tell Me video!"))
        assertEquals("Beatles - From Me To You", MusicTitleParser.clean("Beatles - From Me To You(11-4-1963 Royal Variety)"))
        assertEquals("the used-take it away", MusicTitleParser.clean("the_used-take_it_away-vgb-prv"))
        assertEquals("Michael Jackson - Smooth Criminal", MusicTitleParser.clean("Michael Jackson - Smooth Criminal (1988)"))
    }

    @Test
    fun parserSplitsTheShapesTheLibraryActuallyHas() {
        assertEquals(MusicTitleParser.Parsed("Ruff Ryders", "Get Wild"), parse("Ruff_Ryders_-_Get_Wild_[MMV]"))
        assertEquals(MusicTitleParser.Parsed("B.o.B", "Airplanes"), parse("B.o.B - Airplanes ft. Hayley Williams of Paramore"))
        assertEquals(MusicTitleParser.Parsed("Nelly", "Body On Me"), parse("Nelly ft. Akon ft. Ashanti - Body On Me"))
        assertEquals(MusicTitleParser.Parsed("Beatles", "I'm A Loser"), parse("Beatles - I'm A Loser - Boys(Shindig 1964)"))
        assertEquals(MusicTitleParser.Parsed("the used", "take it away"), parse("the_used-take_it_away-vgb-prv"))
        assertEquals(MusicTitleParser.Parsed(null, "Anaconda"), parse("Anaconda"))
    }

    @Test
    fun aHyphenatedTitleWithNoArtistIsNotSplitIntoNonsense() {
        val c = parse("Twenty-One")
        assertTrue(c.artist == null || c.song.length >= 2)
    }

    @Test
    fun emptyAndSymbolOnlyNamesDoNotCrash() {
        assertEquals(MusicTitleParser.Parsed(null, ""), parse(""))
        assertEquals(MusicTitleParser.Parsed(null, ""), parse(" - "))
    }

    // -- Year trust order ---------------------------------------------------------------

    @Test
    fun yearInTheFilenameWinsOverEverything() {
        assertEquals(1988, MusicTitleParser.yearInTitle("Michael Jackson - Smooth Criminal (1988)"))
        // A date inside a note is not a (YYYY) suffix.
        assertNull(MusicTitleParser.yearInTitle("Beatles - From Me To You(11-4-1963 Royal Variety)"))
        assertNull(MusicTitleParser.yearInTitle("Anaconda"))
        assertEquals(1988, MusicYear.resolve(titleYear = 1988, musicBrainzYear = 2009, serverYear = 1970, thisYear = 2026))
    }

    @Test
    fun musicBrainzBeatsTheServerAndTheServerOnlyCountsWhenPlausible() {
        assertEquals(1977, MusicYear.resolve(null, 1977, 2026, thisYear = 2026))
        assertNull("1970 is the server's unknown", MusicYear.resolve(null, null, 1970, thisYear = 2026))
        assertNull("this year is the date added", MusicYear.resolve(null, null, 2026, thisYear = 2026))
        assertEquals(2014, MusicYear.resolve(null, null, 2014, thisYear = 2026))
        assertNull(MusicYear.resolve(null, null, null, thisYear = 2026))
    }

    // -- Mapping and display ------------------------------------------------------------

    private fun clip(name: String, year: Int?, genres: List<String> = listOf("Pop")) = JellyfinItem(
        Id = "i", Name = name, ProductionYear = year, Genres = genres,
        RunTimeTicks = 4 * JellyfinMapping.TICKS_PER_MINUTE,
    )

    @Test
    fun musicSectionItemGetsArtistTitleAndTheFilenameYear() {
        val m = JellyfinMapping.toMediaItem(clip("Bee Gees - Night Fever (1977)", 1970), isMusicSection = true)!!
        assertEquals("Night Fever", m.title)
        assertEquals("Bee Gees", m.artist)
        assertEquals(1977, m.year)
        assertEquals(LibrarySource.MUSIC_VIDEO, m.librarySource)
        assertEquals("Bee Gees \u00B7 Night Fever", m.displayTitle)
    }

    @Test
    fun implausibleServerYearIsDroppedForMusicVideosOnly() {
        assertNull(JellyfinMapping.toMediaItem(clip("Anaconda", 1970), isMusicSection = true)!!.year)
        // A movie keeps whatever the server says, 1970 included.
        val movie = JellyfinMapping.toMediaItem(
            clip("Night Fever (1977)", 1970, genres = listOf("Drama")).copy(RunTimeTicks = 100 * JellyfinMapping.TICKS_PER_MINUTE),
            isMusicSection = false,
        )!!
        assertEquals(1970, movie.year)
        assertEquals("Night Fever (1977)", movie.title)
        assertNull(movie.artist)
        assertFalse(movie.isMusicVideo)
        assertEquals("Night Fever (1977)", movie.displayTitle)
    }

    @Test
    fun shortMusicGenreClipInAMovieLibraryIsAMusicVideoToo() {
        val m = JellyfinMapping.toMediaItem(clip("Queen - Radio Ga Ga", 1984, genres = listOf("Rock", "Music")), isMusicSection = false)!!
        assertTrue(m.isMusicVideo)
        assertEquals("Queen", m.artist)
    }

    @Test
    fun anEpisodeIsNeverAMusicVideo() {
        val ep = MediaItem(id = "e", title = "Show", type = MediaType.EPISODE, librarySource = LibrarySource.MUSIC_VIDEO, artist = "X")
        assertFalse(ep.isMusicVideo)
        assertEquals("Show", ep.displayTitle)
    }
}
