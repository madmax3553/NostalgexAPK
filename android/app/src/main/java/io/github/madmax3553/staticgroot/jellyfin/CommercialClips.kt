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

    /**
     * Fits whole clips into [remaining] seconds and reserves a 10-second station ident. The first
     * round uses each clip once, in the given order, so a normal break never repeats. A break
     * longer than the whole library (the 3 am end of day can run two hours) goes on in further
     * reshuffled rounds, never putting the same clip twice in a row, until nothing else fits.
     */
    fun fit(clips: List<CommercialClip>, remaining: Long, random: java.util.Random = java.util.Random()): List<CommercialClip> {
        var budget = (remaining - 10).coerceAtLeast(0)
        val out = ArrayList<CommercialClip>()
        var round = clips
        while (round.isNotEmpty() && out.size < MAX_PER_BREAK) {
            var added = false
            for (clip in round) {
                if (clip.durationSeconds <= budget && clip.id != out.lastOrNull()?.id) {
                    out.add(clip)
                    budget -= clip.durationSeconds
                    added = true
                }
            }
            if (!added) break
            round = clips.shuffled(random)
        }
        return out
    }

    /** A sanity ceiling: 2 hours of 15-second clips. */
    const val MAX_PER_BREAK = 480
}
