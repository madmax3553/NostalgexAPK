package io.github.madmax3553.staticgroot.channels

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/* Synthetic titles only. Each test names the case it guards. */
class LibraryChannelPlannerTest {

    @get:Rule val tmp = TemporaryFolder()

    private var n = 0
    private fun movie(title: String, genres: List<String> = emptyList(), minutes: Int = 100) =
        MediaItem(id = "m${n++}", title = title, type = MediaType.MOVIE, genres = genres, duration = minutes)

    private fun show(title: String, episodes: Int, genres: List<String> = emptyList(), minutes: Int = 45): List<MediaItem> =
        (1..episodes).map {
            MediaItem(id = "e${n++}", title = title, type = MediaType.EPISODE, genres = genres, duration = minutes,
                seriesId = "series:$title", seTag = "S01E%02d".format(it), episodeTitle = "Ep $it")
        }

    private fun names(plan: LibraryChannelPlanner.Plan) = plan.channels.map { it.definition.name }

    private fun plan(items: List<MediaItem>, existing: List<ChannelRuntime> = emptyList(), previous: List<GeneratedRecord> = emptyList(),
                     used: Set<Int> = emptySet()) = LibraryChannelPlanner.plan(items, existing, previous, used)

    // -- Franchises ------------------------------------------------------------------------

    @Test
    fun starTrekSeriesAndMoviesFormOneChannel() {
        val items = show("Star Trek: The Next Generation", 30, listOf("Science Fiction")) +
            show("Star Trek: Voyager", 30, listOf("Science Fiction")) +
            listOf("Star Trek: The Motion Picture", "Star Trek II: The Wrath of Khan", "Star Trek III: The Search for Spock")
                .map { movie(it, listOf("Science Fiction")) } +
            show("Unrelated Sitcom", 5)
        val plan = plan(items)
        val trek = plan.channels.single { it.spec.kind == ChannelSpec.FRANCHISE }
        assertEquals("STAR TREK", trek.definition.name)
        assertEquals(63, trek.pool.size)
        assertTrue(trek.pool.none { it.title == "Unrelated Sitcom" })
    }

    @Test
    fun aSharedFirstWordAloneIsNotAFranchise() {
        val items = listOf("American Pie", "American Beauty", "American Psycho", "American Gangster", "American Sniper").map { movie(it) }
        assertTrue(names(plan(items)).none { it.contains("AMERICAN") })
    }

    @Test
    fun fillerPhrasesAreNotFranchises() {
        val items = listOf("I Am Legend", "I Am Mother", "I Am Sam", "I Am Number Four", "I Am Love").map { movie(it) } +
            listOf("Love and Basketball", "Love and Monsters", "Love and Other Drugs", "Love and Death").map { movie(it) }
        assertEquals(emptyList<String>(), names(plan(items)))
    }

    @Test
    fun aSingleWordFranchiseNeedsASequelMarker() {
        val rocky = listOf("Rocky", "Rocky II", "Rocky III", "Rocky IV", "Rocky V", "Rocky VI").map { movie(it) }
        assertEquals(listOf("ROCKY"), names(plan(rocky)))
        val coincidence = listOf("Black Swan", "Black Panther", "Black Hawk Down", "Black Mass").map { movie(it) }
        assertEquals(emptyList<String>(), names(plan(coincidence)))
    }

    @Test
    fun aTwoWordPhraseNeedsFourTitlesOrAMarker() {
        val four = listOf("Harry Potter and the First", "Harry Potter and the Second", "Harry Potter and the Third", "Harry Potter and the Fourth", "Harry Potter and the Fifth", "Harry Potter and the Sixth").map { movie(it) }
        assertEquals(listOf("HARRY POTTER"), names(plan(four)))
        val three = listOf("Mad Dog One", "Mad Dog Two", "Mad Dog Three").map { movie(it) }
        assertEquals(emptyList<String>(), names(plan(three)))
        val marked = listOf("Toy Story", "Toy Story 2", "Toy Story 3", "Toy Story 4", "Toy Story 5", "Toy Story 6").map { movie(it, minutes = 120) }
        assertEquals(listOf("TOY STORY"), names(plan(marked)))
    }

    @Test
    fun theShorterPhraseWinsWhenPhrasesNest() {
        val items = listOf("Pirates of the Caribbean: One", "Pirates of the Caribbean: Two", "Pirates of the Caribbean: Three",
            "Pirates of the Caribbean: Four", "Pirates of the Caribbean: Five").map { movie(it, minutes = 140) }
        assertEquals(listOf("PIRATES OF THE CARIBBEAN"), names(plan(items)))
    }

    @Test
    fun aFranchiseTooSmallToFillTimeIsSkipped() {
        val items = listOf("Tiny Saga", "Tiny Saga 2").map { movie(it, minutes = 90) }
        assertEquals(emptyList<String>(), names(plan(items)))
        // Four films of 100 minutes is under ten hours: it would repeat all day.
        val four = listOf("Small Saga", "Small Saga 2", "Small Saga 3", "Small Saga 4").map { movie(it) }
        assertEquals(emptyList<String>(), names(plan(four)))
    }

