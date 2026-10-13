package io.github.madmax3553.staticgroot.jellyfin

import io.github.madmax3553.staticgroot.channels.LibrarySource
import io.github.madmax3553.staticgroot.channels.MediaItem
import java.net.URLEncoder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    fun stationIdentity(): String {
        val response = http.get(url("/System/Info/Public"), mapOf("Accept" to "application/json"))
        val info = json.parseToJsonElement(response).jsonObject
        val serverId = info["Id"]?.jsonPrimitive?.content ?: throw IllegalStateException("Jellyfin server identity missing")
        return io.github.madmax3553.staticgroot.schedule.SharedStation.stationId(serverId, userId)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val playbackJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val learnedCache = java.util.concurrent.ConcurrentHashMap<String, PlaybackSettings>()
    /** The viewer's chosen rung. Set by the guide from DisplaySettings, and when they change it. */
    @Volatile var streamQuality: StreamQuality = StreamQuality.DEFAULT
    private val hevc by lazy { DeviceProfiles.supportsHevc() }
    fun invalidateLearnedPlayback(itemId: String, source: String, device: String) {
        learnedCache.remove("$itemId|$source|$device")
    }
    private val deviceClass by lazy {
        // Decoder implementation names distinguish TVs with different hardware support.
        val codecs = runCatching { android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
            .codecInfos.filter { !it.isEncoder }.map { it.name }.sorted().joinToString("|") }.getOrDefault("unknown")
        "android-v1-" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("$hevc|$codecs".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun headers(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Authorization" to authorizationHeader(deviceId, accessToken),
    )

    /** HLS child playlists and segments must authenticate too; query strings aren't inherited. */
    fun playbackHeaders(): Map<String, String> = mapOf(
        "Authorization" to authorizationHeader(deviceId, accessToken),
        "X-Emby-Token" to accessToken,
    )

    /** Libraries (views) for the signed-in user, with their section type. */
    fun loadSections(): List<LibrarySection> {
        val body = http.get(url("/UserViews", "userId" to userId), headers())
        val views = json.decodeFromString(ItemsResponse.serializer(), body).Items.orEmpty()
        return views.map {
            LibrarySection(it.Id, it.Name ?: "Library", sectionType(it.CollectionType), it.CollectionType)
        }
    }

    /**
     * Total items of one Jellyfin item type under a library, with no page read. Diagnostic:
     * shows whether a library's clips are stored as "Movie" or as "MusicVideo".
     */
    fun loadCommercials(): List<CommercialClip> = loadSections()
        .filter { CommercialClips.isLibrary(it.title) }
        .flatMap { loadItems(it.id, "Movie,Video,MusicVideo") }
        .mapNotNull(CommercialClips::fromItem).distinctBy { it.id }

    fun countItems(parentId: String, includeItemTypes: String): Int {
        val body = http.get(
            url(
                "/Items",
                "userId" to userId,
                "ParentId" to parentId,
                "Recursive" to "true",
                "IncludeItemTypes" to includeItemTypes,
                "Limit" to "0",
            ),
            headers(),
        )
        return json.decodeFromString(ItemsResponse.serializer(), body).TotalRecordCount ?: 0
    }

    /** All items of [includeItemTypes] directly under a parent, paged until the total is read. */
    fun loadItems(parentId: String, includeItemTypes: String): List<JellyfinItem> {
        val collected = ArrayList<JellyfinItem>()
        var start = 0
        while (true) {
            val body = http.get(
                url(
                    "/Items",
                    "userId" to userId,
                    "ParentId" to parentId,
                    "Recursive" to "true",
                    "IncludeItemTypes" to includeItemTypes,
                    "Fields" to "ProviderIds,Genres,Studios,MediaSources,ProductionYear,PremiereDate,DateCreated,UserData,Overview",
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

    /** Movies (Jellyfin's flat item type) under a library. */
    fun loadMovies(parentId: String, includeItemTypes: String = "Movie"): List<JellyfinItem> =
        loadItems(parentId, includeItemTypes)

    /** Series under a TV library. */
    fun loadSeries(parentId: String): List<JellyfinItem> = loadItems(parentId, "Series")

    /** Episodes of one series. */
    fun loadEpisodes(seriesId: String): List<JellyfinItem> = loadItems(seriesId, "Episode")

    /**
     * Playback URL for an item. Mirrors tvOS JellyfinPlaybackResolver: the server's transcode
     * URL wins, then a static direct-play stream. Returns null when neither exists.
     */
    fun resolvePlayback(itemId: String, mediaSourceId: String? = null, forceDirect: Boolean = false): PlaybackResolution? {
        fun negotiate(settings: PlaybackSettings): PlaybackInfoResponse {
            val body = playbackJson.encodeToString(PlaybackInfoBody.serializer(),
                PlaybackInfoBody(DeviceProfiles.forDevice(hevc, settings), mediaSourceId))
            return json.decodeFromString(PlaybackInfoResponse.serializer(), http.post(
            url("/Items/$itemId/PlaybackInfo", "userId" to userId),
            headers(),
            body,
            ))
        }
        // forceDirect lifts the bitrate cap so the server offers the file itself instead of a
        // transcode it cannot produce fast enough. It also skips learned settings, which came
        // from the transcode path that just failed.
        val cap = streamQuality.settings()
        var settings = if (forceDirect) cap.copy(maxBitrate = DIRECT_PLAY_BITRATE) else cap
        var info = negotiate(settings)
        val source = PlaybackResolver.pickSource(info, mediaSourceId)
        // No freshness facts means no reusable record. Do not assume an item ID implies
        // the same file after replacement/re-encoding.
        val fingerprint = source?.ETag?.takeIf { it.isNotBlank() }?.let {
            "${source.Id}|$it|${source.Size}|${source.RunTimeTicks}"
        }
        val shared = io.github.madmax3553.staticgroot.AppSession.sharedStation
        if (fingerprint != null && shared != null && !forceDirect) {
            val key = "$itemId|$fingerprint|$deviceClass"
            val preferred = (learnedCache[key] ?: runCatching {
                shared.learnedPlayback(itemId, fingerprint, deviceClass).preferred
            }.getOrNull()?.also { learnedCache[key] = it })?.let { StreamQuality.clamp(it, cap) }
            if (preferred != null && preferred != settings) {
                runCatching { stopPlayback(info.PlaySessionId) }
                settings = preferred
                info = negotiate(settings)
            }
        }
        val resolution = PlaybackResolver.resolve(
            info = info,
            serverUrl = serverUrl,
            itemId = itemId,
            apiKey = accessToken,
            mediaSourceId = mediaSourceId,
        )
        return resolution?.copy(sourceFingerprint = fingerprint, deviceProfile = deviceClass,
            settings = settings, itemId = itemId)
    }

    /** Releases the server encode when switching programmes or leaving playback. */
    fun stopPlayback(sessionId: String?) {
        if (sessionId.isNullOrEmpty()) return
        http.post(url("/Sessions/Playing/Stopped"), headers(),
            "{\"PlaySessionId\":\"$sessionId\"}")
    }

    /** Builds an absolute URL with query parameters encoded. */
    private fun url(path: String, vararg query: Pair<String, String>): String {
        val qs = query.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return if (qs.isEmpty()) "$serverUrl$path" else "$serverUrl$path?$qs"
    }

    companion object {
        const val PAGE_SIZE = 200
        /** 4K remuxes run near 100 Mbps. This only has to clear the file's own bitrate. */
        const val DIRECT_PLAY_BITRATE = 200_000_000

        fun authorizationHeader(deviceId: String, token: String): String {
            val fields = mutableListOf(
                "Client=\"StaticGroot\"",
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

        /** Starts Quick Connect. The user approves [code] in the Jellyfin dashboard. */
        fun quickConnectInitiate(serverUrl: String, http: JellyfinHttp, deviceId: String): QuickConnectStart {
            val base = normalizeServerUrl(serverUrl)
            // Initiate is a POST on Jellyfin (Jellyfin.Api QuickConnectController). Connect is a GET.
            val body = http.post(
                "$base/QuickConnect/Initiate",
                mapOf("Accept" to "application/json", "Authorization" to authorizationHeader(deviceId, "")),
                "",
            )
            val r = Json { ignoreUnknownKeys = true }.decodeFromString(QuickConnectResult.serializer(), body)
            val code = r.Code
            val secret = r.Secret
            if (code.isNullOrEmpty() || secret.isNullOrEmpty()) {
                throw JellyfinException("Quick Connect is not available on this server")
            }
            return QuickConnectStart(code, secret)
        }

        /** One poll. True once the user has approved the code. */
        fun quickConnectCheck(serverUrl: String, http: JellyfinHttp, deviceId: String, secret: String): Boolean {
            val base = normalizeServerUrl(serverUrl)
            val body = http.get(
                "$base/QuickConnect/Connect?Secret=${URLEncoder.encode(secret, "UTF-8")}",
                mapOf("Accept" to "application/json", "Authorization" to authorizationHeader(deviceId, "")),
            )
            return Json { ignoreUnknownKeys = true }
                .decodeFromString(QuickConnectResult.serializer(), body).Authenticated == true
        }

        /** Exchanges an approved Quick Connect secret for a token. */
        fun quickConnectAuthenticate(serverUrl: String, http: JellyfinHttp, deviceId: String, secret: String): AuthResult {
            val base = normalizeServerUrl(serverUrl)
            val body = Json.encodeToString(QuickConnectSecret.serializer(), QuickConnectSecret(secret))
            val response = http.post(
                "$base/Users/AuthenticateWithQuickConnect",
                mapOf("Accept" to "application/json", "Authorization" to authorizationHeader(deviceId, "")),
                body,
            )
            val result = Json { ignoreUnknownKeys = true }.decodeFromString(AuthResponse.serializer(), response)
            val token = result.AccessToken
            val uid = result.User?.Id
            if (token.isNullOrEmpty() || uid.isNullOrEmpty()) {
                throw JellyfinException("Quick Connect was not approved", 401)
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

data class LibrarySection(
    val id: String,
    val title: String,
    val type: String,
    val collectionType: String? = null,
)

data class QuickConnectStart(val code: String, val secret: String)

/** The resolved playback URL, and whether it is a direct file or a server transcode. */
data class PlaybackResolution(val url: String, val playSessionId: String?, val isDirectPlay: Boolean,
    val sourceFingerprint: String? = null, val deviceProfile: String? = null,
    val settings: PlaybackSettings = PlaybackSettings(), val itemId: String? = null)

/** Pure resolution, so the precedence rules can be tested without a server. */
object PlaybackResolver {
    /** The requested source when the server returned it, else the first, as tvOS does. */
    fun pickSource(info: PlaybackInfoResponse, mediaSourceId: String?): PlaybackSource? {
        val sources = info.MediaSources.orEmpty()
        return sources.firstOrNull { mediaSourceId != null && it.Id == mediaSourceId } ?: sources.firstOrNull()
    }

    fun resolve(
        info: PlaybackInfoResponse,
        serverUrl: String,
        itemId: String,
        apiKey: String,
        mediaSourceId: String? = null,
    ): PlaybackResolution? {
        val source = pickSource(info, mediaSourceId)

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
