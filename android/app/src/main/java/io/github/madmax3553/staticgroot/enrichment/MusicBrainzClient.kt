package io.github.madmax3553.staticgroot.enrichment

import io.github.madmax3553.staticgroot.jellyfin.JellyfinException
import io.github.madmax3553.staticgroot.jellyfin.JellyfinHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder

data class RecordingMatch(
    val mbid: String,
    val recordingTitle: String,
    val artist: String?,
    val genres: List<String>,
    val releaseYear: Int?,
)

/**
 * Port of tvOS MusicBrainzService: recording search, then a lookup for tags and credits. Free
 * API, one request a second ([throttle]), and a User-Agent naming the app is required.
 */
class MusicBrainzClient(
    private val http: JellyfinHttp,
    private val userAgent: String,
    private val throttle: Throttle = Throttle(1_050),
    private val baseUrl: String = "https://musicbrainz.org/ws/2",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private class Hit(val mbid: String, val title: String, val artist: String?, val year: Int?)

    /** Null when MusicBrainz has no match. Throws [JellyfinException] when it could not be asked. */
    fun resolveRecording(title: String, artist: String?, year: Int?): RecordingMatch? {
        val song = title.replace(Regex("""\s\(\d{4}\)$"""), "").trim()
        if (song.isEmpty()) return null
        val hit = search(song, artist, year) ?: return null
        return lookup(hit.mbid) ?: RecordingMatch(hit.mbid, hit.title, hit.artist, emptyList(), hit.year)
    }

    private fun search(song: String, artist: String?, year: Int?): Hit? {
        val parts = arrayListOf("recording:\"${song.replace("\"", "")}\"", "video:true")
        if (!artist.isNullOrEmpty()) parts.add("artist:\"${artist.replace("\"", "")}\"")
        val query = URLEncoder.encode(parts.joinToString(" AND "), "UTF-8")
        val root = get("$baseUrl/recording?query=$query&fmt=json&limit=10")
        val recordings = (root["recordings"] as? JsonArray) ?: return null

        val songLower = song.lowercase()
        var best: Hit? = null
        var bestRank = Int.MIN_VALUE
        for (el in recordings) {
            val rec = el as? JsonObject ?: continue
            val mbid = str(rec["id"]) ?: continue
            val title = str(rec["title"]) ?: continue
            val score = (rec["score"] as? JsonPrimitive)?.intOrNull ?: 0
            val artistName = primaryArtist(rec)
            val recYear = firstReleaseYear(rec)

            var rank = score
            val t = title.lowercase()
            if (t == songLower) rank += 40 else if (t.contains(songLower) || songLower.contains(t)) rank += 20
            if (artist != null && artistName != null && artistName.lowercase().contains(artist.lowercase())) rank += 25
            if (year != null && recYear != null) {
                if (year == recYear) rank += 15 else if (kotlin.math.abs(year - recYear) <= 1) rank += 8
            }
            if (best == null || rank > bestRank) { best = Hit(mbid, title, artistName, recYear); bestRank = rank }
        }
        return best
    }

    private fun lookup(mbid: String): RecordingMatch? {
        val root = get("$baseUrl/recording/$mbid?inc=artist-credits+tags+releases&fmt=json")
        val title = str(root["title"]) ?: return null
        val tags = (root["tags"] as? JsonArray).orEmpty().mapNotNull { str((it as? JsonObject)?.get("name")) }
        return RecordingMatch(mbid, title, primaryArtist(root), mapTagsToChannelGenres(tags), firstReleaseYear(root))
    }

    private fun get(url: String): JsonObject {
        throttle.await()
        val body = http.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
        return try { json.parseToJsonElement(body).jsonObject } catch (e: Exception) {
            throw JellyfinException("MusicBrainz answered with something that is not JSON")
        }
    }

    private fun str(el: JsonElement?): String? = (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun primaryArtist(o: JsonObject): String? {
        for (c in (o["artist-credit"] as? JsonArray).orEmpty()) {
            val credit = c as? JsonObject ?: continue
            str(credit["name"])?.let { return it }
            str((credit["artist"] as? JsonObject)?.get("name"))?.let { return it }
        }
        return null
    }

    private fun firstReleaseYear(o: JsonObject): Int? {
        for (r in (o["releases"] as? JsonArray).orEmpty()) {
            val date = str((r as? JsonObject)?.get("date")) ?: continue
            if (date.length >= 4) date.take(4).toIntOrNull()?.let { return it }
        }
        return null
    }

    companion object {
        /** MusicBrainz tag vocabulary to the genres channels.json rules use. */
        fun mapTagsToChannelGenres(tags: List<String>): List<String> {
            val out = ArrayList<String>()
            fun add(g: String) { if (out.none { it.equals(g, ignoreCase = true) }) out.add(g) }
            for (tag in tags) {
                val t = tag.lowercase()
                if (t.contains("pop") && !t.contains("k-pop")) add("Pop")
                if (t.contains("rock")) {
                    if (t.contains("alternative") || t.contains("indie")) { add("Alternative Rock"); add("Indie Rock") }
                    else if (t.contains("hard rock") || t.contains("hard-rock")) add("Hard Rock")
                    else if (t.contains("classic rock")) add("Classic Rock")
                    else add("Rock")
                }
                if (t.contains("country")) { add("Country"); if (t.contains("rock")) add("Country Rock") }
                if (t.contains("hip hop") || t.contains("hip-hop")) { add("Hip-Hop"); add("Hip Hop") }
                if (t == "rap" || t.contains("gangsta")) add("Rap")
                if (t.contains("r&b") || t.contains("rhythm and blues") || t == "soul") { add("R&B"); add("Soul") }
                if (t.contains("acoustic")) add("Acoustic")
                if (t.contains("electronic") || t.contains("dance") || t.contains("edm") ||
                    t.contains("house") || t.contains("techno") || t.contains("trance")) add("Dance")
                if (t.contains("metal")) { add("Metal"); add("Hard Rock"); add("Rock") }
                if (t.contains("punk")) { add("Rock"); add("Alternative") }
                if (t.contains("folk")) add("Acoustic")
                if (t.contains("latin") || t.contains("reggaeton") || t.contains("reggaet\u00F3n")) add("Latin")
                if (t.contains("funk")) add("Funk")
                if (t.contains("disco")) add("Disco")
            }
            return out
        }
    }
}