    @Test
    fun broadGenresLikeDramaAndComedyNeverGetAChannel() {
        val items = show("Drama One", 40, listOf("Drama")) + show("Drama Two", 40, listOf("Drama")) +
            (1..60).map { movie("Laugh$it", listOf("Comedy")) }
        assertEquals(emptyList<String>(), names(plan(items)))
    }

    // -- Series and genres -----------------------------------------------------------------

    @Test
    fun aBigSeriesGetsItsOwnChannelAndASmallOneDoesNot() {
        val items = show("Long Runner", 80) + show("Short Runner", 12)
        val p = plan(items)
        assertEquals(listOf("LONG RUNNER"), names(p))
        assertEquals(80, p.channels[0].pool.size)
    }

    @Test
    fun aHeavyGenreGetsAChannelPerTypeAndFranchiseItemsAreNotRepeatedInIt() {
        val scifiEpisodes = show("Star Trek: Voyager", 40, listOf("Science Fiction")) +
            show("Star Trek: Deep Space", 40, listOf("Science Fiction")) +
            show("Star Trek: Enterprise", 40, listOf("Science Fiction")) + show("Star Trek: Discovery", 40, listOf("Science Fiction")) +
            show("Space Anthology", 30, listOf("Science Fiction")) + show("Another Space Show", 30, listOf("Science Fiction"))
        val filler = show("Drama Show", 40) + show("Other Drama", 40)
        val p = plan(scifiEpisodes + filler)
        val trek = p.channels.single { it.spec.kind == ChannelSpec.FRANCHISE }
        val sf = p.channels.single { it.spec.kind == ChannelSpec.GENRE }
        assertEquals("SCI-FI TV", sf.definition.name)
        assertEquals(60, sf.pool.size)
        val trekIds = trek.pool.mapTo(HashSet()) { it.id }
        assertTrue("franchise items must not repeat in the genre channel", sf.pool.none { it.id in trekIds })
    }

    @Test
    fun aGenreTheLibraryIsNotHeavyInGetsNoChannel() {
        val items = (1..5).map { movie("Space$it", listOf("Science Fiction")) } + (1..60).map { movie("Drama$it") }
        assertEquals(emptyList<String>(), names(plan(items)))
    }

    @Test
    fun kidsHorrorAndDocumentaryGenresNeverGetChannels() {
        val items = (1..40).map { movie("Scare$it", listOf("Horror")) } + (1..40).map { movie("Cartoon$it", listOf("Animation", "Comedy")) } +
            (1..40).map { movie("Doc$it", listOf("Documentary")) }
        assertEquals(emptyList<String>(), names(plan(items)))
    }

    @Test
    fun aGenreChannelThatMostlyRepeatsAnExistingChannelIsSkipped() {
        val items = (1..40).map { movie("Space$it", listOf("Science Fiction")) } + (1..20).map { movie("Drama$it") }
        val sf = items.filter { "Science Fiction" in it.genres }
        val existing = ChannelRuntime(ChannelDefinition(73, 73, "SCI-FI", "#fff"), sf)
        assertEquals(emptyList<String>(), names(plan(items, existing = listOf(existing))))
        assertEquals(listOf("SCI-FI FILMS"), names(plan(items)))
    }

    @Test
    fun musicVideosAreNeverUsed() {
        val clips = (1..80).map { MediaItem("c$it", "Band - Song $it", MediaType.MOVIE, genres = listOf("Rock"), duration = 4, librarySource = LibrarySource.MUSIC_VIDEO) }
        assertEquals(emptyList<String>(), names(plan(clips)))
    }

    // -- Stability -------------------------------------------------------------------------

    private val trekLibrary get() = show("Star Trek: The Next Generation", 30) + show("Star Trek: Voyager", 30) + listOf(movie("Star Trek II"), movie("Star Trek III"))

    @Test
    fun idsAreStableAndNumbersAvoidTheLineup() {
        val a = plan(trekLibrary, used = setOf(300, 301))
        val b = plan(trekLibrary.shuffled(), used = setOf(300, 301))
        assertEquals(a.channels[0].definition.id, b.channels[0].definition.id)
        assertTrue(a.channels[0].definition.id >= DYNAMIC_CHANNEL_ID_BASE)
        assertEquals(302, a.channels[0].definition.number)
    }

    @Test
    fun aKeptChannelSurvivesALibraryChangeWithItsIdAndNumberUntilItEmpties() {
        val first = plan(trekLibrary)
        val rec = first.records.single()
        // Library loses most Trek: too small to be proposed fresh, but kept because it was earned.
        val shrunk = show("Star Trek: Voyager", 6) + listOf(movie("Star Trek II")) + show("Other", 40)
        assertEquals(emptyList<String>(), names(plan(shrunk)))
        val kept = plan(shrunk, previous = first.records)
        assertEquals(rec.id, kept.channels.single().definition.id)
        assertEquals(rec.number, kept.channels.single().definition.number)
        // Down to two items it goes.
        val gone = plan(show("Star Trek: Voyager", 2) + show("Other", 40), previous = first.records)
        assertEquals(emptyList<String>(), names(gone))
    }

