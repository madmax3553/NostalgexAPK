package io.github.madmax3553.staticgroot.enrichment

import io.github.madmax3553.staticgroot.channels.MediaEnrichment
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk enrichment cache, keyed by TMDB id. Stands in for tvOS's shared Supabase cache,
 * which this port does not use. It holds public catalogue metadata only, no credentials.
 * A missing, unreadable or newer-format file reads as empty: the cache is rebuilt from the
 * network, never trusted over it.
 */
class EnrichmentStore(private val file: File) {

    @Serializable
    private data class Disk(val version: Int, val records: List<MediaEnrichment>)

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Map<String, MediaEnrichment> {
        if (!file.exists()) return emptyMap()
        return try {
            val disk = json.decodeFromString<Disk>(file.readText())
            if (disk.version != VERSION) emptyMap() else disk.records.filter { it.tmdbID.isNotEmpty() }.associateBy { it.tmdbID }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Writes to a temp file, then renames, so a kill mid-write cannot leave half a cache. */
    @Synchronized
    fun save(records: Collection<MediaEnrichment>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Disk(VERSION, records.toList())))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val VERSION = 1
    }
}
