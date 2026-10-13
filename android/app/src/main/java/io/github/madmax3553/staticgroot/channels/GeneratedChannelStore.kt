package io.github.madmax3553.staticgroot.channels

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Remembers generated channels between launches. Unreadable or newer-format files read as empty. */
class GeneratedChannelStore(private val file: File) {

    @Serializable
    private data class Disk(val version: Int, val records: List<GeneratedRecord>)

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<GeneratedRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val d = json.decodeFromString<Disk>(file.readText())
            if (d.version == VERSION) d.records else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(records: List<GeneratedRecord>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Disk(VERSION, records)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object { const val VERSION = 1 }
}
