package io.github.madmax3553.staticgroot.enrichment

import io.github.madmax3553.staticgroot.channels.MusicVideoEnrichment
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** On-disk music enrichment cache, keyed by server item id. Public catalogue metadata only. Unreadable reads as empty. */
class MusicEnrichmentStore(private val file: File) {

    @Serializable
    private data class Disk(val version: Int, val records: List<MusicVideoEnrichment>)

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Map<String, MusicVideoEnrichment> {
        if (!file.exists()) return emptyMap()
        return try {
            val disk = json.decodeFromString<Disk>(file.readText())
            if (disk.version != VERSION) emptyMap() else disk.records.associateBy { it.itemId }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Temp file then rename, so a kill mid-write cannot leave half a cache. */
    @Synchronized
    fun save(records: Collection<MusicVideoEnrichment>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Disk(VERSION, records.toList())))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object { const val VERSION = 1 }
}
