package io.github.madmax3553.nostalgex.channels

import java.io.File
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Rule-matching tests. Each one states a behaviour of tvOS AppState.filterItems or
 * Channel.filteredPool, so a failure names the Swift rule that changed.
 *
 * Fixed clock: 2026-10-10 12:00 UTC. Ids for manifest claims follow channels-memberships.json.
 */
class RuleFilterTest {

    private val clock = FilterClock(nowEpochSeconds = 1_791_633_600L, zone = ZoneOffset.UTC)
    private val noMemberships = ChannelMemberships.EMPTY

    private fun movie(
        title: String = "Test Movie",
        genres: List<String> = listOf("Drama"),
        year: Int? = 2000,
        rating: String? = "PG-13",
        tmdb: String? = null,
        viewCount: Int = 0,
        duration: Int = 100,
        source: LibrarySource? = null,
        originallyAvailableAt: String? = null,
        addedAt: Long = 0,
        studio: String? = null,
        episodeTitle: String? = null,
        type: MediaType = MediaType.MOVIE,
        userRating: Double = 0.0,
        rating10: Double = 8.0,
        id: String = title,
    ) = MediaItem(
        id = id, title = title, type = type, genres = genres, year = year,
        contentRating = rating, duration = duration, viewCount = viewCount,
        tmdbID = tmdb, librarySource = source, originallyAvailableAt = originallyAvailableAt,
        addedAt = addedAt, studio = studio, episodeTitle = episodeTitle,
        userRating = userRating, rating = rating10,
    )

    private fun run(
        items: List<MediaItem>,
        rules: ChannelRules?,
        channelID: Int = 1,
        category: String? = null,
        memberships: ChannelMemberships = noMemberships,
        exclusive: List<ExclusiveRule> = emptyList(),
        enrichment: Map<String, MediaEnrichment> = emptyMap(),
    ): List<String> = RuleFilter.filter(
        cached = items.map { buildFilterCache(it, enrichment[it.id]) },
        rules = rules,
        channelID = channelID,
        category = category,
        memberships = memberships,
        exclusiveRules = exclusive,
        clock = clock,
    ).map { it.title }

    private fun passes(item: MediaItem, rules: ChannelRules, channelID: Int = 1) =
        run(listOf(item), rules, channelID = channelID).isNotEmpty()

    // -- Haystack and title helpers ---------------------------------------------------

    @Test
    fun yearSuffixIsStrippedOnlyWhenItIsExactlyTheYear() {
        assertEquals("bluey", stripTrailingYearSuffix("bluey (2018)"))
        assertEquals("bluey (20180)", stripTrailingYearSuffix("bluey (20180)"))
        assertEquals("bluey (201x)", stripTrailingYearSuffix("bluey (201x)"))
    }

    @Test
    fun wordMatchRespectsBoundaries() {
        assertTrue(titleContainsWord("don't see me", "see"))
        assertFalse(titleContainsWord("stickbird", "see"))
        assertFalse(titleContainsWord("stylesheet", "style"))
        assertTrue(titleContainsWord("warmth or war", "war"))
        assertFalse(titleContainsWord("warmth", "war"))
    }

    @Test
    fun artistIsPartOfTheHaystack() {
        val mv = movie(title = "Thriller").copy(artist = "Michael Jackson")
        assertEquals("michael jackson thriller", filterTitleHaystack(mv))
    }

    // -- No rules, type, source -------------------------------------------------------

    @Test
    fun nullRulesPassEverything() {
        assertEquals(listOf("A", "B"), run(listOf(movie("A"), movie("B")), rules = null))
    }

    @Test
    fun typeGateKeepsOnlyTheRequestedMediaType() {
        val rules = ChannelRules(type = "episode")
        val items = listOf(movie("Film"), movie("Show", type = MediaType.EPISODE))
        assertEquals(listOf("Show"), run(items, rules))
    }

