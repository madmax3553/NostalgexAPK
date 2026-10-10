package io.github.madmax3553.staticgroot.channels

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Port of tvOS Nostalgex/Models/Channel.swift (JSON config section) and
 * ChannelBundle.swift. The source of truth is channels.json at the repo root.
 * Field names match that file so the Swift and Kotlin readers stay comparable.
 *
 * Values are kept as the file spells them (strings for type and source). The
 * Swift code converts them to enums; that conversion belongs with the filter port.
 */

/** Static channel ids stay below this. Runtime (collection) ids live above it. Mirrors ChannelIDSpace. */
const val DYNAMIC_CHANNEL_ID_BASE = 1_000_000

@Serializable
data class ChannelConfigFile(
    val version: Int,
    val exclusiveRules: List<ExclusiveRuleJson>? = null,
    val bundles: List<BundleDefinition>? = null,
    val channels: List<ChannelDefinition>,
)

@Serializable
data class ChannelDefinition(
    val id: Int,
    val number: Int,
    val name: String,
    val colorHex: String,
    val category: String? = null,
    val minItems: Int? = null,
    val isPremiereChannel: Boolean? = null,
    val rules: ChannelRules? = null,
    val timeRestrictions: TimeRestrictions? = null,
) {
    val effectiveMinItems: Int get() = minItems ?: 50
    val premiere: Boolean get() = isPremiereChannel ?: false
}

@Serializable
data class ChannelRules(
    val type: String? = null,
    val source: String? = null,
    val genres: GenreRules? = null,
    val studios: List<String>? = null,
    val yearRange: YearRange? = null,
    val contentRatings: List<String>? = null,
    val allowUnrated: Boolean? = null,
    val ratingMin: Double? = null,
    val durationRange: DurationRange? = null,
    val watchedOnly: Boolean? = null,
    val unwatchedOnly: Boolean? = null,
    val rewatched: Boolean? = null,
    val titleContains: List<String>? = null,
    val episodeTitleContains: List<String>? = null,
    val titleExcludes: List<String>? = null,
    val editorialOverrides: List<String>? = null,
    val addedWithinDays: Int? = null,
    val releasedWithinMonths: Int? = null,
    // TMDB enrichment rules
    val keywords: List<String>? = null,
    val keywordsRequireAll: List<String>? = null,
    val keywordsExclude: List<String>? = null,
    val keywordsRequireAnyGenre: List<String>? = null,
    val keywordGatedGenres: List<String>? = null,
    val networks: List<String>? = null,
    val productionCompanies: List<String>? = null,
    // OMDb enrichment rules
    val imdbRatingMin: Double? = null,
    val imdbVotesMin: Int? = null,
    val rtScoreMin: Int? = null,
    val metacriticMin: Int? = null,
    val wonOscar: Boolean? = null,
    val manifestOnly: Boolean? = null,
)

@Serializable
data class GenreRules(
    val include: List<String>? = null,
    val requireAll: List<String>? = null,
    val exclude: List<String>? = null,
)

@Serializable
data class YearRange(val min: Int? = null, val max: Int? = null)

@Serializable
data class DurationRange(val min: Int? = null, val max: Int? = null)

@Serializable
data class TimeRestrictions(
    val blockRatings: BlockRatings? = null,
    val tvOnlyBefore: Int? = null,
    val onlyAfterHour: Int? = null,
)

@Serializable
data class BlockRatings(
    val ratings: List<String>,
    val blockBefore: Int? = null,
    val blockAfter: Int? = null,
)

/**
 * Older configs and snapshots use a single `channelID`; newer ones use `channelIDs`.
 * At least one is present.
 */
@Serializable
data class ExclusiveRuleJson(
    val channelID: Int? = null,
    val channelIDs: List<Int>? = null,
    val genres: List<String>? = null,
    val titleContains: List<String>? = null,
    val editorialTitles: List<String>? = null,
    val manifestExclusive: Boolean? = null,
) {
    fun toExclusiveRule(): ExclusiveRule = ExclusiveRule(
        channelIDs = channelIDs ?: channelID?.let { listOf(it) } ?: emptyList(),
        genres = genres ?: emptyList(),
        titleContains = titleContains ?: emptyList(),
        editorialTitles = editorialTitles ?: emptyList(),
        manifestExclusive = manifestExclusive ?: false,
    )
}

data class ExclusiveRule(
    val channelIDs: List<Int>,
    val genres: List<String>,
    val titleContains: List<String>,
    val editorialTitles: List<String>,
    val manifestExclusive: Boolean,
)

@Serializable
data class BundleDefinition(
    val id: String,
    val name: String,
    val description: String? = null,
    val channelIDs: List<Int> = emptyList(),
    /** Months (1-12) the bundle is shown. Null means always visible. */
    val activeMonths: List<Int>? = null,
)

/** The parsed result, the same shape as Swift's ChannelConfigResult. */
data class ChannelConfig(
    val version: Int,
    val channels: List<ChannelDefinition>,
    val bundles: List<BundleDefinition>,
    val exclusiveRules: List<ExclusiveRule>,
)

object ChannelConfigLoader {
    // Unknown keys are ignored so newer channels.json files do not break an older APK.
    private val json = Json { ignoreUnknownKeys = true }

    /** Parse channels.json text. Throws on malformed JSON, like Swift's loader returning nil. */
    fun parse(text: String): ChannelConfig {
        val file = json.decodeFromString(ChannelConfigFile.serializer(), text)
        return ChannelConfig(
            version = file.version,
            channels = file.channels,
            bundles = file.bundles ?: emptyList(),
            exclusiveRules = (file.exclusiveRules ?: emptyList()).map { it.toExclusiveRule() },
        )
    }
}
