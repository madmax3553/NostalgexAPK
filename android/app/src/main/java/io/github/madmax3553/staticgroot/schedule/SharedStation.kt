package io.github.madmax3553.staticgroot.schedule

import android.content.Context
import io.github.madmax3553.staticgroot.channels.ChannelDefinition
import io.github.madmax3553.staticgroot.channels.ChannelRuntime
import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import io.github.madmax3553.staticgroot.jellyfin.HttpUrlJellyfinHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.net.URLEncoder
import io.github.madmax3553.staticgroot.jellyfin.LearnedPlayback
import io.github.madmax3553.staticgroot.jellyfin.PlaybackObservation

/** Companion URL and key are device-local, not bundled in the APK. */
class CompanionSettings(context: Context) {
    private val prefs = io.github.madmax3553.staticgroot.SecurePrefs.open(context, "staticgroot_companion")
    val url get() = prefs.getString("url", "").orEmpty()
    val key get() = prefs.getString("key", "").orEmpty()
    fun save(url: String, key: String) {
        require(url.isBlank() || (url.startsWith("http://") || url.startsWith("https://")))
        require(url.isBlank() || key.length >= 24) { "The service key must have at least 24 characters" }
        prefs.edit().putString("url", url.trim().trimEnd('/')).putString("key", key).apply()
    }
}

@Serializable data class SharedChannel(val id: Int, val name: String, val enabled: Boolean = true,
    val definition: ChannelDefinition)
@Serializable data class StationSetup(val version: Int = 1, val timeZone: String,
    val channels: List<SharedChannel>, val generation: Int = 0)
@Serializable data class StoredBlock(val itemId: String, val startUnix: Long, val endUnix: Long,
    val isBumper: Boolean, val mediaOffsetSeconds: Long, val isMidBreak: Boolean,
    val movieIdentity: String? = null)
@Serializable data class SharedDay(val blocks: List<StoredBlock>, val generation: Int = 0)
@Serializable data class RegenerationDay(val channelId: Int, val dayKey: String, val blocks: List<StoredBlock>)
@Serializable data class Regeneration(val generation: Int, val days: List<RegenerationDay>)
@Serializable data class Selection(val channelId: Int? = null)
@Serializable data class CatalogItem(val id: String, val title: String, val type: String, val seriesId: String?)
@Serializable data class Catalog(val items: List<CatalogItem>)

class SharedStation(private val base: String, key: String, stationId: String) {
    private val http = HttpUrlJellyfinHttp()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val headers = mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
    private val root = "$base/v1/stations/$stationId"
    private var generation = 0
    fun publishCatalog(items: List<MediaItem>) {
        http.post("$root/catalog", headers, json.encodeToString(Catalog.serializer(),
            Catalog(items.map { CatalogItem(it.id, it.title, it.type.raw, it.seriesId) })))
    }

    fun setup(definitions: List<ChannelDefinition>, zone: String): StationSetup = json.decodeFromString(
        StationSetup.serializer(), http.post("$root/setup", headers, json.encodeToString(
            StationSetup.serializer(), StationSetup(timeZone = zone,
                channels = definitions.map { SharedChannel(it.id, it.name, definition = it) })))).also { generation = it.generation }

    fun publish(ch: ChannelRuntime, dayKey: String, blocks: List<Block>, items: Map<String, MediaItem>): List<Block> {
        val proposal = SharedDay(storedBlocks(blocks), generation)
        val saved = json.decodeFromString(SharedDay.serializer(), http.post(
            "$root/days/${ch.id}/$dayKey", headers, json.encodeToString(SharedDay.serializer(), proposal)))
        return resolve(saved, items)
    }

    fun existing(ch: ChannelRuntime, dayKey: String, items: Map<String, MediaItem>): List<Block>? = try {
        resolve(json.decodeFromString(SharedDay.serializer(), http.get("$root/days/${ch.id}/$dayKey", headers)), items)
    } catch (e: io.github.madmax3553.staticgroot.jellyfin.JellyfinException) {
        if (e.status == 404) null else throw e
    }

    private fun resolve(saved: SharedDay, items: Map<String, MediaItem>): List<Block> = saved.blocks.map {
            val item = items[it.itemId] ?: throw IllegalStateException(
                "A published programme is unavailable to this Jellyfin user. Use the same library permissions on both devices.")
            Block(item, it.startUnix, it.endUnix, it.isBumper, it.mediaOffsetSeconds, it.isMidBreak)
        }

    fun regenerate(days: List<Triple<Int, String, List<Block>>>) {
        val request = Regeneration(generation, days.map { (channel, day, blocks) ->
            RegenerationDay(channel, day, storedBlocks(blocks))
        })
        val saved = json.decodeFromString(StationSetup.serializer(), http.post("$root/regenerate", headers,
            json.encodeToString(Regeneration.serializer(), request)))
        generation = saved.generation
    }

    private fun storedBlocks(blocks: List<Block>): List<StoredBlock> = blocks.map {
            StoredBlock(it.item.id, it.startUnix, it.endUnix, it.isBumper,
                it.mediaOffsetSeconds, it.isMidBreak, if (it.item.type == MediaType.MOVIE &&
                    it.item.librarySource != io.github.madmax3553.staticgroot.channels.LibrarySource.MUSIC_VIDEO)
                    it.item.tmdbID?.let { id -> "tmdb:$id" } ?:
                    "${io.github.madmax3553.staticgroot.channels.stripTrailingYearSuffix(it.item.title.lowercase())}:${it.item.year ?: ""}"
                    else null)
        }

    fun selection(): Int? = json.decodeFromString(Selection.serializer(),
        http.get("$root/selection", headers)).channelId

    fun select(id: Int) { http.post("$root/selection", headers,
        json.encodeToString(Selection.serializer(), Selection(id))) }

    fun learnedPlayback(itemId: String, source: String, device: String): LearnedPlayback {
        fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
        return json.decodeFromString(LearnedPlayback.serializer(), http.get(
            "$root/playback?itemId=${encode(itemId)}&source=${encode(source)}&device=${encode(device)}", headers))
    }

    fun reportPlayback(sample: PlaybackObservation) {
        http.post("$root/playback", headers, json.encodeToString(PlaybackObservation.serializer(), sample))
    }

    companion object {
        /** Same Jellyfin server and user produce the same station; addresses are never published as IDs. */
        fun stationId(serverId: String, userId: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$serverId|$userId".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