    @Test
    fun musicVideosAreExcludedUnlessTheChannelAsksForThem() {
        val mv = movie("Clip", source = LibrarySource.MUSIC_VIDEO)
        assertFalse(passes(mv, ChannelRules()))
        assertTrue(passes(mv, ChannelRules(source = "musicVideo")))
    }

    @Test
    fun unknownSourceOrTypeStringIsIgnoredLikeSwift() {
        // Swift: MediaType(rawValue:) and LibrarySource(rawValue:) return nil, so no gate applies.
        assertTrue(passes(movie("Any"), ChannelRules(type = "bogus")))
    }

    // -- Genre locks and genre rules --------------------------------------------------

    @Test
    fun horrorIsLockedFromChannelsThatDoNotIncludeIt() {
        val horror = movie("Scream", genres = listOf("Horror"))
        assertFalse(passes(horror, ChannelRules(genres = GenreRules(include = listOf("Comedy")))))
        assertTrue(passes(horror, ChannelRules(genres = GenreRules(include = listOf("Horror")))))
    }

    @Test
    fun studioOnlyChannelBypassesGenreLocks() {
        val horror = movie("Scream", genres = listOf("Horror"), studio = "Netflix")
        assertTrue(passes(horror, ChannelRules(studios = listOf("Netflix"))))
    }

    @Test
    fun studioAndGenreTogetherRequireBoth() {
        val rules = ChannelRules(studios = listOf("Disney"), genres = GenreRules(include = listOf("Animation")))
        val disneyAnimated = movie("Up", genres = listOf("Animation"), studio = "Disney Pixar")
        val disneyLive = movie("Hocus", genres = listOf("Comedy"), studio = "Disney")
        val pixarAnimated = movie("Cars", genres = listOf("Animation"), studio = "Pixar")
        assertTrue(passes(disneyAnimated, rules))
        assertFalse(passes(disneyLive, rules))
        assertFalse(passes(pixarAnimated, rules))
    }

    @Test
    fun genreExcludeRemovesMatches() {
        val rules = ChannelRules(genres = GenreRules(include = listOf("Drama"), exclude = listOf("Family")))
        assertFalse(passes(movie(genres = listOf("Drama", "Family")), rules))
        assertTrue(passes(movie(genres = listOf("Drama")), rules))
    }

    @Test
    fun genreRequireAllNeedsEveryGenre() {
        val rules = ChannelRules(genres = GenreRules(requireAll = listOf("Comedy", "Romance")))
        assertFalse(passes(movie(genres = listOf("Comedy")), rules))
        assertTrue(passes(movie(genres = listOf("Comedy", "Romance")), rules))
    }

    @Test
    fun realityChannelsShowOnlyRealityContent() {
        val rules = ChannelRules(genres = GenreRules(include = listOf("Reality")))
        assertFalse(passes(movie(genres = listOf("Drama")), rules))
        assertTrue(passes(movie(genres = listOf("Reality")), rules))
    }

    // -- Title rules ------------------------------------------------------------------

    @Test
    fun titleContainsUsesWordBoundaries() {
        val rules = ChannelRules(titleContains = listOf("See"))
        assertTrue(passes(movie("Don't See Me"), rules))
        assertFalse(passes(movie("Stickbird"), rules))
    }

    @Test
    fun titleCuratedChannelIgnoresYearSuffix() {
        val rules = ChannelRules(titleContains = listOf("bluey"))
        assertTrue(passes(movie("Bluey (2018)", genres = listOf("Animation")), rules))
    }

    @Test
    fun titleExcludesRemoveWordMatches() {
        val rules = ChannelRules(genres = GenreRules(include = listOf("Drama")), titleExcludes = listOf("war"))
        assertFalse(passes(movie("War Games"), rules))
        assertTrue(passes(movie("Warmth"), rules))
    }

    @Test
    fun editorialOverrideIncludesEvenWhenOtherRulesFail() {
        val rules = ChannelRules(type = "episode", editorialOverrides = listOf("the holiday"))
        assertTrue(passes(movie("The Holiday"), rules))
    }

