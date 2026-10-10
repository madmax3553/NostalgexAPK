package io.github.madmax3553.staticgroot.schedule

import java.io.File

/*
 * Port of the day-manifest store in tvOS DailyManifestStore.swift, reduced to what the
 * seam rules read: the item ids a channel aired on a day, in order. One small text file per
 * channel per day, named "<channel>_<yyyy-MM-dd>.txt", one item id per line.
 *
 * Files live in the app's private storage. They hold item ids and day keys, no titles and
 * no server details. Nothing is sent anywhere.
 */
class FileDayHistory(private val dir: File) : AiredHistory {

    init {
        dir.mkdirs()
    }

    private fun file(channelId: Int, dayKey: String) = File(dir, "${channelId}_$dayKey.txt")

    private fun read(channelId: Int, dayKey: String): List<String> {
        val f = file(channelId, dayKey)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotEmpty() }
    }

    override fun airedKeys(channelId: Int, dayKey: String): Set<String> =
        read(channelId, dayKey).toSet()

    override fun lastAired(channelId: Int, dayKey: String): String? =
        read(channelId, dayKey).lastOrNull()

    override fun record(channelId: Int, dayKey: String, orderedItemIds: List<String>) {
        // Write to a temp file, then rename, so a crash mid-write cannot leave half a day.
        val target = file(channelId, dayKey)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(orderedItemIds.joinToString("\n", postfix = "\n"))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
