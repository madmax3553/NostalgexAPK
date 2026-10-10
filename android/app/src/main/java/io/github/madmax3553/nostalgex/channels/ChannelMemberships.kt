package io.github.madmax3553.nostalgex.channels

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Port of tvOS ChannelMemberships.swift. The manifest is built offline by
 * scripts/channel-keyword-tuner and shipped as channels-memberships.json at the repo root.
 * Keys are "<mediaType>:<tmdbID>", e.g. "tv:1396" or "movie:12345".
 */
@Serializable
data class MembershipEntry(
    val channels: List<Int>,
    val exclusive: Int? = null,
)

@Serializable
data class MembershipFile(
    val version: Int,
    val generated: String,
    val items: Map<String, MembershipEntry>,
)

class ChannelMemberships(private val items: Map<String, MembershipEntry>) {

    /** Channels that claim this TMDB item, or null when it is not claimed. */
    fun channels(mediaType: String, tmdbID: String): List<Int>? =
        items["$mediaType:$tmdbID"]?.channels

    /** The channel this item is locked to, or null. */
    fun exclusive(mediaType: String, tmdbID: String): Int? =
        items["$mediaType:$tmdbID"]?.exclusive

    val size: Int get() = items.size

    companion object {
        val EMPTY = ChannelMemberships(emptyMap())

        // Unknown keys are ignored so a newer manifest does not break an older APK.
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse channels-memberships.json. Returns [EMPTY] for unparseable input, like the Swift loader. */
        fun parse(text: String): ChannelMemberships = try {
            ChannelMemberships(json.decodeFromString(MembershipFile.serializer(), text).items)
        } catch (e: kotlinx.serialization.SerializationException) {
            EMPTY
        }
    }
}
