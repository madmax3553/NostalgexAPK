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
            TranscodingProfile("mp4", "Video", "h264", AUDIO, "Streaming", "6", "hls", settings.maxHeight),
        ),
    )
}