    // -- Episode titles ---------------------------------------------------------------

    @Test
    fun episodeTitleRuleIsAnAllowlist() {
        val rules = ChannelRules(episodeTitleContains = listOf("holiday"))
        val hit = movie("Friends", type = MediaType.EPISODE, episodeTitle = "The One with the Holiday Armadillo")
        val miss = movie("Friends", type = MediaType.EPISODE, episodeTitle = "The One with the Ross")
        assertTrue(passes(hit, rules))
        assertFalse(passes(miss, rules))
    }

    // -- Ratings, years, duration, watch status ---------------------------------------

    @Test
    fun contentRatingsWhitelistAndAllowUnrated() {
        val rules = ChannelRules(contentRatings = listOf("PG", "G"))
        assertTrue(passes(movie(rating = "PG"), rules))
        assertFalse(passes(movie(rating = "R"), rules))
        assertFalse(passes(movie(rating = null), rules))
        assertTrue(passes(movie(rating = null), rules.copy(allowUnrated = true)))
    }

    @Test
    fun kidsCategoryDropsAdultRatings() {
        val rules = ChannelRules(genres = GenreRules(include = listOf("Animation")))
        val r = movie("Adult Toon", genres = listOf("Animation"), rating = "R")
        assertFalse(run(listOf(r), rules, category = "kids").isNotEmpty())
        assertTrue(run(listOf(r), rules, category = null).isNotEmpty())
    }

    @Test
    fun yearRangeKeepsUnknownYears() {
        val rules = ChannelRules(yearRange = YearRange(min = 1980, max = 1989))
        assertTrue(passes(movie(year = 1985), rules))
        assertFalse(passes(movie(year = 1995), rules))
        assertTrue(passes(movie(year = null, originallyAvailableAt = null), rules))
    }

    @Test
    fun durationRangeInMinutes() {
        val rules = ChannelRules(durationRange = DurationRange(min = 20, max = 30))
        assertTrue(passes(movie(duration = 25, type = MediaType.EPISODE), rules))
        assertFalse(passes(movie(duration = 45, type = MediaType.EPISODE), rules))
    }

    @Test
    fun ratingMinFailsOnlyWhenBothRatingsAreLow() {
        val rules = ChannelRules(ratingMin = 6.0)
        assertFalse(passes(movie(rating10 = 5.0, userRating = 0.0), rules))
        assertTrue(passes(movie(rating10 = 5.0, userRating = 7.0), rules))
    }

    @Test
    fun watchStatusRules() {
        val seen = movie("Seen", viewCount = 2, year = 1995)
        val unseen = movie("Unseen", viewCount = 0, year = 1995)
        val newRelease = movie("New", viewCount = 0, year = 2026)
        assertFalse(passes(unseen, ChannelRules(watchedOnly = true)))
        assertTrue(passes(seen, ChannelRules(watchedOnly = true)))
        // A new release counts as unwatched even with no views.
        assertTrue(passes(newRelease, ChannelRules(watchedOnly = true)))
        assertFalse(passes(seen, ChannelRules(unwatchedOnly = true)))
        assertTrue(passes(unseen, ChannelRules(unwatchedOnly = true)))
    }

    @Test
    fun rewatchedMeansThreeOrMoreViews() {
        assertFalse(passes(movie(viewCount = 2), ChannelRules(rewatched = true)))
        assertTrue(passes(movie(viewCount = 3), ChannelRules(rewatched = true)))
    }

    @Test
    fun releasedWithinMonthsUsesTheFullDateWhenPresent() {
        val rules = ChannelRules(releasedWithinMonths = 6)
        // Clock is 2026-10-10: six months back is 2026-04-10.
        assertTrue(passes(movie(originallyAvailableAt = "2026-06-01"), rules))
        assertFalse(passes(movie(originallyAvailableAt = "2025-01-01"), rules))
    }

