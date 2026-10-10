package io.github.madmax3553.nostalgex.channels

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Port of tvOS AppState+Channels.swift: filterItems, its helpers, and the
 * Channel.matches / filteredPool logic from Models/Channel.swift.
 *
 * Swift reads Date() and Calendar.current inside the filter. Here the clock and the
 * time zone are parameters, so the rules can be tested deterministically. This is the
 * only intended difference from the Swift code.
 *
 * Where the Swift code and scripts/nostalgex-channel-filter.cjs agree, the comments
 * below say so. Where Swift adds behaviour that the web filter lacks, that is kept.
 */

/** Per-item data computed once per filter pass, like Swift's FilterCache. */
data class FilterCache(
    val item: MediaItem,
    /** Lowercased, artist-prefixed, trailing " (YYYY)" stripped. */
    val titleLower: String,
    /** "" unless the item is an episode with an episode title. */
    val episodeTitleLower: String,
    val genresLower: Set<String>,
    val enrichment: MediaEnrichment?,
    val studioLower: String,
)

fun buildFilterCache(item: MediaItem, enrichment: MediaEnrichment?): FilterCache = FilterCache(
    item = item,
    titleLower = filterTitleHaystack(item),
    episodeTitleLower = (item.episodeTitle ?: "").lowercase(),
    genresLower = item.genres.map { it.lowercase() }.toSet(),
    enrichment = enrichment,
    studioLower = (item.studio ?: "").lowercase(),
)

private val ADULT_RATINGS = setOf("R", "NC-17", "TV-MA", "18", "18+", "X", "NR")

private val HORROR_GENRES = setOf("horror")
private val REALITY_GENRES = setOf("reality", "game show", "game-show", "reality-tv")
private val ANIMATION_GENRES = setOf("animation", "animated", "cartoon")
// "history" is deliberately not in DOCUMENTARY or WAR. See the Swift comment: Plex tags
// dramas like Apollo 13 as History, and no channel includes History.
private val DOCUMENTARY_GENRES = setOf("documentary", "docuseries")
private val WAR_GENRES = setOf("war", "war & politics")
private val WESTERN_GENRES = setOf("western")
private val TALK_SHOW_GENRES = setOf("talk show", "talk", "news")

/** Haystack for title rules: artist (when present) plus title, lowercased, year suffix stripped. */
fun filterTitleHaystack(item: MediaItem): String {
    val parts = mutableListOf<String>()
    item.artist?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    parts.add(item.title)
    return stripTrailingYearSuffix(parts.joinToString(" ").lowercase())
}

/** Strip a trailing " (YYYY)" from a lowercased title. Mirrors Swift's manual scan. */
fun stripTrailingYearSuffix(s: String): String {
    if (s.length < 7 || !s.endsWith(")")) return s
    val tail = s.substring(s.length - 7)
    val ok = tail[0] == ' ' && tail[1] == '(' && tail[2].isDigit() && tail[3].isDigit() &&
        tail[4].isDigit() && tail[5].isDigit() && tail[6] == ')'
    return if (ok) s.substring(0, s.length - 7) else s
}

/**
 * Word-boundary substring match, case-insensitive. The needle matches when it appears in
 * the haystack with a non-letter, non-digit (or an edge) on each side. Needle "See" matches
 * "Don't See Me" but not "Stickbird". Both arguments are expected to be lowercase already.
 */
fun titleContainsWord(haystackLower: String, needleLower: String): Boolean {
    if (needleLower.isEmpty()) return false
    var start = 0
    while (true) {
        val idx = haystackLower.indexOf(needleLower, start)
        if (idx < 0) return false
        val end = idx + needleLower.length
        val leftOk = idx == 0 || !haystackLower[idx - 1].isLetterOrDigit()
        val rightOk = end == haystackLower.length || !haystackLower[end].isLetterOrDigit()
        if (leftOk && rightOk) return true
        start = idx + 1
    }
}

/** Mirrors Swift `itemReleaseYear`: the year field, else the year of originallyAvailableAt. */
fun itemReleaseYear(item: MediaItem): Int? {
    item.year?.let { return it }
    return parseReleaseDate(item.originallyAvailableAt)?.year
}

