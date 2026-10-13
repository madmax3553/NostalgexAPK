package io.github.madmax3553.staticgroot.channels

import kotlinx.serialization.Serializable
import java.time.LocalDate

/*
 * Channels derived from what is in the library, on top of channels.json. Android only: tvOS
 * ships the fixed lineup plus collection channels. Three kinds:
 *
 *   franchise  items whose titles share a leading phrase ("Star Trek", "Toy Story")
 *   series     one big TV series on its own channel
 *   genre      a genre the library is heavy in, per type (movies or episodes)
 *
 * and one seasonal channel, built from the seasonal bundles in channels.json.
 *
 * Everything here is pure: items in, channels out. A [ChannelSpec] is the rule that makes a
 * channel, so a pool can be rebuilt each launch from the spec alone, and a channel that was once
 * worth having stays while it still has content (otherwise adding one movie to the library
 * could make a channel flicker in and out).
 */

@Serializable
data class ChannelSpec(val kind: String, val key: String, val type: String? = null) {
    val storageKey: String get() = "$kind:$key:${type.orEmpty()}"

    companion object {
        const val FRANCHISE = "franchise"
        const val SERIES = "series"
        const val GENRE = "genre"
        const val SEASONAL = "seasonal"
    }
}

/** What is kept between launches so a generated channel keeps its id, number and name. */
@Serializable
data class GeneratedRecord(val spec: ChannelSpec, val id: Int, val number: Int, val name: String, val colorHex: String)

data class GeneratedChannel(val definition: ChannelDefinition, val spec: ChannelSpec, val pool: List<MediaItem>)

/** A show or a film, with every item that belongs to it. */
class Work(val id: String, val title: String, val items: List<MediaItem>) {
    val tokens: List<String> = TitleTokens.tokens(title)
    val minutes: Int = items.sumOf { it.duration }
    val isSeries: Boolean get() = items.firstOrNull()?.type == MediaType.EPISODE
}

object TitleTokens {
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercase words, apostrophes dropped, "&" as "and", and a leading "The" removed. */
    fun tokens(title: String): List<String> {
        val parts = title.lowercase().replace("&", " and ").replace("'", "").replace("\u2019", "")
            .split(SPLIT).filter { it.isNotEmpty() }
        return if (parts.size > 1 && parts[0] == "the") parts.drop(1) else parts
    }

    /** The first [count] words of [title] as written, upper-cased, skipping a leading "The". */
    fun display(title: String, count: Int): String {
        val words = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'\u2019.&-]*").findAll(title).map { it.value.trimEnd('.', '-') }.toList()
        val body = if (words.size > 1 && words[0].equals("the", ignoreCase = true)) words.drop(1) else words
        return body.take(count).joinToString(" ").uppercase()
    }
}

object LibraryChannelPlanner {

    // Franchise rules
    private const val MIN_FRANCHISE_MINUTES = 300
    private const val MIN_FRANCHISE_ITEMS = 4
    private const val MAX_PREFIX_WORDS = 5
    // Series rules
    private const val MIN_SERIES_EPISODES = 50
    // Genre rules
    private const val MIN_GENRE_SHARE = 0.12
    private const val MIN_GENRE_MOVIE_MINUTES = 24 * 60
    private const val MIN_GENRE_EPISODES = 60
    // Shared
    private const val KEEP_MIN_ITEMS = 3
    private const val OVERLAP_SKIP = 0.8
    private const val MAX_NEW_PER_KIND = 4
    const val MAX_GENERATED = 12
    private const val FIRST_NUMBER = 300

    private val STOP = setOf("the", "a", "an", "and", "of", "in", "to", "for", "with", "on", "at", "is", "am", "are",
        "my", "me", "i", "you", "it", "we", "no", "not", "by", "from", "or", "this", "that")
    private val ROMAN = Regex("^(ii|iii|iv|v|vi|vii|viii|ix|x)$")

    /** Genres that never get a generated channel: kids and reality shelves, and seasonal horror. */
    private val SKIP_GENRES = setOf("animation", "animated", "family", "kids", "children", "documentary", "reality",
        "talk show", "talk", "news", "game show", "soap", "music", "musical", "horror", "holiday", "christmas",
        "short", "tv movie", "sport", "sports")

    private val GENRE_NAMES = mapOf(
        "science fiction" to "SCI-FI", "sci-fi" to "SCI-FI", "sci-fi & fantasy" to "SCI-FI",
        "action & adventure" to "ACTION", "romance" to "ROMANCE",
    )