    @Test
    fun releasedWithinMonthsFallsBackToYear() {
        val rules = ChannelRules(releasedWithinMonths = 6)
        // Six months rounds to the current year only.
        assertTrue(passes(movie(year = 2026, originallyAvailableAt = null), rules))
        assertFalse(passes(movie(year = 2024, originallyAvailableAt = null), rules))
    }

    @Test
    fun addedWithinDaysUsesUnixSeconds() {
        val now = clock.nowEpochSeconds
        val rules = ChannelRules(addedWithinDays = 30)
        assertTrue(passes(movie(addedAt = now - 10L * 86400), rules))
        assertFalse(passes(movie(addedAt = now - 60L * 86400), rules))
    }

    // -- Enrichment gates -------------------------------------------------------------

    @Test
    fun omdbGatesRejectUnenrichedItems() {
        val rules = ChannelRules(imdbRatingMin = 6.5)
        val item = movie("Foreign", genres = listOf("Drama"))
        assertFalse(run(listOf(item), rules).isNotEmpty())
        val enriched = mapOf(item.id to MediaEnrichment(imdbRating = 7.0))
        assertEquals(listOf("Foreign"), run(listOf(item), rules, enrichment = enriched))
    }

    @Test
    fun wonOscarReadsTheAwardsString() {
        val rules = ChannelRules(wonOscar = true)
        val item = movie("Oscar Film")
        val won = mapOf(item.id to MediaEnrichment(awards = "Won 2 Oscars. 14 wins total."))
        val nominated = mapOf(item.id to MediaEnrichment(awards = "Nominated for 3 Oscars."))
        assertTrue(run(listOf(item), rules, enrichment = won).isNotEmpty())
        assertTrue(run(listOf(item), rules, enrichment = nominated).isEmpty())
    }

    @Test
    fun keywordsNeedAnyGenreWhenConfigured() {
        val rules = ChannelRules(
            keywords = listOf("wedding"),
            keywordsRequireAnyGenre = listOf("comedy"),
        )
        val kw = mapOf("c" to MediaEnrichment(keywords = listOf("wedding")))
        val comedy = movie("Comedy Wedding", genres = listOf("Comedy"), id = "c")
        val drama = movie("Drama Wedding", genres = listOf("Drama"), id = "d")
        val dramaKw = mapOf("d" to MediaEnrichment(keywords = listOf("wedding")))
        assertEquals(listOf("Comedy Wedding"), run(listOf(comedy, drama), rules, enrichment = kw + dramaKw))
    }

    @Test
    fun keywordExcludeIsSoftForUnenrichedItems() {
        val rules = ChannelRules(genres = GenreRules(include = listOf("Drama")), keywordsExclude = listOf("zombie"))
        val item = movie("Plain", genres = listOf("Drama"), id = "p")
        val zombie = movie("Undead", genres = listOf("Drama"), id = "z")
        val enriched = mapOf("z" to MediaEnrichment(keywords = listOf("zombie")))
        assertEquals(listOf("Plain"), run(listOf(item, zombie), rules, enrichment = enriched))
    }

    // -- Manifest, exclusivity --------------------------------------------------------

    private fun membership(vararg entries: Pair<String, MembershipEntry>) =
        ChannelMemberships(mapOf(*entries))

    @Test
    fun manifestClaimAddsItemWithoutGenreMatch() {
        val claimed = membership("movie:1" to MembershipEntry(channels = listOf(5)))
        val rules = ChannelRules(genres = GenreRules(include = listOf("Comedy")))
        val item = movie("Claimed", genres = listOf("Drama"), tmdb = "1")
        assertEquals(listOf("Claimed"), run(listOf(item), rules, channelID = 5, memberships = claimed))
        assertTrue(run(listOf(item), rules, channelID = 6, memberships = claimed).isEmpty())
    }