/** YYYY-MM-DD, read as a date with no time zone, as Swift's UTC formatter does. */
private fun parseReleaseDate(raw: String?): LocalDate? =
    raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** Era gate. Items with no known year pass, so sparse metadata does not drop good matches. */
fun passesYearRange(item: MediaItem, range: YearRange?): Boolean {
    if (range == null) return true
    val year = itemReleaseYear(item) ?: return true
    range.min?.let { if (year < it) return false }
    range.max?.let { if (year > it) return false }
    return true
}

/** Exact-source checks. An explicit `source` restricts the pool; without one, music videos are kept out. */
private fun ChannelRules.resolvedSource(): LibrarySource? = LibrarySource.fromRaw(source)

private fun ChannelRules.resolvedType(): MediaType? = MediaType.fromRaw(type)

private fun channelIncludesGenre(rules: ChannelRules?, set: Set<String>): Boolean =
    rules?.genres?.include?.any { it.lowercase() in set } ?: false

/** Port of Channel.matches for an ExclusiveRule (Models/Channel.swift). */
fun ExclusiveRule.matches(item: MediaItem): Boolean {
    val titleLower = item.title.lowercase()
    // Plex's TMDB agent stores films as "Elf (2003)". The year suffix is ignored for
    // editorial matches, same as every other title comparison in the app.
    val strippedLower = stripTrailingYearSuffix(item.title.lowercase())
    if (editorialTitles.any { val c = it.lowercase(); titleLower == c || strippedLower == c }) return true
    if (titleContains.isNotEmpty() && titleContains.any { titleLower.contains(it.lowercase()) }) return true
    if (genres.isNotEmpty()) {
        val hit = item.genres.any { g -> genres.any { gg -> g.lowercase().contains(gg.lowercase()) } }
        if (hit) return true
    }
    return false
}

/**
 * Port of Channel.filteredPool (Models/Channel.swift): time-of-day restrictions.
 * [hour] is the local hour 0-23.
 */
fun filteredPool(pool: List<MediaItem>, restrictions: TimeRestrictions?, hour: Int): List<MediaItem> {
    if (restrictions == null) return pool
    var result = pool

    restrictions.blockRatings?.let { br ->
        val blocked = (br.blockBefore?.let { hour < it } ?: false) ||
            (br.blockAfter?.let { hour >= it } ?: false)
        if (blocked) {
            val filtered = result.filter { (it.contentRating ?: "") !in br.ratings }
            if (filtered.size >= 2) result = filtered
        }
    }

    restrictions.tvOnlyBefore?.let { tvBefore ->
        if (hour < tvBefore) {
            val episodesOnly = result.filter { it.type == MediaType.EPISODE }
            if (episodesOnly.size >= 2) result = episodesOnly
        }
    }

    // Prime-time gate: the channel is unavailable before this hour.
    restrictions.onlyAfterHour?.let { afterHour ->
        if (hour < afterHour) return emptyList()
    }

    return result
}

/** Time context for a filter pass. Injected so the rules can be tested without the system clock. */
data class FilterClock(val nowEpochSeconds: Long, val zone: ZoneId) {
    val now: ZonedDateTime get() = ZonedDateTime.ofInstant(Instant.ofEpochSecond(nowEpochSeconds), zone)
    val currentYear: Int get() = now.year
}

object RuleFilter {

