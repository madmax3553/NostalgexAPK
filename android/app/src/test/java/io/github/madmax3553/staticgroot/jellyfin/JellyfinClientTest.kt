package io.github.madmax3553.staticgroot.jellyfin

import io.github.madmax3553.staticgroot.channels.LibrarySource
import io.github.madmax3553.staticgroot.channels.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/*
 * Jellyfin client tests against synthetic JSON. No server, no real IDs or titles.
 * Each test states the tvOS behaviour it mirrors.
 */
class JellyfinClientTest {

    /** Replays canned responses by URL substring and records every request. */
    private class FakeHttp(private val routes: Map<String, String>) : JellyfinHttp {
        data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
        val calls = ArrayList<Call>()

        private fun answer(url: String): String {
            val key = routes.keys.firstOrNull { url.contains(it) }
                ?: throw JellyfinException("no fake route for $url", 404)
            return routes.getValue(key)
        }

        override fun get(url: String, headers: Map<String, String>): String {
            calls.add(Call("GET", url, headers, null))
            return answer(url)
        }

        override fun post(url: String, headers: Map<String, String>, body: String): String {
            calls.add(Call("POST", url, headers, body))
            return answer(url)
        }
    }

    private val deviceId = "test-device"

    // -- Auth -------------------------------------------------------------------------

    @Test
    fun authorizationHeaderOmitsTokenBeforeSignIn() {
        val h = JellyfinClient.authorizationHeader(deviceId, "")
        assertTrue(h.startsWith("MediaBrowser "))
        assertTrue(h.contains("DeviceId=\"test-device\""))
        assertFalse(h.contains("Token="))
    }

    @Test
    fun authorizationHeaderCarriesTokenAfterSignIn() {
        assertTrue(JellyfinClient.authorizationHeader(deviceId, "tok").contains("Token=\"tok\""))
    }

