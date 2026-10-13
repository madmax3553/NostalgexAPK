package io.github.madmax3553.staticgroot.jellyfin

import kotlinx.serialization.Serializable

@Serializable data class PlaybackSettings(val maxBitrate: Int = StreamQuality.DEFAULT.maxBitrate, val maxHeight: Int = 2160)
@Serializable data class PlaybackObservation(val itemId: String, val sourceFingerprint: String,
    val deviceProfile: String, val outcome: String, val settings: PlaybackSettings,
    val startupMs: Long, val playedMs: Long, val wallMs: Long, val encoderSpeed: Double? = null)
@Serializable data class LearnedPlayback(val preferred: PlaybackSettings? = null)

/** A decoded first frame is not enough. Require a continuous, near-real-time sample. */
object PlaybackLearning {
    fun sustained(playedMs: Long, wallMs: Long): Boolean =
        wallMs >= 30_000 && playedMs >= 30_000 && playedMs.toDouble() / wallMs >= .9
}