    /**
     * Port of AppState.filterItems. Returns the items that pass [rules] for [channelID].
     * [category] is the bundle category (for example "kids"). A null [rules] passes everything.
     */
    fun filter(
        cached: List<FilterCache>,
        rules: ChannelRules?,
        channelID: Int,
        category: String?,
        memberships: ChannelMemberships,
        exclusiveRules: List<ExclusiveRule>,
        clock: FilterClock,
    ): List<MediaItem> {
        if (rules == null) return cached.map { it.item }

        val currentYear = clock.currentYear
        val isKids = category == "kids"
        val hasFamilyGenre = rules.genres?.include?.any { it.lowercase() == "family" } ?: false
        val isFamilySafe = isKids || hasFamilyGenre

        // Studio-only channels (streamers such as Netflix or HBO) bypass genre locks,
        // so they show all their content.
        val isStudioChannel = !(rules.studios ?: emptyList()).isEmpty() &&
            (rules.genres?.include?.isEmpty() ?: true)

        val allowsHorror = isStudioChannel || channelIncludesGenre(rules, HORROR_GENRES)
        val allowsReality = isStudioChannel || channelIncludesGenre(rules, REALITY_GENRES)
        val hasTitleCurated = !(rules.titleContains ?: emptyList()).isEmpty()
        val allowsAnimation = isStudioChannel || channelIncludesGenre(rules, ANIMATION_GENRES) ||
            isKids || hasTitleCurated
        val allowsDocumentary = isStudioChannel || channelIncludesGenre(rules, DOCUMENTARY_GENRES)
        val allowsWar = isStudioChannel || channelIncludesGenre(rules, WAR_GENRES)
        val allowsWestern = isStudioChannel || channelIncludesGenre(rules, WESTERN_GENRES)
        val allowsTalkShow = isStudioChannel || channelIncludesGenre(rules, TALK_SHOW_GENRES)

        fun lower(list: List<String>?): List<String> = list?.map { it.lowercase() } ?: emptyList()

        val titleContainsLower = lower(rules.titleContains)
        val episodeTitleContainsLower = lower(rules.episodeTitleContains)
        val titleExcludesLower = lower(rules.titleExcludes)
        val studiosLower = lower(rules.studios)
        val keywordsLower = lower(rules.keywords)
        val keywordsExcludeLower = lower(rules.keywordsExclude)
        val keywordsRequireAnyGenreLower = lower(rules.keywordsRequireAnyGenre)
        val keywordGatedGenresLower = lower(rules.keywordGatedGenres)
        val networksLower = lower(rules.networks)
        val productionCompaniesLower = lower(rules.productionCompanies)
        val genresIncludeLower = lower(rules.genres?.include)
        val genresExcludeLower = lower(rules.genres?.exclude)
        val genresRequireAllLower = lower(rules.genres?.requireAll)
        val editorialOverridesLower = lower(rules.editorialOverrides)
        val contentRatingsSet: Set<String>? = rules.contentRatings?.toSet()

        val resolvedSource = rules.resolvedSource()
        val resolvedType = rules.resolvedType()

        val out = ArrayList<MediaItem>()
        for (cache in cached) {
            if (passes(
                    cache = cache,
                    rules = rules,
                    channelID = channelID,
                    isFamilySafe = isFamilySafe,
                    allows = Allows(allowsHorror, allowsReality, allowsAnimation, allowsDocumentary, allowsWar, allowsWestern, allowsTalkShow),
                    lists = Lists(
                        titleContainsLower, episodeTitleContainsLower, titleExcludesLower, studiosLower,
                        keywordsLower, keywordsExcludeLower, keywordsRequireAnyGenreLower, keywordGatedGenresLower,
                        networksLower, productionCompaniesLower, genresIncludeLower, genresExcludeLower,
                        genresRequireAllLower, editorialOverridesLower, contentRatingsSet,
                    ),
                    resolvedSource = resolvedSource,
                    resolvedType = resolvedType,
                    memberships = memberships,
                    exclusiveRules = exclusiveRules,
                    clock = clock,
                    currentYear = currentYear,
                )
            ) {
                out.add(cache.item)
            }
        }
        return out
    }

    private data class Allows(
        val horror: Boolean,
        val reality: Boolean,
        val animation: Boolean,
        val documentary: Boolean,
        val war: Boolean,
        val western: Boolean,
        val talkShow: Boolean,
    )