    @Test
    fun serverUrlIsTrimmedAndSchemeRequired() {
        assertEquals("http://host:8096", JellyfinClient.normalizeServerUrl("  http://host:8096/ "))
        try {
            JellyfinClient.normalizeServerUrl("host:8096")
            fail("expected an error for a missing scheme")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("http"))
        }
    }

    @Test
    fun authenticatePostsCredentialsAndReadsTheToken() {
        val http = FakeHttp(mapOf(
            "AuthenticateByName" to """{"AccessToken":"abc","ServerId":"srv","User":{"Id":"u1"}}""",
        ))
        val auth = JellyfinClient.authenticate("http://host:8096", "alice", "secret", http, deviceId)
        assertEquals("abc", auth.accessToken)
        assertEquals("u1", auth.userId)
        assertEquals("srv", auth.serverId)
        val call = http.calls.single()
        assertEquals("POST", call.method)
        assertTrue(call.body!!.contains("\"Username\":\"alice\""))
        assertFalse(call.headers.getValue("Authorization").contains("Token="))
    }

    @Test
    fun authenticateRefusesAnAnswerWithoutAToken() {
        val http = FakeHttp(mapOf("AuthenticateByName" to """{"User":{"Id":"u1"}}"""))
        try {
            JellyfinClient.authenticate("http://host:8096", "a", "b", http, deviceId)
            fail("expected a refusal")
        } catch (e: JellyfinException) {
            assertEquals(401, e.status)
        }
    }

    @Test
    fun quickConnectInitiateIsAPostAndConnectIsAGet() {
        // Jellyfin's QuickConnectController: Initiate is [HttpPost], Connect is [HttpGet].
        val http = FakeHttp(mapOf(
            "/QuickConnect/Initiate" to """{"Code":"123456","Secret":"s3cret"}""",
            "/QuickConnect/Connect" to """{"Authenticated":false}""",
        ))
        val start = JellyfinClient.quickConnectInitiate("http://host:8096", http, deviceId)
        assertEquals("123456", start.code)
        assertEquals("POST", http.calls.single().method)
        assertFalse(JellyfinClient.quickConnectCheck("http://host:8096", http, deviceId, start.secret))
        assertEquals("GET", http.calls.last().method)
        assertTrue(http.calls.last().url.contains("Secret=s3cret"))
    }

    // -- Libraries --------------------------------------------------------------------

    @Test
    fun collectionTypesMapToSectionTypes() {
        assertEquals("show", JellyfinClient.sectionType("tvshows"))
        assertEquals("musicvideo", JellyfinClient.sectionType("musicvideos"))
        assertEquals("movie", JellyfinClient.sectionType("movies"))
        assertEquals("movie", JellyfinClient.sectionType(null))
    }

    @Test
    fun loadSectionsUsesUserViewsForJellyfin() {
        val http = FakeHttp(mapOf(
            "/UserViews" to """{"Items":[
                {"Id":"v1","Name":"Clips","CollectionType":"musicvideos"},
                {"Id":"v2","Name":"Films","CollectionType":"movies"}]}""",
        ))
        val client = JellyfinClient("http://host:8096", http, deviceId, "tok", "u1")
        val sections = client.loadSections()
        assertEquals(listOf("musicvideo", "movie"), sections.map { it.type })
        assertTrue(http.calls.single().url.contains("userId=u1"))
    }

    @Test
    fun loadMoviesPagesUntilTotalRecordCountIsReached() {
        val page1 = """{"Items":[
            {"Id":"a","Name":"A","RunTimeTicks":600000000},
            {"Id":"b","Name":"B","RunTimeTicks":600000000}],"TotalRecordCount":3}"""
        val page2 = """{"Items":[{"Id":"c","Name":"C","RunTimeTicks":600000000}],"TotalRecordCount":3}"""
        val http = object : JellyfinHttp {
            val urls = ArrayList<String>()
            override fun get(url: String, headers: Map<String, String>): String {
                urls.add(url)
                return if (url.contains("StartIndex=0")) page1 else page2
            }
            override fun post(url: String, headers: Map<String, String>, body: String) = error("unused")
        }
        val items = JellyfinClient("http://host:8096", http, deviceId, "tok", "u1").loadMovies("lib")
        assertEquals(listOf("a", "b", "c"), items.map { it.Id })
        assertEquals(2, http.urls.size)
        assertTrue(http.urls[0].contains("IncludeItemTypes=Movie"))
        assertTrue(http.urls[1].contains("StartIndex=2"))
    }

    // -- Playback resolution ----------------------------------------------------------

    private val serverUrl = "http://host:8096"

    @Test
    fun serverTranscodeUrlWinsAndGetsTheApiKey() {
        val info = PlaybackInfoResponse(
            MediaSources = listOf(PlaybackSource(Id = "m", SupportsDirectPlay = true,
                TranscodingUrl = "/videos/x/master.m3u8?PlaySessionId=p1")),
            PlaySessionId = "p1",
        )
        val r = PlaybackResolver.resolve(info, serverUrl, "item1", "tok")!!
        assertFalse(r.isDirectPlay)
        assertEquals("http://host:8096/videos/x/master.m3u8?PlaySessionId=p1&api_key=tok", r.url)
    }

    @Test
    fun directPlayUsesTheStaticStreamWhenNoTranscodeUrlExists() {
        val info = PlaybackInfoResponse(
            MediaSources = listOf(PlaybackSource(Id = "m1", SupportsDirectPlay = true)),
            PlaySessionId = "p2",
        )
        val r = PlaybackResolver.resolve(info, serverUrl, "item1", "tok")!!
        assertTrue(r.isDirectPlay)
        assertEquals(
            "http://host:8096/Videos/item1/stream?Static=true&MediaSourceId=m1&api_key=tok",
            r.url,
        )
    }

    @Test
    fun nothingPlayableGivesNull() {
        val info = PlaybackInfoResponse(MediaSources = listOf(PlaybackSource(SupportsDirectPlay = false)))
        assertNull(PlaybackResolver.resolve(info, serverUrl, "item1", "tok"))
    }

    @Test
    fun resolvePlaybackSendsAMp4DeviceProfile() {
        val http = FakeHttp(mapOf(
            "/PlaybackInfo" to """{"MediaSources":[{"Id":"m","SupportsDirectPlay":true}],"PlaySessionId":"s"}""",
        ))
        val client = JellyfinClient(serverUrl, http, deviceId, "tok", "u1")
        val r = client.resolvePlayback("item1")!!
        assertTrue(r.isDirectPlay)
        val body = http.calls.single().body!!
        assertTrue(body.contains("\"DirectPlayProfiles\""))
        assertTrue(body.contains("\"Container\":\"mp4,m4v,mov\""))
        assertTrue(body.contains("\"Protocol\":\"hls\""))
    }

    // -- Item mapping -----------------------------------------------------------------

    private fun ticks(minutes: Int) = minutes * JellyfinMapping.TICKS_PER_MINUTE

    @Test
    fun runtimeTicksConvertToWholeMinutes() {
        assertEquals(3, JellyfinMapping.minutesFromTicks(ticks(3) + 12345))
        assertEquals(0, JellyfinMapping.minutesFromTicks(null))
    }

    @Test
    fun itemsWithoutRuntimeAreDropped() {
        val item = JellyfinItem(Id = "x", Name = "No time")
        assertNull(JellyfinMapping.toMediaItem(item, isMusicSection = true))
    }

    @Test
    fun musicVideoLibraryTagsItemsAndSetsTheMusicSource() {
        val item = JellyfinItem(Id = "v1", Name = "Clip", RunTimeTicks = ticks(4), Genres = listOf("Pop"))
        val m = JellyfinMapping.toMediaItem(item, isMusicSection = true)!!
        assertEquals(LibrarySource.MUSIC_VIDEO, m.librarySource)
        assertEquals(MediaType.MOVIE, m.type)
        assertTrue("Music Video" in m.genres)
        assertEquals(4, m.duration)
    }

    @Test
    fun shortMusicGenreClipInAMovieLibraryIsTreatedAsAMusicVideo() {
        val short = JellyfinItem(Id = "s", Name = "Short", RunTimeTicks = ticks(5), Genres = listOf("Music"))
        val long = JellyfinItem(Id = "l", Name = "Musical", RunTimeTicks = ticks(90), Genres = listOf("Music"))
        assertEquals(LibrarySource.MUSIC_VIDEO, JellyfinMapping.toMediaItem(short, false)!!.librarySource)
        assertEquals(LibrarySource.MOVIE, JellyfinMapping.toMediaItem(long, false)!!.librarySource)
    }

    @Test
    fun providerIdsMatchCaseInsensitively() {
        assertEquals("123", JellyfinMapping.providerId(mapOf("tmdb" to "123"), "Tmdb"))
        assertEquals("123", JellyfinMapping.providerId(mapOf("Tmdb" to "123"), "Tmdb"))
        assertNull(JellyfinMapping.providerId(null, "Tmdb"))
    }

    @Test
    fun premiereDateTakesTheYearMonthDay() {
        val item = JellyfinItem(Id = "p", Name = "P", RunTimeTicks = ticks(3), PremiereDate = "2019-05-17T00:00:00.0000000Z")
        assertEquals("2019-05-17", JellyfinMapping.toMediaItem(item, true)!!.originallyAvailableAt)
    }

    @Test
    fun dateCreatedParsesFractionalSecondsAndFallsBackToZero() {
        assertEquals(1_700_000_000L, JellyfinMapping.parseEpochSeconds("2023-11-14T22:13:20.1234567Z"))
        assertEquals(0L, JellyfinMapping.parseEpochSeconds("not a date"))
        assertEquals(0L, JellyfinMapping.parseEpochSeconds(null))
    }
}