    @Test
    fun aKeptNumberThatAStaticChannelNowUsesMovesToAFreeOne() {
        val first = plan(trekLibrary)
        val rec = first.records.single()
        val moved = plan(trekLibrary, previous = first.records, used = setOf(rec.number)).channels.single()
        assertNotEquals(rec.number, moved.definition.number)
        assertEquals(rec.id, moved.definition.id)
    }

    @Test
    fun theNumberOfGeneratedChannelsIsCapped() {
        val items = (1..30).flatMap { i -> show("Show$i Alpha", 55) }
        assertTrue(plan(items).channels.size <= LibraryChannelPlanner.MAX_GENERATED)
    }

    @Test
    fun recordsRoundTripThroughTheStore() {
        val store = GeneratedChannelStore(File(tmp.root, "g.json"))
        assertEquals(emptyList<GeneratedRecord>(), store.load())
        val recs = plan(trekLibrary).records
        store.save(recs)
        assertEquals(recs, store.load())
        File(tmp.root, "g.json").writeText("{bad")
        assertEquals(emptyList<GeneratedRecord>(), store.load())
    }

    // -- Seasonal --------------------------------------------------------------------------

    private fun runtime(id: Int, number: Int, pool: List<MediaItem>) =
        ChannelRuntime(ChannelDefinition(id, number, "CH$id", "#aabbcc"), pool)

    private val shared = movie("Shared Title")
    private val config = ChannelConfig(
        version = 1,
        channels = emptyList(),
        bundles = listOf(
            BundleDefinition("seasonal", "SCREAM", channelIDs = listOf(131, 132), activeMonths = listOf(10)),
            BundleDefinition("tis-the-season", "TIS THE SEASON", channelIDs = listOf(130), activeMonths = listOf(12)),
            BundleDefinition("kids", "KIDZ", channelIDs = listOf(20)),
        ),
        exclusiveRules = emptyList(),
    )
    private val lineup = listOf(
        runtime(20, 20, listOf(movie("Kid Film"))),
        runtime(130, 130, listOf(movie("Xmas Film"))),
        runtime(131, 137, listOf(movie("Scare A"), shared)),
        runtime(132, 139, listOf(movie("Scare B"), shared)),
    )

    @Test
    fun inSeasonOneChannelHoldsTheUnionAndSitsWhereTheFirstMemberDid() {
        val r = SeasonalChannels.apply(config, lineup, LocalDate.of(2026, 10, 12))
        assertEquals(listOf(20), r.remaining.map { it.id })
        val ch = r.seasonal.single()
        assertEquals("SCREAM", ch.definition.name)
        assertEquals(137, ch.definition.number)
        assertEquals(3, ch.pool.size)
        assertEquals("a shared title counts once", 1, ch.pool.count { it.title == "Shared Title" })
        assertEquals(1, ch.definition.minItems)
    }

    @Test
    fun outOfSeasonSeasonalChannelsVanishEntirely() {
        val r = SeasonalChannels.apply(config, lineup, LocalDate.of(2026, 7, 4))
        assertEquals(listOf(20), r.remaining.map { it.id })
        assertTrue(r.seasonal.isEmpty())
    }

    @Test
    fun septemberOpensOnTheFifteenthLikeTvOS() {
        assertFalse(SeasonalChannels.isInSeason(listOf(9, 10), LocalDate.of(2026, 9, 14)))
        assertTrue(SeasonalChannels.isInSeason(listOf(9, 10), LocalDate.of(2026, 9, 15)))
        assertTrue(SeasonalChannels.isInSeason(null, LocalDate.of(2026, 1, 1)))
        assertFalse(SeasonalChannels.isInSeason(listOf(12), LocalDate.of(2026, 11, 30)))
    }

    @Test
    fun lineupCombinesAllThreeAndKeepsNumbersUnique() {
        val items = trekLibrary
        val r = Lineup.build(config, lineup, items, emptyList(), LocalDate.of(2026, 12, 20))
        assertEquals(listOf(20), r.channels.filter { it.id < DYNAMIC_CHANNEL_ID_BASE }.map { it.id })
        assertEquals(r.channels.size, r.channels.map { it.number }.toSet().size)
        assertEquals(listOf(130), r.generated.filter { it.spec.kind == ChannelSpec.SEASONAL }.map { it.definition.number })
        assertEquals("seasonal channels are decided by the calendar, never saved", 1, r.records.size)
        assertNull(r.records.firstOrNull { it.spec.kind == ChannelSpec.SEASONAL })
    }
}