    private val PALETTE = listOf("#4FC3F7", "#BA68C8", "#81C784", "#FFB74D", "#E57373", "#4DB6AC", "#F06292", "#AED581")

    // -- Works -----------------------------------------------------------------------------

    fun works(items: List<MediaItem>): List<Work> {
        val out = ArrayList<Work>()
        val shows = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in items) {
            if (item.isMusicVideo) continue
            if (item.type == MediaType.EPISODE) {
                shows.getOrPut(item.seriesId ?: "t:" + item.title.lowercase()) { ArrayList() }.add(item)
            } else out.add(Work(item.id, item.title, listOf(item)))
        }
        for ((key, eps) in shows) out.add(Work(key, eps.first().title, eps))
        return out
    }

    // -- Franchises ------------------------------------------------------------------------

    class Franchise(val prefix: List<String>, val works: List<Work>, val displayName: String) {
        val minutes: Int get() = works.sumOf { it.minutes }
        val spec: ChannelSpec get() = ChannelSpec(ChannelSpec.FRANCHISE, prefix.joinToString(" "))
    }

    private fun isNumberToken(t: String) = t.firstOrNull()?.isDigit() == true || ROMAN.matches(t) || t == "part" || t == "chapter"

    /**
     * Finds leading phrases shared by several titles. A phrase counts when it is not just filler
     * ("I Am" is not a franchise), and either has a sequel marker ("Rocky", "Rocky II") or is
     * long and common enough that a coincidence is unlikely (four titles starting "Star Trek").
     * Where phrases nest, the shorter one wins, so "Star Trek" holds every Star Trek title.
     */
    fun franchises(works: List<Work>): List<Franchise> {
        val byPrefix = HashMap<String, MutableList<Work>>()
        for (w in works) for (n in 1..minOf(w.tokens.size, MAX_PREFIX_WORDS)) {
            byPrefix.getOrPut(w.tokens.take(n).joinToString(" ")) { ArrayList() }.add(w)
        }
        val valid = HashMap<String, List<Work>>()
        for ((prefix, ws) in byPrefix) {
            val toks = prefix.split(" ")
            val n = toks.size
            if (ws.size < 2 || toks.last() in STOP || toks.none { it.length >= 4 && it !in STOP }) continue
            val residuals = ws.map { it.tokens.drop(n) }
            val hasBare = residuals.any { it.isEmpty() }
            val numbered = residuals.count { it.isNotEmpty() && isNumberToken(it.first()) }
            val marker = (hasBare && numbered >= 1) || numbered >= 2
            val ok = if (n == 1) marker && prefix.length >= 4 else marker || ws.size >= 4
            if (ok) valid[prefix] = ws
        }
        // Keep the shortest phrase of any nested pair.
        val kept = valid.keys.filter { p ->
            val toks = p.split(" ")
            (1 until toks.size).none { len -> toks.take(len).joinToString(" ") in valid }
        }
        return kept.mapNotNull { p ->
            val ws = valid.getValue(p)
            val items = ws.sumOf { it.items.size }
            val minutes = ws.sumOf { it.minutes }
            if (items < MIN_FRANCHISE_ITEMS || minutes < MIN_FRANCHISE_MINUTES) null
            else {
                val n = p.split(" ").size
                Franchise(p.split(" "), ws, TitleTokens.display(ws.maxBy { it.items.size }.title, n))
            }
        }.sortedByDescending { it.minutes }
    }

    // -- Pools from specs ------------------------------------------------------------------

    private fun hasSkippedGenre(item: MediaItem) = item.genres.any { it.lowercase() in SKIP_GENRES }

    fun poolFor(spec: ChannelSpec, items: List<MediaItem>, claimed: Set<String> = emptySet()): List<MediaItem> {
        val base = items.filter { !it.isMusicVideo }
        return when (spec.kind) {
            ChannelSpec.FRANCHISE -> {
                val prefix = spec.key.split(" ")
                base.filter { TitleTokens.tokens(it.title).take(prefix.size) == prefix }
            }
            ChannelSpec.SERIES -> base.filter { it.type == MediaType.EPISODE && it.title.lowercase() == spec.key }
            ChannelSpec.GENRE -> base.filter { item ->
                item.type.raw == spec.type && item.id !in claimed && !hasSkippedGenre(item) &&
                    item.genres.any { it.equals(spec.key, ignoreCase = true) }
            }
            else -> emptyList()
        }
    }

    // -- Planning --------------------------------------------------------------------------

    data class Plan(val channels: List<GeneratedChannel>, val records: List<GeneratedRecord>)

    /**
     * @param existing pools of the channels already in the lineup. A new channel that mostly
     *   repeats one of them is not worth adding.
     * @param previous records from earlier launches. Those channels stay while they hold at
     *   least three items, and keep their id, number and name.
     * @param usedNumbers numbers taken by the lineup, so generated channels do not collide.
     */
    fun plan(
        items: List<MediaItem>,
        existing: List<ChannelRuntime>,
        previous: List<GeneratedRecord>,
        usedNumbers: Set<Int>,
    ): Plan {
        val base = items.filter { !it.isMusicVideo }
        val existingSets = existing.map { r -> r.unrestrictedPool.mapTo(HashSet()) { it.id } }
        val accepted = ArrayList<Pair<ChannelSpec, List<MediaItem>>>()
        val acceptedSets = ArrayList<Set<String>>()
        val claimed = HashSet<String>()
        val prevByKey = previous.associateBy { it.spec.storageKey }

        fun duplicatesSomething(pool: List<MediaItem>): Boolean {
            if (pool.isEmpty()) return true
            val ids = pool.mapTo(HashSet()) { it.id }
            return (existingSets + acceptedSets).any { other -> other.isNotEmpty() && ids.count { it in other } >= OVERLAP_SKIP * ids.size }
        }

        fun take(spec: ChannelSpec, pool: List<MediaItem>) {
            accepted.add(spec to pool)
            acceptedSets.add(pool.mapTo(HashSet()) { it.id })
            if (spec.kind != ChannelSpec.GENRE) claimed.addAll(pool.map { it.id })
        }

        // 1. Channels from earlier launches stay while they still have content. Franchises and
        //    series first, so their items are claimed before genre pools are cut.
        val ordered = previous.sortedBy { if (it.spec.kind == ChannelSpec.GENRE) 1 else 0 }
        for (rec in ordered) {
            val pool = poolFor(rec.spec, base, claimed)
            if (pool.size >= KEEP_MIN_ITEMS) take(rec.spec, pool)
        }
        val keptKeys = accepted.map { it.first.storageKey }.toSet()
        fun room() = accepted.size < MAX_GENERATED

        // 2. New franchises and big series.
        val works = works(base)
        val franchises = franchises(works)
        for (f in franchises.filter { it.spec.storageKey !in keptKeys }.take(MAX_NEW_PER_KIND)) {
            if (!room()) break
            val pool = poolFor(f.spec, base)
            if (!duplicatesSomething(pool)) take(f.spec, pool)
        }
        val inFranchise = franchises.flatMap { f -> f.works.map { it.id } }.toSet()
        val bigShows = works.filter { it.isSeries && it.id !in inFranchise && it.items.size >= MIN_SERIES_EPISODES }
            .sortedByDescending { it.minutes }
        for (w in bigShows.map { ChannelSpec(ChannelSpec.SERIES, it.title.lowercase()) }
            .filter { it.storageKey !in keptKeys }.take(MAX_NEW_PER_KIND)) {
            if (!room()) break
            val pool = poolFor(w, base)
            if (!duplicatesSomething(pool)) take(w, pool)
        }

        // 3. New genre channels, with franchise and series items taken out first.
        val movies = base.count { it.type == MediaType.MOVIE }
        val episodes = base.count { it.type == MediaType.EPISODE }
        val counts = HashMap<Pair<String, String>, Int>()
        for (item in base) for (g in item.genres.map { it.lowercase() }.toSet()) {
            if (g !in SKIP_GENRES) counts.merge(g to item.type.raw, 1, Int::plus)
        }
        val genreSpecs = counts.entries.mapNotNull { (k, count) ->
            val (genre, type) = k
            val typeTotal = if (type == MediaType.MOVIE.raw) movies else episodes
            if (typeTotal == 0 || count.toDouble() / typeTotal < MIN_GENRE_SHARE) return@mapNotNull null
            val name = base.firstNotNullOf { it.genres.firstOrNull { x -> x.equals(genre, ignoreCase = true) } }
            ChannelSpec(ChannelSpec.GENRE, name, type)
        }.filter { it.storageKey !in keptKeys }
        val sized = genreSpecs.map { it to poolFor(it, base, claimed) }.filter { (spec, pool) ->
            if (spec.type == MediaType.MOVIE.raw) pool.sumOf { it.duration } >= MIN_GENRE_MOVIE_MINUTES
            else pool.size >= MIN_GENRE_EPISODES
        }.sortedByDescending { (_, pool) -> pool.sumOf { it.duration } }
        for ((spec, pool) in sized.take(MAX_NEW_PER_KIND)) {
            if (!room()) break
            if (!duplicatesSomething(pool)) take(spec, pool)
        }

        // 4. Ids, numbers, names.
        val takenIds = HashSet<Int>(previous.map { it.id })
        val takenNumbers = HashSet(usedNumbers)
        val records = ArrayList<GeneratedRecord>()
        val channels = ArrayList<GeneratedChannel>()
        for ((spec, pool) in accepted) {
            val known = prevByKey[spec.storageKey]
            val fresh = known ?: stableId(spec, takenIds).let { id ->
                takenIds.add(id)
                GeneratedRecord(spec, id, freeNumber(takenNumbers), nameFor(spec, works), PALETTE[Math.floorMod(id, PALETTE.size)])
            }
            // A kept channel whose number a static channel has since taken moves to a free one.
            val rec = if (fresh.number in takenNumbers) fresh.copy(number = freeNumber(takenNumbers)) else fresh
            takenNumbers.add(rec.number)
            records.add(rec)
            channels.add(GeneratedChannel(definitionOf(rec), spec, pool))
        }
        return Plan(channels, records)
    }

    private fun freeNumber(taken: Set<Int>): Int { var n = FIRST_NUMBER; while (n in taken) n++; return n }

    fun definitionOf(rec: GeneratedRecord) = ChannelDefinition(
        id = rec.id, number = rec.number, name = rec.name, colorHex = rec.colorHex,
        category = "generated", minItems = 1,
    )

    private fun nameFor(spec: ChannelSpec, works: List<Work>): String = when (spec.kind) {
        ChannelSpec.FRANCHISE -> {
            val prefix = spec.key.split(" ")
            TitleTokens.display(works.first { it.tokens.take(prefix.size) == prefix }.title, prefix.size)
        }
        ChannelSpec.SERIES -> works.first { it.isSeries && it.title.lowercase() == spec.key }.title.uppercase()
        else -> {
            val base = GENRE_NAMES[spec.key.lowercase()] ?: spec.key.uppercase()
            if (spec.type == MediaType.EPISODE.raw) "$base TV" else "$base FILMS"
        }
    }

    /** The same spec always gets the same id on every device. Collisions probe upward. */
    fun stableId(spec: ChannelSpec, taken: Set<Int>): Int {
        var h = 2166136261L
        for (c in spec.storageKey) { h = (h xor c.code.toLong()) * 16777619L and 0xFFFFFFFFL }
        var id = DYNAMIC_CHANNEL_ID_BASE + (h % 900_000L).toInt()
        while (id in taken) id++
        return id
    }
}

