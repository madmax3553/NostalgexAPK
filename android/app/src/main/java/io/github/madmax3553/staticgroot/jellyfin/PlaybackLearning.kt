package io.github.madmax3553.staticgroot.jellyfin

import kotlinx.serialization.Serializable

@Serializable data class PlaybackSettings(val maxBitrate: Int = StreamQuality.DEFAULT.maxBitrate, val maxHeight: Int = 2160)
@Serializable data class PlaybackObservation(val itemId: String, val sourceFingerprint: String,
    val deviceProfile: String, val outcome: String, val settings: PlaybackSettings,
    val startupMs: Long, val playedMs: Long, val wallMs: Long, val encoderSpeed: Double? = null)
@Serializable data class LearnedPlayback(val preferred: PlaybackSettings? = null)

/**
 * One failed stream, kept by the shared service for a week. Carries class names and codes only:
 * exception messages and URLs can contain the access token.
 */
@Serializable data class PlaybackFailure(val kind: String, val appVersion: String, val device: String,
    val route: String, val channelId: Int?, val itemId: String?, val mediaSourceId: String?,
    val commercial: Boolean, val direct: Boolean?, val maxBitrate: Int?, val maxHeight: Int?,
    val offsetSeconds: Long, val errorCode: String?, val causes: List<String>, val sinceTuneMs: Long,
    val firstFrame: Boolean, val bufferingMs: Long, val positionMs: Long, val loads: Int, val loadedBytes: Long)

/** How the TV reaches Jellyfin, without recording the address. */
object NetworkRoute {
    fun of(serverUrl: String?): String {
        val host = runCatching { java.net.URI(serverUrl.orEmpty()).host }.getOrNull().orEmpty().lowercase()
        if (host.endsWith(".ts.net")) return "tailscale"
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return "other"
        return when {
            parts[0] == 100 && parts[1] in 64..127 -> "tailscale"
            parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31) -> "lan"
            else -> "other"
        }
    }
}

/** A decoded first frame is not enough. Require a continuous, near-real-time sample. */
object PlaybackLearning {
    fun sustained(playedMs: Long, wallMs: Long): Boolean =
        wallMs >= 30_000 && playedMs >= 30_000 && playedMs.toDouble() / wallMs >= .9
}
