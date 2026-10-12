package io.github.madmax3553.staticgroot.enrichment

import io.github.madmax3553.staticgroot.channels.MediaEnrichment
import io.github.madmax3553.staticgroot.jellyfin.JellyfinException
import io.github.madmax3553.staticgroot.jellyfin.JellyfinHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class TmdbRateLimited : Exception("TMDB rate limited")

/**
 * Port of tvOS TMDBService. One request per title with keywords appended.
 *
 * Quirk kept from Swift: movie keywords sit under `keywords.keywords`, TV keywords under
 * `keywords.results`. The key is in the query string, as TMDB expects, so nothing here may
 * log a URL. The shared HTTP implementation logs paths only.
 */
class TmdbClient(
    private val http: JellyfinHttp,
    private val apiKey: String,
    private val baseUrl: String = "https://api.themoviedb.org/3",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Throws [TmdbRateLimited] on HTTP 429 and [JellyfinException] on any other failure. */
    fun fetch(tmdbID: String, imdbID: String?, mediaType: String, nowEpochSeconds: Long): MediaEnrichment {
        val path = if (mediaType == "tv") "/tv/$tmdbID" else "/movie/$tmdbID"
        val body = try {
            http.get("$baseUrl$path?api_key=$apiKey&append_to_response=keywords", mapOf("Accept" to "application/json"))
        } catch (e: JellyfinException) {
            if (e.status == 429) throw TmdbRateLimited()
            throw e
        }
        return parse(body, tmdbID, imdbID, mediaType, nowEpochSeconds)
    }

    internal fun parse(body: String, tmdbID: String, imdbID: String?, mediaType: String, now: Long): MediaEnrichment {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw JellyfinException("TMDB answered with something that is not JSON")
        }
        val kwKey = if (mediaType == "tv") "results" else "keywords"
        return MediaEnrichment(
            keywords = names(root["keywords"]?.asObject()?.get(kwKey)),
            networks = names(root["networks"]),
            productionCompanies = names(root["production_companies"]),
            tmdbID = tmdbID,
            imdbID = imdbID,
            mediaType = mediaType,
            fetchedAt = now,
        )
    }

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private fun names(el: JsonElement?): List<String> =
        (el as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }.orEmpty()
}