/** Seasonal bundles in channels.json: out of season they vanish, in season they are one channel. */
object SeasonalChannels {

    /** Port of ChannelBundle.isInSeason: September counts only from the 15th. */
    fun isInSeason(activeMonths: List<Int>?, date: LocalDate): Boolean {
        if (activeMonths == null) return true
        if (date.monthValue !in activeMonths) return false
        return date.monthValue != 9 || date.dayOfMonth >= 15
    }

    data class Result(val remaining: List<ChannelRuntime>, val seasonal: List<GeneratedChannel>)

    /**
     * Takes the channels of every seasonal bundle out of the lineup. For each bundle in season,
     * adds one channel holding all of their items, numbered where the first of them sat. A small
     * pool is fine: the scheduler loops a pool and spaces repeats, so a seasonal channel simply
     * repeats more than a big one.
     */
    fun apply(config: ChannelConfig, runtimes: List<ChannelRuntime>, date: LocalDate): Result {
        val seasonalBundles = config.bundles.filter { it.activeMonths != null }
        val seasonalIds = seasonalBundles.flatMap { it.channelIDs }.toSet()
        val made = ArrayList<GeneratedChannel>()
        for (b in seasonalBundles) {
            if (!isInSeason(b.activeMonths, date)) continue
            val members = runtimes.filter { it.id in b.channelIDs }
            val pool = members.flatMap { it.unrestrictedPool }.distinctBy { it.id }
            if (members.isEmpty() || pool.isEmpty()) continue
            val spec = ChannelSpec(ChannelSpec.SEASONAL, b.id)
            val def = ChannelDefinition(
                id = LibraryChannelPlanner.stableId(spec, emptySet()),
                number = members.minOf { it.number },
                name = b.name,
                colorHex = members.first().definition.colorHex,
                category = "seasonal",
                minItems = 1,
            )
            made.add(GeneratedChannel(def, spec, pool))
        }
        return Result(runtimes.filter { it.id !in seasonalIds }, made)
    }
}
