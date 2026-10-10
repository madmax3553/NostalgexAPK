package io.github.madmax3553.nostalgex.jellyfin

/**
 * The device profile sent with PlaybackInfo. Mirrors the shape of tvOS
 * JellyfinPlaybackResolver.deviceProfile, for H.264 only.
 *
 * Not yet ported: HEVC capability (tvOS uses VideoToolbox, Android needs MediaCodecList),
 * the 8-bit cap on H.264, and the bitrate ceiling from tvOS StreamQuality. The bitrate
 * below is a placeholder, not a measured value.
 */
object DeviceProfiles {
    const val MAX_BITRATE = 20_000_000
    private const val AUDIO = "aac,mp3,ac3,eac3,flac,alac"

    fun forDevice(): DeviceProfile = DeviceProfile(
        MaxStreamingBitrate = MAX_BITRATE,
        MaxStaticBitrate = MAX_BITRATE,
        DirectPlayProfiles = listOf(
            // Not mkv: the tvOS port notes that AVPlayer cannot demux Matroska. ExoPlayer can,
            // but the server still needs to agree, so keep the same container list for parity.
            DirectPlayProfile("mp4,m4v,mov", "Video", "h264", AUDIO),
        ),
        TranscodingProfiles = listOf(
            TranscodingProfile("mp4", "Video", "h264", AUDIO, "Streaming", "6", "hls"),
        ),
    )
}
