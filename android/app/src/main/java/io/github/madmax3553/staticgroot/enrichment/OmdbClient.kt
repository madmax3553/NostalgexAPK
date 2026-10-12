package io.github.madmax3553.staticgroot.enrichment

import io.github.madmax3553.staticgroot.channels.OmdbResult
import io.github.madmax3553.staticgroot.jellyfin.JellyfinException
import io.github.madmax3553.staticgroot.jellyfin.JellyfinHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The daily quota is spent. Stops OMDb for the rest of the session. */
class OmdbRateLimited : Exception("OMDb rate limited")

/** The IMDb id is not in OMDb. Not an error for the session. */
class OmdbNotFound : Exception("OMDb has no such title")

/**
 * Port of tvOS OMDBService. OMDb reports most failures as HTTP 200 with
 * `{"Response":"False","Error":"..."}`, so the error text decides the outcome, the same
 * substring tests tvOS uses.
 */
class OmdbClient(
    private val http: JellyfinHttp,
    private val apiKey: String,
    private val baseUrl: String = "https://www.omdbapi.com/",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Throws [OmdbRateLimited], [OmdbNotFound], or [JellyfinException] (with the HTTP status). */
    fun fetch(imdbID: String): OmdbResult {
        val body = http.get("$baseUrl?i=$imdbID&apikey=$apiKey&tomatoes=true", mapOf("Accept" to "application/json"))
        return parse(body, imdbID)
    }

    internal fun parse(body: String, imdbID: String): OmdbResult {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw JellyfinException("OMDb answered with something that is not JSON")
        }
        if (str(root["Response"]) == "False") {
            val err = str(root["Error"]).orEmpty().lowercase()
            if (err.contains("limit") || err.contains("reached")) throw OmdbRateLimited()
            if (err.contains("not found") || err.contains("incorrect imdb")) throw OmdbNotFound()
            throw JellyfinException("OMDb error")
        }
        return OmdbResult(
            imdbID = imdbID,
            imdbRating = str(root["imdbRating"])?.toDoubleOrNull(),
            imdbVotes = str(root["imdbVotes"])?.replace(",", "")?.toIntOrNull(),
            rottenTomatoesScore = rottenTomatoes(root["Ratings"]),
            metacriticScore = str(root["Metascore"])?.toIntOrNull(),
            awards = str(root["Awards"]),
        )
    }

    /** A string field, with OMDb's "N/A" placeholder read as absent. */
    private fun str(el: JsonElement?): String? =
        ((el as? JsonPrimitive)?.contentOrNull)?.takeIf { it != "N/A" }

    private fun rottenTomatoes(el: JsonElement?): Int? {
        for (r in (el as? JsonArray).orEmpty()) {
            val o = r as? JsonObject ?: continue
            if (str(o["Source"]) == "Rotten Tomatoes") {
                return str(o["Value"])?.replace("%", "")?.toIntOrNull()
            }
        }
        return null
    }
}