    @Test
    fun manifestClaimStillHonoursGenreExclude() {
        val claimed = membership("movie:1" to MembershipEntry(channels = listOf(5)))
        val rules = ChannelRules(genres = GenreRules(include = listOf("Comedy"), exclude = listOf("Animation")))
        val item = movie("Aladdin-like", genres = listOf("Animation"), tmdb = "1")
        assertTrue(run(listOf(item), rules, channelID = 5, memberships = claimed).isEmpty())
    }

    @Test
    fun manifestExclusiveLockBlocksOtherChannels() {
        val locked = membership("movie:2" to MembershipEntry(channels = listOf(9), exclusive = 9))
        val rules = ChannelRules(genres = GenreRules(include = listOf("Comedy")))
        val item = movie("Locked", genres = listOf("Comedy"), tmdb = "2")
        assertTrue(run(listOf(item), rules, channelID = 5, memberships = locked).isEmpty())
    }

    @Test
    fun exclusiveRuleBlocksTheRuleOwnerOnlyFromOtherChannels() {
        val anime = ExclusiveRule(
            channelIDs = listOf(7), genres = listOf("Anime"), titleContains = emptyList(),
            editorialTitles = emptyList(), manifestExclusive = false,
        )
        val rules = ChannelRules(genres = GenreRules(include = listOf("Animation")))
        val item = movie("Akira", genres = listOf("Anime", "Animation"))
        assertTrue(run(listOf(item), rules, channelID = 3, exclusive = listOf(anime)).isEmpty())
        assertEquals(listOf("Akira"), run(listOf(item), rules, channelID = 7, exclusive = listOf(anime)))
    }

    @Test
    fun exclusiveEditorialTitleIgnoresTheYearSuffix() {
        // Plex stores "Elf (2003)". An editorial entry of "Elf" must still match.
        val rule = ExclusiveRule(
            channelIDs = listOf(4), genres = emptyList(), titleContains = emptyList(),
            editorialTitles = listOf("Elf"), manifestExclusive = false,
        )
        assertTrue(rule.matches(movie("Elf (2003)")))
    }

    // -- Time-of-day restrictions (Channel.filteredPool) -----------------------------

    @Test
    fun primeTimeGateEmptiesThePoolBeforeTheHour() {
        val pool = listOf(movie("A"), movie("B"))
        assertTrue(filteredPool(pool, TimeRestrictions(onlyAfterHour = 20), hour = 19).isEmpty())
        assertEquals(2, filteredPool(pool, TimeRestrictions(onlyAfterHour = 20), hour = 20).size)
    }

    @Test
    fun blockRatingsApplyOnlyWhenTwoItemsRemain() {
        val pool = listOf(movie("R1", rating = "R"), movie("G1", rating = "G"), movie("G2", rating = "G"))
        val restricted = TimeRestrictions(blockRatings = BlockRatings(ratings = listOf("R"), blockBefore = 20))
        assertEquals(listOf("G1", "G2"), filteredPool(pool, restricted, hour = 19).map { it.title })
        assertEquals(3, filteredPool(pool, restricted, hour = 21).size)
    }

    @Test
    fun tvOnlyBeforeKeepsEpisodesWhenEnoughRemain() {
        val pool = listOf(
            movie("Film"),
            movie("E1", type = MediaType.EPISODE),
            movie("E2", type = MediaType.EPISODE),
        )
        val tr = TimeRestrictions(tvOnlyBefore = 11)
        assertEquals(listOf("E1", "E2"), filteredPool(pool, tr, hour = 9).map { it.title })
        assertEquals(3, filteredPool(pool, tr, hour = 12).size)
    }

    // -- Shared fixture --------------------------------------------------------------

    @Test
    fun membershipManifestParsesTheSharedFile() {
        val file = File("../../channels-memberships.json")
        check(file.exists()) { "channels-memberships.json not found at ${file.absolutePath}" }
        val m = ChannelMemberships.parse(file.readText())
        assertTrue("manifest should not be empty", m.size > 0)
    }
}
