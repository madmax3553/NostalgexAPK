package io.github.madmax3553.staticgroot.schedule

import android.util.Log
import io.github.madmax3553.staticgroot.channels.ChannelDefinition
import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.jellyfin.CommercialClip
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class LaunchSnapshot(
    val version: Int = 1,
    val stationId: String,
    val generation: Int = 0,
    val timeZone: String = "America/Edmonton",
    val savedAtEpochSeconds: Long = 0L,
    val lastChannelId: Int? = null,
    val channels: List<ChannelDefinition> = emptyList(),
    val items: List<MediaItem> = emptyList(),
    val commercials: List<CommercialClip> = emptyList(),
    /** Keyed by "$channelId|$dayKey" for [generation]. */
    val publishedDays: Map<String, List<StoredBlock>> = emptyMap(),
)

/**
 * Local snapshot of the mapped library, active lineup and immutable published days for the
 * current station generation. Allows cold launches with an existing schedule to open the
 * home screen on the last-watched channel immediately while offscreen rows load in the background.
 */
class LaunchSnapshotStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(expectedStationId: String): LaunchSnapshot? = try {
        if (!file.exists()) null
        else {
            val snap = json.decodeFromString(LaunchSnapshot.serializer(), file.readText())
            if (snap.version == CURRENT_VERSION && snap.stationId == expectedStationId && snap.items.isNotEmpty()) snap
            else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "SNAPSHOT load failed (${e.javaClass.simpleName}): ${e.message}")
        null
    }

    @Synchronized
    fun save(snapshot: LaunchSnapshot) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(LaunchSnapshot.serializer(), snapshot))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "SNAPSHOT save failed (${e.javaClass.simpleName}): ${e.message}")
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
        private const val TAG = "StaticGroot"
    }
}
