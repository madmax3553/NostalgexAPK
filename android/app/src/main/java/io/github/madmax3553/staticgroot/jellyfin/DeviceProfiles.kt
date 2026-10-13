package io.github.madmax3553.staticgroot.jellyfin

/**
 * The device profile sent with PlaybackInfo. Mirrors the shape of tvOS
 * JellyfinPlaybackResolver.deviceProfile, adapted to Android decoder capabilities.
 *
 * Not yet ported: the 8-bit cap on H.264. The bitrate cap comes from the viewer's StreamQuality.
 */
object DeviceProfiles {
    private const val AUDIO = "aac,mp3,ac3,eac3,flac,alac"

    fun supportsHevc(): Boolean = runCatching {
        android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.any {
            !it.isEncoder && it.supportedTypes.any { type -> type.equals("video/hevc", true) }
        }
    }.getOrDefault(false)

    fun forDevice(hevc: Boolean = supportsHevc(), settings: PlaybackSettings = PlaybackSettings()): DeviceProfile = DeviceProfile(
        MaxStreamingBitrate = settings.maxBitrate,
        MaxStaticBitrate = settings.maxBitrate,
        DirectPlayProfiles = listOf(
            // Media3 can demux Matroska. Unlike AVPlayer, it does not need a server remux
            // for compatible H.264/audio tracks just because the container is MKV.
            DirectPlayProfile("mp4,m4v,mov,mkv", "Video", if (hevc) "h264,hevc" else "h264", AUDIO),
        ),
        TranscodingProfiles = listOf(
            // HEVC first when the TV decodes it: a file with HEVC video and an audio track the TV
            // cannot play (TrueHD, DTS-HD) then gets its video copied and only the audio converted.
            // With H.264 alone the server re-encodes 4K video in software to change the audio,
            // measured at 0.008x real time on a 4 core server.
            TranscodingProfile("mp4", "Video", if (hevc) "hevc,h264" else "h264", AUDIO, "Streaming", "6", "hls", settings.maxHeight),
        ),
    )
}
