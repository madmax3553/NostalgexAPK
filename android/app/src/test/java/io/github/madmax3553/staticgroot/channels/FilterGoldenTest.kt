package io.github.madmax3553.staticgroot.channels

import java.io.File
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Cross-check against the web filter. filter-golden.json is written by
 * scripts/nostalgex-filter-fixture.cjs, which runs scripts/nostalgex-channel-filter.cjs over
 * synthetic items, the shared channels.json and the shared membership manifest. This test runs
 * the Kotlin RuleFilter on the same inputs and expects the same kept ids for every channel.
 *
 * The web filter is a runnable reference, not the tvOS spec. A mismatch means one side has a
 * bug, or the two have drifted. Check the Swift source before changing either side.
 */
class FilterGoldenTest {

    private val fixture: JsonObject by lazy {
        val file = File("src/test/resources/filter-golden.json")
        check(file.exists()) { "missing ${file.absolutePath}" }
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.int
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.long
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.contentOrNull?.toBoolean()

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun toItem(o: JsonObject): MediaItem {
        val type = if (o.str("type") == "episode") MediaType.EPISODE else MediaType.MOVIE
        return MediaItem(
            id = o.str("ratingKey")!!,
            title = o.str("title").orEmpty(),
            type = type,
            genres = o.stringList("genres"),
            episodeTitle = o.str("episodeTitle"),
            year = o.int("year"),
            originallyAvailableAt = o.str("originallyAvailableAt"),
            contentRating = o.str("contentRating"),
            duration = o.int("duration") ?: 0,
            rating = (o["rating"] as? JsonPrimitive)?.contentOrNull?.toDouble() ?: 0.0,
            userRating = (o["userRating"] as? JsonPrimitive)?.contentOrNull?.toDouble() ?: 0.0,
            viewCount = o.int("viewCount") ?: 0,
            addedAt = o.long("addedAt") ?: 0L,
            studio = o.str("studio"),
            tmdbID = o.str("tmdbID"),
            librarySource = LibrarySource.fromRaw(o.str("librarySource")),
        )
    }

    @Test
    fun everyChannelKeepsTheSameItemsAsTheWebFilter() {
        val items = fixture["items"]!!.jsonArray.map { toItem(it.jsonObject) }
        val config = ChannelConfigLoader.parse(File("../../channels.json").readText())
        val memberships = ChannelMemberships.parse(File("../../channels-memberships.json").readText())
        val clock = FilterClock(nowEpochSeconds = fixture.long("nowSec")!!, zone = ZoneOffset.UTC)
        val cached = items.map { buildFilterCache(it, enrichment = null) }
        // Same path the app takes: prepare once, then filter every channel.
        val prepared = RuleFilter.prepare(cached, memberships, config.exclusiveRules)

        val expected = fixture["channels"]!!.jsonObject
        assertEquals("channel count", config.channels.size, expected.size)

        val mismatches = ArrayList<String>()
        for (ch in config.channels) {
            val got = RuleFilter.filterPrepared(
                pool = prepared,
                rules = ch.rules,
                channelID = ch.id,
                category = ch.category,
                clock = clock,
            ).map { it.id }
            val want = expected[ch.id.toString()]!!.jsonArray.map { it.jsonPrimitive.content }
            if (got != want) {
                mismatches.add("CH ${ch.id} ${ch.name}: web kept ${want.size}, kotlin kept ${got.size}")
            }
        }
        assertEquals("channels that differ:\n" + mismatches.joinToString("\n"), emptyList<String>(), mismatches)
    }
}
