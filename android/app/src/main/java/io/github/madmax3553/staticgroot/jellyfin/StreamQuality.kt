package io.github.madmax3553.staticgroot.jellyfin

/**
 * The quality ladder. Port of tvOS StreamQuality with a different top rung: tvOS tops out at
 * 40 Mbps, Android's baseline is 100 Mbps, so a UHD remux of up to that bitrate is offered as
 * direct play instead of a server transcode. The cap only decides direct play versus transcode and
 * the ceiling of a transcode; a file under it plays as it is.
 *
 * The lower rungs are tvOS's own numbers. Choosing one forces anything above it through a server
 * transcode, so it suits a weak network or a weak server link, not a weak server.
 */
enum class StreamQuality(val id: String, val label: String, val detail: String, val maxBitrate: Int, val maxHeight: Int) {
    AUTO("auto", "Auto (100 Mbps)", "Up to 4K. Files under 100 Mbps play as they are.", 100_000_000, 2160),
    HIGH("high", "High (12 Mbps)", "1080p. Anything bigger is transcoded by the server.", 12_000_000, 1080),
    MEDIUM("medium", "Medium (4 Mbps)", "720p. Works on a weak network.", 4_000_000, 720),
    LOW("low", "Low (1.5 Mbps)", "480p. For very weak or metered connections.", 1_500_000, 480);

    fun settings() = PlaybackSettings(maxBitrate = maxBitrate, maxHeight = maxHeight)

    companion object {
        val DEFAULT = AUTO
        fun fromId(id: String?): StreamQuality = entries.firstOrNull { it.id == id } ?: DEFAULT

        /** A remembered setting never exceeds the rung the viewer chose, whatever it learned at a higher one. */
        fun clamp(learned: PlaybackSettings, cap: PlaybackSettings) = PlaybackSettings(
            maxBitrate = minOf(learned.maxBitrate, cap.maxBitrate),
            maxHeight = minOf(learned.maxHeight, cap.maxHeight),
        )
    }
}
