package io.github.madmax3553.staticgroot.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Jellyfin DTOs, the subset the port reads. Field names follow the Jellyfin JSON,
 * which is also what tvOS JellyfinAPIService.swift decodes. Unknown keys are ignored.
 */

@Serializable
data class AuthBody(val Username: String, val Pw: String)

@Serializable
data class AuthUser(val Id: String? = null, val ServerName: String? = null)

@Serializable
data class AuthResponse(
    val AccessToken: String? = null,
    val ServerId: String? = null,
    val User: AuthUser? = null,
)

@Serializable
data class ItemsResponse(
    val Items: List<JellyfinItem>? = null,
    val TotalRecordCount: Int? = null,
)

@Serializable
data class JellyfinItem(
    val Id: String,
    val Name: String? = null,
    val SeriesName: String? = null,
    val Overview: String? = null,
    val ParentIndexNumber: Int? = null,
    val IndexNumber: Int? = null,
    val ProductionYear: Int? = null,
    val PremiereDate: String? = null,
    val DateCreated: String? = null,
    val OfficialRating: String? = null,
    val CommunityRating: Double? = null,
    val RunTimeTicks: Long? = null,
    val Genres: List<String>? = null,
    val CollectionType: String? = null,
    val ProviderIds: Map<String, String>? = null,
    val Studios: List<Studio>? = null,
    val MediaSources: List<MediaSource>? = null,
    val UserData: UserData? = null,
)

@Serializable
data class Studio(val Name: String? = null)

@Serializable
data class UserData(val Played: Boolean? = null, val PlayCount: Int? = null)

@Serializable
data class MediaSource(
    val Id: String? = null,
    val Name: String? = null,
    val Path: String? = null,
    val RunTimeTicks: Long? = null,
    val Container: String? = null,
    val Bitrate: Int? = null,
    val MediaStreams: List<MediaStream>? = null,
)

@Serializable
data class MediaStream(
    @SerialName("Type") val type: String? = null,
    val Codec: String? = null,
    val Profile: String? = null,
    val Width: Int? = null,
    val Height: Int? = null,
    val BitDepth: Int? = null,
)

/** Response of POST /Items/{id}/PlaybackInfo. */
@Serializable
data class PlaybackInfoResponse(
    val MediaSources: List<PlaybackSource>? = null,
    val PlaySessionId: String? = null,
)

@Serializable
data class PlaybackSource(
    val Id: String? = null,
    val SupportsDirectPlay: Boolean? = null,
    val TranscodingUrl: String? = null,
    val MediaStreams: List<MediaStream>? = null,
    val ETag: String? = null,
    val Size: Long? = null,
    val RunTimeTicks: Long? = null,
)

/** The device profile sent with PlaybackInfo. Tells the server what this device can play. */
@Serializable
data class DeviceProfile(
    val MaxStreamingBitrate: Int,
    val MaxStaticBitrate: Int,
    val DirectPlayProfiles: List<DirectPlayProfile>,
    val TranscodingProfiles: List<TranscodingProfile>,
)

@Serializable
data class DirectPlayProfile(
    val Container: String,
    @SerialName("Type") val mediaType: String,
    val VideoCodec: String,
    val AudioCodec: String,
)

@Serializable
data class TranscodingProfile(
    val Container: String,
    @SerialName("Type") val mediaType: String,
    val VideoCodec: String,
    val AudioCodec: String,
    val Context: String,
    val MaxAudioChannels: String,
    @SerialName("Protocol") val protocolName: String,
    val MaxHeight: Int = 2160,
)

@Serializable
data class PlaybackInfoBody(
    val DeviceProfile: DeviceProfile,
    val MediaSourceId: String? = null,
)

@Serializable
data class QuickConnectResult(
    val Code: String? = null,
    val Secret: String? = null,
    val Authenticated: Boolean? = null,
)

@Serializable
data class QuickConnectSecret(val Secret: String)
