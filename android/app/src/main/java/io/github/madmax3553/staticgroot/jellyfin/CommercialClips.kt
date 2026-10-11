package io.github.madmax3553.staticgroot.jellyfin

/** Precise seconds, not rounded programme minutes: short ads must remain playable. */
data class CommercialClip(val id: String, val title: String, val durationSeconds: Long)

object CommercialClips {
    fun isLibrary(title: String) = title.trim().equals("Commercials", ignoreCase = true)

    fun fromItem(item: JellyfinItem): CommercialClip? {
        val ticks = item.RunTimeTicks ?: return null
        val seconds = (ticks + 9_999_999) / 10_000_000
        if (seconds <= 0) return null
        return CommercialClip(item.Id, item.Name ?: "Commercial", seconds)
    }

    /** Fit whole clips, leave at least a 10-second station ident; no repeats inside a break. */
    fun fit(clips: List<CommercialClip>, remaining: Long): List<CommercialClip> {
        var budget = (remaining - 10).coerceAtLeast(0)
        return buildList {
            for (clip in clips) {
                if (clip.durationSeconds <= budget) {
                    add(clip)
                    budget -= clip.durationSeconds
                }
            }
        }
    }
}
