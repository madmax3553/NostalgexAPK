package io.github.madmax3553.staticgroot.jellyfin

import io.github.madmax3553.staticgroot.channels.LibrarySource
import io.github.madmax3553.staticgroot.channels.MediaItem
import java.net.URLEncoder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * Port of the Jellyfin path in tvOS JellyfinAPIService.swift, music-video subset.
 *
 * Quirks kept from the Swift code:
 *  - Libraries come from /UserViews (Jellyfin). Emby uses /Users/{id}/Views instead. Not this client.
 *  - Auth uses the MediaBrowser Authorization scheme, with Token omitted before sign-in.
 *  - Library pages are read with StartIndex/Limit until TotalRecordCount is reached.
 *
 * Blocking calls. Run them on a background thread.
 */
class JellyfinClient(
    serverUrl: String,
    private val http: JellyfinHttp,
    private val deviceId: String,
    private val accessToken: String = "",
    private val userId: String = "",
) {
    val serverUrl: String = normalizeServerUrl(serverUrl)

    private val json = Json { ignoreUnknownKeys = true }

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Authorization" to authorizationHeader(deviceId, accessToken),
    )

    /** Libraries (views) for the signed-in user, with their section type. */
    fun loadSections(): List<LibrarySection> {
        val body = http.get(url("/UserViews", "userId" to userId), headers())
        val views = json.decodeFromString(ItemsResponse.serializer(), body).Items.orEmpty()
        return views.map { LibrarySection(it.Id, it.Name ?: "Library", sectionType(it.CollectionType)) }
    }

    /** All movies (Jellyfin's flat item type) directly under a library, paged. */
    fun loadMovies(parentId: String): List<JellyfinItem> {
        val collected = ArrayList<JellyfinItem>()
        var start = 0
        while (true) {
            val body = http.get(
                url(
                    "/Items",
                    "userId" to userId,
                    "ParentId" to parentId,
                    "Recursive" to "true",
                    "IncludeItemTypes" to "Movie",
                    "Fields" to "ProviderIds,Genres,Studios,MediaSources,ProductionYear,PremiereDate,DateCreated,UserData",
                    "StartIndex" to start.toString(),
                    "Limit" to PAGE_SIZE.toString(),
                    "SortBy" to "SortName",
                ),
                headers(),
            )
            val page = json.decodeFromString(ItemsResponse.serializer(), body)
            val items = page.Items.orEmpty()
            collected.addAll(items)
            start += items.size
            val total = page.TotalRecordCount ?: collected.size
            if (items.isEmpty() || start >= total) break
        }
        return collected
    }

    /**
     * Playback URL for an item. Mirrors tvOS JellyfinPlaybackResolver: the server's transcode
     * URL wins, then a static direct-play stream. Returns null when neither exists.
     */
    fun resolvePlayback(itemId: String): PlaybackResolution? {
        val body = json.encodeToString(
            PlaybackInfoBody.serializer(),
            PlaybackInfoBody(DeviceProfiles.forDevice()),
        )
        val response = http.post(
            url("/Items/$itemId/PlaybackInfo", "userId" to userId),
            headers(),
            body,
        )
        val info = json.decodeFromString(PlaybackInfoResponse.serializer(), response)
        return PlaybackResolver.resolve(
            info = info,
            serverUrl = serverUrl,
            itemId = itemId,
            apiKey = accessToken,
        )
    }

    /** Builds an absolute URL with query parameters encoded. */
    private fun url(path: String, vararg query: Pair<String, String>): String {
        val qs = query.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return if (qs.isEmpty()) "$serverUrl$path" else "$serverUrl$path?$qs"
    }

    companion object {
        const val PAGE_SIZE = 200

        fun authorizationHeader(deviceId: String, token: String): String {
            val fields = mutableListOf(
                "Client=\"Nostalgex\"",
                "Device=\"Android TV\"",
                "DeviceId=\"$deviceId\"",
                "Version=\"0.1.0\"",
            )
            if (token.isNotEmpty()) fields.add("Token=\"$token\"")
            return "MediaBrowser " + fields.joinToString(", ")
        }

        /** Trims whitespace and trailing slashes. Requires an http or https scheme. */
        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                "Server address must start with http:// or https://"
            }
            return trimmed
        }

        /** Sign in with a username and password. Returns the token and user id. */
        fun authenticate(
            serverUrl: String,
            username: String,
            password: String,
            http: JellyfinHttp,
            deviceId: String,
        ): AuthResult {
            val base = normalizeServerUrl(serverUrl)
            val body = Json.encodeToString(AuthBody.serializer(), AuthBody(username, password))
            val response = http.post(
                "$base/Users/AuthenticateByName",
                mapOf(
                    "Accept" to "application/json",
                    "Authorization" to authorizationHeader(deviceId, ""),
                ),
                body,
            )
            val result = Json { ignoreUnknownKeys = true }.decodeFromString(AuthResponse.serializer(), response)
            val token = result.AccessToken
            val uid = result.User?.Id
            if (token.isNullOrEmpty() || uid.isNullOrEmpty()) {
                throw JellyfinException("Sign-in was refused", 401)
            }
            return AuthResult(accessToken = token, userId = uid, serverId = result.ServerId.orEmpty())
        }

        /** Maps a Jellyfin CollectionType to the section type the loader branches on. */
        fun sectionType(collectionType: String?): String = when (collectionType?.lowercase()) {
            "tvshows" -> "show"
            "musicvideos" -> "musicvideo"
            else -> "movie"
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}

data class AuthResult(val accessToken: String, val userId: String, val serverId: String)

data class LibrarySection(val id: String, val title: String, val type: String)

/** The resolved playback URL, and whether it is a direct file or a server transcode. */
data class PlaybackResolution(val url: String, val playSessionId: String?, val isDirectPlay: Boolean)

/** Pure resolution, so the precedence rules can be tested without a server. */
object PlaybackResolver {
    fun resolve(
        info: PlaybackInfoResponse,
        serverUrl: String,
        itemId: String,
        apiKey: String,
    ): PlaybackResolution? {
        val source = info.MediaSources.orEmpty().firstOrNull()

        // 1) Server transcode URL, used as given. Add the api_key if the server left it out.
        val relative = source?.TranscodingUrl
        if (!relative.isNullOrEmpty()) {
            val absolute = if (relative.startsWith("http")) relative else serverUrl + relative
            val withKey = if (absolute.contains("api_key=")) absolute
            else absolute + (if (absolute.contains("?")) "&" else "?") + "api_key=" + enc(apiKey)
            return PlaybackResolution(withKey, info.PlaySessionId, isDirectPlay = false)
        }

        // 2) Directly playable: static file stream.
        if (source?.SupportsDirectPlay == true) {
            val mediaSourceId = source.Id ?: itemId
            val url = "$serverUrl/Videos/$itemId/stream?Static=true" +
                "&MediaSourceId=${enc(mediaSourceId)}&api_key=${enc(apiKey)}"
            return PlaybackResolution(url, info.PlaySessionId, isDirectPlay = true)
        }

        // 3) Nothing playable.
        return null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
