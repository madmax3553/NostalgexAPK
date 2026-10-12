package io.github.madmax3553.staticgroot.enrichment

import io.github.madmax3553.staticgroot.jellyfin.JellyfinException
import io.github.madmax3553.staticgroot.jellyfin.JellyfinHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder

data class TrackMatch(
    val trackID: Long,
    val albumID: Long,
    val title: String,
    val artist: String,
    val genres: List<String>,
    val releaseYear: Int?,
)

/**
 * Port of tvOS DeezerService: track search, then one album lookup for genres. Free, no key.
 * A second source after MusicBrainz, whose tags are sparse. Genres are the album's.
 */
class DeezerClient(
    private val http: JellyfinHttp,
    private val userAgent: String,
    private val throttle: Throttle = Throttle(250),
    private val baseUrl: String = "https://api.deezer.com",
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class SearchHit(val trackID: Long, val albumID: Long, val title: String, val artist: String)

    /**
     * Searches with the artist first, then the bare song when that finds nothing, so a wrong
     * artist hint does not cost the lookup. Null for no match; throws [JellyfinException] on failure.
     */
    fun resolve(song: String, artist: String?): TrackMatch? {
        val s = song.trim()
        if (s.isEmpty()) return null
        val queries = ArrayList<String>()
        if (!artist.isNullOrEmpty()) queries.add("$artist $s")
        queries.add(s)
        for (q in queries) {
            val pick = bestHit(search(q), artist) ?: continue
            val (genres, year) = album(pick.albumID)
            return TrackMatch(pick.trackID, pick.albumID, pick.title, pick.artist, mapGenres(genres), year)
        }
        return null
    }

    private fun search(query: String): List<SearchHit> {
        val root = get("$baseUrl/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=5")
        return (root["data"] as? JsonArray).orEmpty().mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            val id = num(row["id"]) ?: return@mapNotNull null
            val title = text(row["title"]) ?: return@mapNotNull null
            val artist = text((row["artist"] as? JsonObject)?.get("name")) ?: return@mapNotNull null
            val albumId = num((row["album"] as? JsonObject)?.get("id")) ?: return@mapNotNull null
            SearchHit(id, albumId, title, artist)
        }
    }

    private fun album(id: Long): Pair<List<String>, Int?> {
        val root = get("$baseUrl/album/$id")
        val genres = ((root["genres"] as? JsonObject)?.get("data") as? JsonArray).orEmpty()
            .mapNotNull { text((it as? JsonObject)?.get("name")) }
        val year = text(root["release_date"])?.take(4)?.toIntOrNull()
        return genres to year
    }

    private fun get(url: String): JsonObject {
        throttle.await()
        val body = http.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
        return try { json.parseToJsonElement(body).jsonObject } catch (e: Exception) {
            throw JellyfinException("Deezer answered with something that is not JSON")
        }
    }

    private fun num(el: kotlinx.serialization.json.JsonElement?) = (el as? JsonPrimitive)?.longOrNull
    private fun text(el: kotlinx.serialization.json.JsonElement?) = (el as? JsonPrimitive)?.contentOrNull

    companion object {
        /**
         * Deezer ranks by popularity. When the artist is known, prefer the first hit sharing a
         * word with it, so a cover does not beat the original.
         */
        fun bestHit(hits: List<SearchHit>, artistHint: String?): SearchHit? {
            val first = hits.firstOrNull() ?: return null
            val hint = artistHint?.lowercase().orEmpty()
            if (hint.isEmpty()) return first
            val hintWords = words(hint).filter { it.length > 2 }.toSet()
            if (hintWords.isEmpty()) return first
            return hits.firstOrNull { h -> words(h.artist.lowercase()).any { it in hintWords } } ?: first
        }

        private fun words(s: String) = s.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

        /** Deezer album genres to the names channels.json rules match on. Films/Games and Kids stay unmapped. */
        fun mapGenres(raw: List<String>): List<String> {
            val out = ArrayList<String>()
            fun add(vararg names: String) { for (g in names) if (out.none { it.equals(g, ignoreCase = true) }) out.add(g) }
            for (name in raw) {
                when (val g = name.lowercase()) {
                    "pop", "international pop", "indie pop", "k-pop", "j-pop", "latin pop" -> {
                        add("Pop")
                        if (g == "indie pop") add("Alternative")
                        if (g == "latin pop") add("Latin")
                    }
                    "rock" -> add("Rock")
                    "hard rock" -> add("Hard Rock", "Rock")
                    "classic rock" -> add("Classic Rock", "Rock")
                    "indie rock", "indie rock/rock pop" -> add("Indie Rock", "Alternative", "Rock")
                    "alternative" -> add("Alternative")
                    "punk" -> add("Punk", "Rock", "Alternative")
                    "metal" -> add("Metal", "Hard Rock", "Rock")
                    "rap/hip hop" -> add("Hip-Hop", "Hip Hop", "Rap")
                    "r&b" -> add("R&B")
                    "soul & funk" -> add("Soul", "Funk")
                    "disco" -> add("Disco", "Funk")
                    "dance", "dancefloor" -> add("Dance", "Electronic")
                    "electro", "dubstep", "chill out/trip-hop/lounge", "electro pop/electro rock" -> {
                        add("Electronic", "Dance")
                        if (g == "electro pop/electro rock") add("Pop")
                    }
                    "techno/house" -> add("House", "Techno", "Electronic", "Dance")
                    "country" -> add("Country")
                    "latin music", "brazilian music" -> add("Latin")
                    "reggaeton" -> add("Reggaeton", "Latin")
                    "reggae" -> add("Reggae")
                    "folk" -> add("Acoustic", "Folk")
                    "jazz" -> add("Jazz")
                    "blues" -> add("Blues")
                }
            }
            return out
        }
    }
}