    private data class Lists(
        val titleContains: List<String>,
        val episodeTitleContains: List<String>,
        val titleExcludes: List<String>,
        val studios: List<String>,
        val keywords: List<String>,
        val keywordsExclude: List<String>,
        val keywordsRequireAnyGenre: List<String>,
        val keywordGatedGenres: List<String>,
        val networks: List<String>,
        val productionCompanies: List<String>,
        val genresInclude: List<String>,
        val genresExclude: List<String>,
        val genresRequireAll: List<String>,
        val editorialOverrides: List<String>,
        val contentRatings: Set<String>?,
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
    private fun passes(
        cache: FilterCache,
        rules: ChannelRules,
        channelID: Int,
        isFamilySafe: Boolean,
        allows: Allows,
        lists: Lists,
        resolvedSource: LibrarySource?,
        resolvedType: MediaType?,
        memberships: ChannelMemberships,
        exclusiveRules: List<ExclusiveRule>,
        clock: FilterClock,
        currentYear: Int,
    ): Boolean {
        val item = cache.item
        val titleLower = cache.titleLower
        val genres = cache.genresLower
        val enrichment = cache.enrichment

        val matchesEpisodeTitleRule = lists.episodeTitleContains.isNotEmpty() &&
            cache.episodeTitleLower.isNotEmpty() &&
            lists.episodeTitleContains.any { titleContainsWord(cache.episodeTitleLower, it) }

        val matchesTitleRule = lists.titleContains.isEmpty() ||
            lists.titleContains.any { titleContainsWord(titleLower, it) }

        // Library source. An explicit source restricts the pool. With none, music videos are kept out.
        val itemSource = item.librarySource ?: LibrarySource.MOVIE
        if (resolvedSource != null) {
            if (itemSource != resolvedSource) return false
        } else if (itemSource == LibrarySource.MUSIC_VIDEO) {
            return false
        }

        // Kids and family channels exclude adult ratings.
        if (isFamilySafe) {
            item.contentRating?.let { if (it in ADULT_RATINGS) return false }
        }

        // Genre locks: a genre-locked item appears only where the channel opts in.
        if (!allows.horror && genres.any { it in HORROR_GENRES }) return false
        if (!allows.reality && genres.any { it in REALITY_GENRES }) return false
        if (!allows.animation && genres.any { it in ANIMATION_GENRES }) return false
        if (!allows.documentary && genres.any { it in DOCUMENTARY_GENRES }) return false
        if (!allows.war && genres.any { it in WAR_GENRES }) return false
        if (!allows.western && genres.any { it in WESTERN_GENRES }) return false
        if (!allows.talkShow && genres.any { it in TALK_SHOW_GENRES }) return false

        // Reality channels show only reality content, in both directions.
        val isExplicitReality = channelIncludesGenre(rules, REALITY_GENRES)
        if (isExplicitReality && genres.none { it in REALITY_GENRES }) return false

        // Editorial overrides: an exact title match is included regardless of other rules.
        if (lists.editorialOverrides.isNotEmpty() && lists.editorialOverrides.any { titleLower == it }) {
            return true
        }

        // Manifest-level exclusivity: an item locked to one channel appears nowhere else.
        val tmdbID = item.tmdbID
        val mediaType = if (item.type == MediaType.EPISODE) "tv" else "movie"
        if (tmdbID != null) {
            val locked = memberships.exclusive(mediaType, tmdbID)
            if (locked != null && locked != channelID) return false
        }

        // Manifest claim for this channel: additive whitelist. Type, era, title curation,
        // title excludes and genre excludes still apply. Only genre includes are bypassed.
        if (tmdbID != null) {
            val claimed = memberships.channels(mediaType, tmdbID)
            if (claimed != null && channelID in claimed) {
                resolvedType?.let { if (item.type != it) return false }
                if (!passesYearRange(item, rules.yearRange)) return false
                if (lists.titleContains.isNotEmpty() && !matchesTitleRule) return false
                if (lists.titleExcludes.isNotEmpty() &&
                    lists.titleExcludes.any { titleContainsWord(titleLower, it) }
                ) return false
                if (lists.genresExclude.isNotEmpty() &&
                    genres.any { g -> lists.genresExclude.any { g.contains(it) } }
                ) return false
                return true
            }
        }

        // manifestOnly: membership comes only from the manifest. No genre or title fallback.
        if (rules.manifestOnly == true) return false

        if (lists.titleExcludes.isNotEmpty() && lists.titleExcludes.any { titleContainsWord(titleLower, it) }) {
            return false
        }

        // Channel exclusivity: content owned by another channel is skipped here.
        for (rule in exclusiveRules) {
            if (channelID in rule.channelIDs) continue
            if (rule.matches(item)) return false
            if (rule.manifestExclusive && tmdbID != null) {
                val claimed = memberships.channels(mediaType, tmdbID)
                if (claimed != null && claimed.any { it in rule.channelIDs }) return false
            }
        }

        resolvedType?.let { if (item.type != it) return false }

        // Content matching. Title, keyword, network and production-company rules are OR-ed.
        // Studio and genre are AND-ed when both are present, and work alone otherwise.
        val hasEpisodeTitleRule = lists.episodeTitleContains.isNotEmpty()
        val hasTitleRule = lists.titleContains.isNotEmpty()
        val hasGenreInclude = lists.genresInclude.isNotEmpty()
        val hasStudioRule = lists.studios.isNotEmpty()
        val hasKeywordRule = lists.keywords.isNotEmpty()
        val hasNetworkRule = lists.networks.isNotEmpty()
        val hasProdCoRule = lists.productionCompanies.isNotEmpty()

        // Episode-title channels and title-curated channels: the list is the allowlist.
        if (hasEpisodeTitleRule && !matchesEpisodeTitleRule) return false
        val isTitleCurated = hasTitleRule && !hasKeywordRule && !hasNetworkRule && !hasProdCoRule && !hasStudioRule
        if (isTitleCurated) {
            if (!matchesTitleRule) return false
        } else if (!hasEpisodeTitleRule &&
            (hasTitleRule || hasGenreInclude || hasStudioRule || hasKeywordRule || hasNetworkRule || hasProdCoRule)
        ) {
            val matchesTitle = hasTitleRule && matchesTitleRule

            val matchesKeyword: Boolean = run {
                if (!hasKeywordRule || enrichment == null) return@run false
                val itemKw = enrichment.keywords.map { it.lowercase() }
                if (lists.keywords.none { it in itemKw }) return@run false
                // keywordsRequireAnyGenre: a keyword only counts if the item also has one of these genres.
                if (lists.keywordsRequireAnyGenre.isNotEmpty()) {
                    return@run lists.keywordsRequireAnyGenre.any { g -> genres.any { it.contains(g) } }
                }
                true
            }

            val matchesNetwork: Boolean = run {
                if (!hasNetworkRule || enrichment == null) return@run false
                val itemNetworks = enrichment.networks.map { it.lowercase() }
                lists.networks.any { n -> itemNetworks.any { it.contains(n) } }
            }

            val matchesProdCo: Boolean = run {
                if (!hasProdCoRule || enrichment == null) return@run false
                val itemCompanies = enrichment.productionCompanies.map { it.lowercase() }
                lists.productionCompanies.any { c -> itemCompanies.any { it.contains(c) } }
            }

            val matchesGenre = hasGenreInclude && genres.any { g -> lists.genresInclude.any { g.contains(it) } }

            val matchesStudio: Boolean = run {
                if (!hasStudioRule) return@run false
                if (lists.studios.any { cache.studioLower.contains(it) }) return@run true
                if (enrichment != null) {
                    if (enrichment.productionCompanies.any { pc ->
                            val pcl = pc.lowercase(); lists.studios.any { pcl.contains(it) }
                        }
                    ) return@run true
                    if (enrichment.networks.any { n ->
                            val nl = n.lowercase(); lists.studios.any { nl.contains(it) }
                        }
                    ) return@run true
                }
                false
            }

            when {
                matchesTitle -> Unit
                matchesKeyword -> Unit
                matchesNetwork -> Unit
                matchesProdCo -> Unit
                hasStudioRule && hasGenreInclude -> if (!matchesStudio || !matchesGenre) return false
                hasStudioRule -> if (!matchesStudio) return false
                hasGenreInclude -> {
                    if (!matchesGenre) return false
                    // keywordGatedGenres: broad genres (for example Music or Drama) also need a
                    // keyword match, so unrelated content cannot pass on genre alone.
                    if (lists.keywordGatedGenres.isNotEmpty()) {
                        // Exact genre match, not substring: gating "Music" must not gate "Musical".
                        val gated = lists.keywordGatedGenres.any { it in genres }
                        if (gated && !matchesKeyword) return false
                    }
                }
                else -> return false
            }
        }

        // TMDB keyword exclude. Soft: unenriched items pass.
        if (lists.keywordsExclude.isNotEmpty() && enrichment != null) {
            val itemKw = enrichment.keywords.map { it.lowercase() }
            if (lists.keywordsExclude.any { it in itemKw }) return false
        }

        // OMDb gates. An item without OMDb data fails each of these.
        rules.imdbRatingMin?.let { min ->
            val r = enrichment?.imdbRating ?: return false
            if (r < min) return false
        }
        rules.imdbVotesMin?.let { min ->
            val v = enrichment?.imdbVotes ?: return false
            if (v < min) return false
        }
        rules.rtScoreMin?.let { min ->
            val rt = enrichment?.rottenTomatoesScore ?: return false
            if (rt < min) return false
        }
        rules.metacriticMin?.let { min ->
            val mc = enrichment?.metacriticScore ?: return false
            if (mc < min) return false
        }
        if (rules.wonOscar == true) {
            val awards = enrichment?.awards?.lowercase() ?: return false
            if (!(awards.contains("won") && awards.contains("oscar"))) return false
        }

        // Added within N days.
        rules.addedWithinDays?.takeIf { it > 0 }?.let { days ->
            val cutoff = clock.nowEpochSeconds - days.toLong() * 86400L
            if (item.addedAt < cutoff) return false
        }

        // Released within N months. Uses the full date when present, else the year.
        rules.releasedWithinMonths?.takeIf { it > 0 }?.let { months ->
            val releaseDate = parseReleaseDate(item.originallyAvailableAt)
            if (releaseDate != null) {
                val releaseInstant = releaseDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                val cutoff = clock.now.minusMonths(months.toLong()).toInstant()
                if (releaseInstant.isBefore(cutoff)) return false
            } else if (item.year != null) {
                // No full date: approximate with years. months/12, rounded up, minus one.
                val yearsBack = (months + 11) / 12 - 1
                if (item.year < currentYear - yearsBack) return false
            }
        }

        // Genre requireAll: every required genre must appear.
        if (lists.genresRequireAll.isNotEmpty()) {
            val hasAll = lists.genresRequireAll.all { m -> genres.any { it.contains(m) } }
            if (!hasAll) return false
        }

        // Genre exclude.
        if (lists.genresExclude.isNotEmpty() && genres.any { g -> lists.genresExclude.any { g.contains(it) } }) {
            return false
        }

        if (!passesYearRange(item, rules.yearRange)) return false

        // Content ratings whitelist.
        lists.contentRatings?.let { ratings ->
            if (ratings.isNotEmpty()) {
                val rating = item.contentRating ?: ""
                if (rating.isEmpty() && rules.allowUnrated == true) {
                    // allowed
                } else if (rating !in ratings) {
                    return false
                }
            }
        }

        // Minimum star rating: fails only when both the server and user rating are below it.
        rules.ratingMin?.let { min ->
            if (item.rating < min && item.userRating < min) return false
        }

        // Duration, in minutes.
        rules.durationRange?.let { dr ->
            dr.min?.let { if (item.duration < it) return false }
            dr.max?.let { if (item.duration > it) return false }
        }

        // Watch status. New releases (this year or last) count as unwatched.
        val isNewRelease = (item.year ?: 0) >= currentYear - 1
        if (rules.watchedOnly == true && item.viewCount < 1 && !isNewRelease) return false
        if (rules.unwatchedOnly == true && (item.viewCount > 0 || isNewRelease)) return false
        if (rules.rewatched == true && item.viewCount < 3) return false

        return true
    }
}
