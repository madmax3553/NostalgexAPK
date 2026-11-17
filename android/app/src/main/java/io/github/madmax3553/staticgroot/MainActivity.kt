package io.github.madmax3553.staticgroot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.madmax3553.staticgroot.channels.ChannelConfigLoader
import io.github.madmax3553.staticgroot.channels.ChannelConfig
import io.github.madmax3553.staticgroot.channels.ChannelMemberships
import io.github.madmax3553.staticgroot.channels.ChannelRuntime
import io.github.madmax3553.staticgroot.channels.DYNAMIC_CHANNEL_ID_BASE
import io.github.madmax3553.staticgroot.channels.GeneratedChannelStore
import io.github.madmax3553.staticgroot.channels.Lineup
import io.github.madmax3553.staticgroot.channels.ChannelPools
import io.github.madmax3553.staticgroot.channels.FilterClock
import io.github.madmax3553.staticgroot.channels.MediaEnrichment
import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.enrichment.EnrichmentService
import io.github.madmax3553.staticgroot.channels.isMusicVideo
import io.github.madmax3553.staticgroot.enrichment.DeezerClient
import io.github.madmax3553.staticgroot.enrichment.EnrichmentStore
import io.github.madmax3553.staticgroot.enrichment.MusicBrainzClient
import io.github.madmax3553.staticgroot.enrichment.MusicEnrichmentService
import io.github.madmax3553.staticgroot.enrichment.MusicEnrichmentStore
import io.github.madmax3553.staticgroot.enrichment.OmdbClient
import io.github.madmax3553.staticgroot.enrichment.TmdbClient
import io.github.madmax3553.staticgroot.jellyfin.AuthResult
import io.github.madmax3553.staticgroot.jellyfin.HttpUrlJellyfinHttp
import io.github.madmax3553.staticgroot.jellyfin.JellyfinClient
import io.github.madmax3553.staticgroot.jellyfin.JellyfinDiscovery
import io.github.madmax3553.staticgroot.jellyfin.JellyfinException
import io.github.madmax3553.staticgroot.jellyfin.JellyfinItem
import io.github.madmax3553.staticgroot.jellyfin.JellyfinMapping
import io.github.madmax3553.staticgroot.library.LibraryMaintenance
import io.github.madmax3553.staticgroot.library.LibraryPipeline
import java.io.File
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/*
 * Launch screen. With a saved session it loads the library and opens the guide, with no sign-in.
 * Without one, or when the server rejects the saved token, it shows sign-in (password or Quick
 * Connect). Plain views, not the final Leanback UI.
 */
class MainActivity : Activity() {

    private val http = HttpUrlJellyfinHttp()
    private lateinit var store: SessionStore
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    @Volatile private var quickConnectCancelled = false

    private val deviceId: String by lazy {
        val prefs = getSharedPreferences("staticgroot", MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(48), dp(48), dp(48))
            gravity = Gravity.TOP
        }
        setContentView(root)
        status = TextView(this).apply { textSize = 18f }

        val saved = store.load()
        if (saved != null) resume(saved) else showSignIn(null)
    }

    override fun onDestroy() {
        quickConnectCancelled = true
        super.onDestroy()
    }

    // MARK: - Resume with the saved session

    private fun resume(saved: SessionStore.Saved) {
        root.removeAllViews()
        root.addView(title("StaticGroot"))
        status = TextView(this).apply { textSize = 18f; text = "Loading your library..." }
        root.addView(status)
        Thread {
            try {
                val activeUrl = JellyfinDiscovery.discover(saved.serverUrl, BuildConfig.DEFAULT_JELLYFIN_URLS)?.url
                    ?: saved.serverUrl
                if (activeUrl != saved.serverUrl) {
                    Log.i(TAG, "DISCOVERY switched saved server URL to reachable address")
                    store.save(activeUrl, saved.accessToken, saved.userId)
                }
                val client = JellyfinClient(activeUrl, http, deviceId, saved.accessToken, saved.userId)
                loadLibrary(client)
            } catch (e: JellyfinException) {
                if (e.status == 401 || e.status == 403) {
                    store.clearSession()
                    runOnUiThread { showSignIn("Your session has ended. Please sign in again.") }
                } else {
                    runOnUiThread { showRetry(saved, e.message) }
                }
            } catch (e: Exception) {
                runOnUiThread { showRetry(saved, e.message) }
            }
        }.start()
    }

    /** The server did not answer. Offer a retry, or sign-in, rather than a dead end. */
    private fun showRetry(saved: SessionStore.Saved, reason: String?) {
        root.removeAllViews()
        root.addView(title("StaticGroot"))
        root.addView(TextView(this).apply {
            textSize = 18f
            text = "Could not reach your Jellyfin server at ${saved.serverUrl}.\n${reason.orEmpty()}"
            setPadding(0, 0, 0, dp(16))
        })
        root.addView(Button(this).apply {
            text = "Retry"
            setOnClickListener { resume(saved) }
        })
        root.addView(Button(this).apply {
            text = "Sign in again"
            setOnClickListener { showSignIn(null) }
        })
        status = TextView(this).apply { textSize = 16f }
        root.addView(status)
    }

    // MARK: - Sign-in screen

    private fun showSignIn(message: String?) {
        root.removeAllViews()
        root.addView(title("Sign in to Jellyfin"))
        val initialServer = store.lastServerUrl().ifBlank {
            JellyfinDiscovery.firstConfiguredUrl(BuildConfig.DEFAULT_JELLYFIN_URLS)
        }
        val server = field("Server address, e.g. http://jellyfin.example:8096").apply {
            setText(initialServer)
        }
        val user = field("Username").apply {
            setText(BuildConfig.DEFAULT_JELLYFIN_USER)
        }
        val password = field("Password", secret = true)
        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, dp(12), 0, 0)
            text = message ?: "Searching for your Jellyfin server…"
        }
        root.addView(server)
        root.addView(user)
        root.addView(password)
        root.addView(Button(this).apply {
            text = "Sign in with password"
            setOnClickListener {
                status.text = "Signing in..."
                val url = server.text.toString()
                val name = user.text.toString()
                val pass = password.text.toString()
                Thread { passwordSignIn(url, name, pass) }.start()
            }
        })
        root.addView(Button(this).apply {
            text = "Sign in with Quick Connect"
            setOnClickListener {
                status.text = "Starting Quick Connect..."
                val url = server.text.toString()
                Thread { quickConnect(url) }.start()
            }
        })
        root.addView(status)
        root.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(24), 0, 0)
            text = getString(R.string.about_credit)
        })
        Thread {
            val found = JellyfinDiscovery.discover(initialServer, BuildConfig.DEFAULT_JELLYFIN_URLS)
            if (found != null) {
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        val current = server.text.toString().trim()
                        if (current.isEmpty() || current == initialServer) {
                            server.setText(found.url)
                        }
                        if (message == null) {
                            status.text = "Found ${found.name} at ${found.url}"
                        }
                    }
                }
            } else if (message == null) {
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) status.text = ""
                }
            }
        }.start()
    }

    private fun passwordSignIn(url: String, name: String, pass: String) {
        try {
            val auth = JellyfinClient.authenticate(url, name, pass, http, deviceId)
            completeSignIn(url, auth)
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Shows the code, polls until the user approves it, then signs in. */
    private fun quickConnect(url: String) {
        try {
            quickConnectCancelled = false
            val start = JellyfinClient.quickConnectInitiate(url, http, deviceId)
            runOnUiThread {
                status.text = "Enter code ${start.code} in Jellyfin: Dashboard, then Quick Connect. Waiting..."
            }
            val deadline = System.currentTimeMillis() + QUICK_CONNECT_TIMEOUT_MS
            while (!quickConnectCancelled && System.currentTimeMillis() < deadline) {
                Thread.sleep(QUICK_CONNECT_POLL_MS)
                if (JellyfinClient.quickConnectCheck(url, http, deviceId, start.secret)) {
                    val auth = JellyfinClient.quickConnectAuthenticate(url, http, deviceId, start.secret)
                    completeSignIn(url, auth)
                    return
                }
            }
            if (!quickConnectCancelled) {
                runOnUiThread { status.text = "Quick Connect timed out. Try again." }
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Saves the session (encrypted), then loads the library and opens the guide. */
    private fun completeSignIn(url: String, auth: AuthResult) {
        val client = JellyfinClient(url, http, deviceId, auth.accessToken, auth.userId)
        store.save(client.serverUrl, auth.accessToken, auth.userId)
        loadLibrary(client)
    }

    // MARK: - Library

    /**
     * Loads every library, the movies and the TV episodes, and builds the pools for all channels,
     * then opens the guide. Logs what each library holds under the tag "StaticGroot", so an empty
     * result can be traced to the server's item types.
     */
    private fun loadLibrary(client: JellyfinClient) {
        val launchStartMs = System.currentTimeMillis()
        val force = intent.getBooleanExtra("force_regenerate", false)
        val stationId = runCatching { client.stationIdentity() }.getOrNull()
        val snapshotStore = io.github.madmax3553.staticgroot.schedule.LaunchSnapshotStore(File(filesDir, "launch_snapshot.json"))
        if (!force && stationId != null) {
            val snapshot = snapshotStore.load(stationId)
            if (snapshot != null && tryWarmLaunch(client, stationId, snapshot, snapshotStore, launchStartMs)) {
                return
            }
        }

        val loaded = pipeline.loadAll(client) { text -> runOnUiThread { status.text = text } }
        val items = loaded.items
        val commercials = loaded.commercials
        val musicStore = MusicEnrichmentStore(File(filesDir, "music_enrichment_cache.json"))
        Log.i(TAG, "MUSIC musicVideos=${items.count { it.isMusicVideo }}")

        val enrichment = enrichItems(items)

        runOnUiThread { status.text = "Building ${items.size} items into channels. This can take a while on a TV." }
        val t0 = System.currentTimeMillis()
        var config = ChannelConfigLoader.parse(assets.open("channels.json").bufferedReader().use { it.readText() })
        val companion = io.github.madmax3553.staticgroot.schedule.CompanionSettings(this)
        val companionUrl = if (companion.enabled) companion.resolveUrl(client.serverUrl) else ""
        var shared = if (companionUrl.isNotBlank() && stationId != null) runCatching {
            io.github.madmax3553.staticgroot.schedule.SharedStation(companionUrl, stationId)
        }.getOrNull() else null
        val displaySettings = DisplaySettings(this)
        var stationZone = displaySettings.resolveZoneId(ZoneId.of("America/Edmonton"))
        var disabledGenerated = emptySet<Int>()
        var serverGenerated: List<io.github.madmax3553.staticgroot.schedule.SharedChannel> = emptyList()
        var currentGeneration = 0
        if (shared != null) {
            runOnUiThread { status.text = "Reading shared channel setup…" }
            try {
                val setup = shared.setup(config.channels, stationZone.id)
                stationZone = ZoneId.of(setup.timeZone)
                currentGeneration = setup.generation
                // Generated channels (id from DYNAMIC_CHANNEL_ID_BASE) have no rules, so they are not
                // built here. They are rebuilt from the library and merged with serverGenerated below.
                config = config.copy(channels = setup.channels
                    .filter { it.enabled && it.id < DYNAMIC_CHANNEL_ID_BASE }.map { it.definition })
                disabledGenerated = setup.channels.filter { !it.enabled && it.id >= DYNAMIC_CHANNEL_ID_BASE }.map { it.id }.toSet()
                serverGenerated = setup.channels.filter { it.enabled && it.id >= DYNAMIC_CHANNEL_ID_BASE }
                Log.i(TAG, "SHARED connected url=$companionUrl zone=$stationZone channels=${setup.channels.size} gen=${setup.generation}")
            } catch (e: Exception) {
                Log.w(TAG, "SHARED schedule unavailable at $companionUrl (${e.message}), using local schedule")
                shared = null
            }
        }
        val memberships = ChannelMemberships.parse(
            assets.open("channels-memberships.json").bufferedReader().use { it.readText() },
        )
        val tParsed = System.currentTimeMillis()
        val clock = FilterClock(System.currentTimeMillis() / 1000, stationZone)
        val staticChannels = ChannelPools.buildAll(config, items, enrichment = enrichment, memberships = memberships, clock = clock)
        val channels = buildLineup(config, staticChannels, items, shared, stationZone, clock, disabledGenerated, serverGenerated)
        val tBuilt = System.currentTimeMillis()
        Log.i(
            TAG,
            "items=${items.size} channels=${channels.size} withItems=${channels.count { it.pool.isNotEmpty() }} " +
                "enriched=${enrichment.size} parseMs=${tParsed - t0} buildMs=${tBuilt - tParsed}",
        )
        if (BuildConfig.DEBUG && enrichment.isNotEmpty()) {
            // Measurement for the enrichment port: the same library with no enrichment at all.
            val bare = ChannelPools.buildAll(config, items, memberships = memberships, clock = clock)
            val gained = channels.count { it.pool.isNotEmpty() } - bare.count { it.pool.isNotEmpty() }
            val changed = channels.indices.count { channels[it].unrestrictedPool.size != bare[it].unrestrictedPool.size }
            Log.i(TAG, "ENRICHMENT delta: channelsWithItems +$gained, channelsWithDifferentPool=$changed of ${channels.size}")
        }

        AppSession.stationZone = stationZone
        AppSession.init(File(filesDir, "day_history"))
        AppSession.client = client
        AppSession.commercials = commercials
        AppSession.channels = channels
        AppSession.sharedStation = shared
        AppSession.lastChannelId = displaySettings.lastChannelId
        val publishedMap = HashMap<String, List<io.github.madmax3553.staticgroot.schedule.StoredBlock>>()
        if (shared != null) {
            runOnUiThread { status.text = "Loading published programs…" }
            val lookup = items.associateBy { it.id }
            val startDate = io.github.madmax3553.staticgroot.schedule.LocalDays.broadcastDate(clock.nowEpochSeconds, stationZone)
            val candidates = ArrayList<Triple<Int, String, List<io.github.madmax3553.staticgroot.schedule.Block>>>()
            val existingDays = HashMap<Pair<Int, String>, List<io.github.madmax3553.staticgroot.schedule.Block>>()
            if (!force) {
                val fetchPool = Executors.newFixedThreadPool(EPISODE_FETCH_THREADS)
                try {
                    val queries = (0L..2L).flatMap { offset ->
                        val dayKey = startDate.plusDays(offset).toString()
                        channels.map { ch -> Triple(ch, dayKey, fetchPool.submit<List<io.github.madmax3553.staticgroot.schedule.Block>?> {
                            shared.existing(ch, dayKey, lookup)?.takeIf { it.isNotEmpty() }
                        }) }
                    }
                    for ((ch, dayKey, future) in queries) {
                        val existing = future.get()
                        if (existing != null) existingDays[ch.id to dayKey] = existing
                    }
                } finally {
                    fetchPool.shutdownNow()
                }
            }
            for (offset in 0L..2L) {
                val date = startDate.plusDays(offset)
                val dayKey = date.toString()
                val start = io.github.madmax3553.staticgroot.schedule.LocalDays.dayStartFor(date, stationZone)
                val locked = HashSet<Int>()
                val day = channels.associate { ch ->
                    val existing = existingDays[ch.id to dayKey]
                    if (existing != null) locked.add(ch.id)
                    ch.id to (existing ?: AppSession.scheduler!!.dayBlocks(ch.id, ch.unrestrictedPool, ch.isPremiere, start + 3600))
                }
                // Premiere channels keep their anchor; existing published days are immutable.
                locked.addAll(channels.filter { it.isPremiere }.map { it.id })
                val optimized = if (locked.size >= channels.size) {
                    io.github.madmax3553.staticgroot.schedule.GenerationReshuffler.Result(day, 0)
                } else {
                    io.github.madmax3553.staticgroot.schedule.GenerationReshuffler.optimize(day, start, locked)
                }
                Log.i(TAG, "GENERATION overlap-reducing swaps=${optimized.swaps}")
                channels.forEach { ch -> candidates.add(Triple(ch.id, dayKey, optimized.days.getValue(ch.id))) }
            }
            if (force) {
                shared.regenerate(candidates.filter { it.third.isNotEmpty() })
                currentGeneration += 1
            }
            for ((id, dayKey, proposed) in candidates) {
                val ch = channels.first { it.id == id }
                val start = io.github.madmax3553.staticgroot.schedule.LocalDays.dayStartFor(java.time.LocalDate.parse(dayKey), stationZone)
                val alreadyPublished = existingDays[id to dayKey]
                val saved = when {
                    alreadyPublished != null -> alreadyPublished
                    proposed.isNotEmpty() -> shared.publish(ch, dayKey, proposed, lookup)
                    else -> emptyList()
                }
                if (saved.isNotEmpty()) {
                    AppSession.scheduler!!.installPublished(id, start, saved)
                    publishedMap["$id|$dayKey"] = shared.storedBlocks(saved)
                }
            }
            val remoteSelection = runCatching { shared.selection() }.getOrNull()
            val resolvedSelection = remoteSelection ?: displaySettings.lastChannelId
            AppSession.lastChannelId = resolvedSelection
            if (resolvedSelection != null) {
                displaySettings.lastChannelId = resolvedSelection
                if (remoteSelection == null) {
                    val activeShared = shared
                    Thread { runCatching { activeShared.select(resolvedSelection) } }.apply { isDaemon = true }.start()
                }
            }
            AppSession.scheduler!!.usePublishedOnly()
            val activeShared = shared
            Thread { runCatching { activeShared.publishCatalog(items) } }.apply { isDaemon = true; name = "publish-catalog" }.start()
        }
        if (stationId != null && channels.isNotEmpty() && items.isNotEmpty()) {
            val snap = io.github.madmax3553.staticgroot.schedule.LaunchSnapshot(
                stationId = stationId,
                generation = currentGeneration,
                timeZone = stationZone.id,
                savedAtEpochSeconds = System.currentTimeMillis() / 1000,
                lastChannelId = AppSession.lastChannelId,
                channels = channels.map { it.definition },
                items = items,
                commercials = commercials,
                publishedDays = publishedMap,
            )
            Thread { snapshotStore.save(snap) }.apply { isDaemon = true; name = "save-snapshot" }.start()
        }
        Log.i(TAG, "LAUNCH cold complete in ${System.currentTimeMillis() - launchStartMs}ms")
        startMusicEnrichment(items, musicStore)
        if (stationId != null && shared != null && items.isNotEmpty()) {
            LibraryMaintenance.attachFresh(applicationContext, client, stationId, snapshotStore, items, commercials, publishedMap)
        }
        runOnUiThread {
            startActivity(Intent(this, GuideActivity::class.java))
            finish()
        }
    }

    /**
     * Warm fast-launch path when a cached library snapshot and shared schedule service exist.
     * Resolves the last-watched channel and visible EPG neighborhood for today's window first,
     * opens [GuideActivity] immediately, and loads offscreen channels and future days in the background.
     */
    private fun tryWarmLaunch(
        client: JellyfinClient,
        stationId: String,
        snapshot: io.github.madmax3553.staticgroot.schedule.LaunchSnapshot,
        snapshotStore: io.github.madmax3553.staticgroot.schedule.LaunchSnapshotStore,
        launchStartMs: Long,
    ): Boolean {
        val companion = io.github.madmax3553.staticgroot.schedule.CompanionSettings(this)
        val companionUrl = if (companion.enabled) companion.resolveUrl(client.serverUrl) else ""
        if (companionUrl.isBlank()) return false
        return try {
            runOnUiThread { status.text = "Tuning to live broadcast…" }
            val shared = io.github.madmax3553.staticgroot.schedule.SharedStation(companionUrl, stationId)
            val displaySettings = DisplaySettings(this)
            val defaultZone = displaySettings.resolveZoneId(ZoneId.of(snapshot.timeZone))
            val config = ChannelConfigLoader.parse(assets.open("channels.json").bufferedReader().use { it.readText() })
            val setup = shared.setup(config.channels, defaultZone.id)
            val stationZone = ZoneId.of(setup.timeZone)
            val enabledById = setup.channels.filter { it.enabled }.associateBy { it.id }
            val activeDefs = snapshot.channels.mapNotNull { enabledById[it.id]?.definition }
                .ifEmpty { setup.channels.filter { it.enabled }.map { it.definition } }
                .sortedBy { it.number }
            if (activeDefs.isEmpty()) return false

            val remoteSelection = runCatching { shared.selection() }.getOrNull()
            val targetChannelId = remoteSelection
                ?: displaySettings.lastChannelId
                ?: snapshot.lastChannelId
                ?: activeDefs.first().id
            val lookup = snapshot.items.associateBy { it.id }
            val placeholderPool = listOf(snapshot.items.first())
            val runtimeChannels = activeDefs.map { ChannelRuntime(it, placeholderPool) }
            val targetIdx = runtimeChannels.indexOfFirst { it.id == targetChannelId }.coerceAtLeast(0)
            // Load the target channel and the visible EPG rows around it first.
            val visibleIndices = (-1..GuideNav.VISIBLE).map { Math.floorMod(targetIdx + it, runtimeChannels.size) }.distinct()
            val visibleChannels = visibleIndices.map { runtimeChannels[it] }

            val nowUnix = System.currentTimeMillis() / 1000
            val startDate = io.github.madmax3553.staticgroot.schedule.LocalDays.broadcastDate(nowUnix, stationZone)
            val sameGeneration = snapshot.generation == setup.generation

            AppSession.stationZone = stationZone
            AppSession.init(File(filesDir, "day_history"))
            val updatedPublished = HashMap(if (sameGeneration) snapshot.publishedDays else emptyMap())
            var foregroundInstalled = 0

            for (offset in 0L..1L) {
                val date = startDate.plusDays(offset)
                val dayKey = date.toString()
                val dayStart = io.github.madmax3553.staticgroot.schedule.LocalDays.dayStartFor(date, stationZone)
                for (ch in visibleChannels) {
                    val key = "${ch.id}|$dayKey"
                    val cachedStored = if (sameGeneration) updatedPublished[key] else null
                    val blocks = if (cachedStored != null) {
                        shared.resolve(io.github.madmax3553.staticgroot.schedule.SharedDay(cachedStored, setup.generation), lookup)
                    } else {
                        shared.existing(ch, dayKey, lookup)?.also { fetched ->
                            if (fetched.isNotEmpty()) updatedPublished[key] = shared.storedBlocks(fetched)
                        }
                    }
                    if (!blocks.isNullOrEmpty()) {
                        AppSession.scheduler!!.installPublished(ch.id, dayStart, blocks)
                        if (offset == 0L) foregroundInstalled++
                    }
                }
            }
            if (foregroundInstalled == 0) return false

            AppSession.scheduler!!.usePublishedOnly()
            AppSession.client = client
            AppSession.commercials = snapshot.commercials
            AppSession.channels = runtimeChannels
            AppSession.sharedStation = shared
            AppSession.lastChannelId = runtimeChannels[targetIdx].id
            displaySettings.lastChannelId = runtimeChannels[targetIdx].id
            if (remoteSelection == null) {
                val selectedId = runtimeChannels[targetIdx].id
                Thread { runCatching { shared.select(selectedId) } }.apply { isDaemon = true }.start()
            }

            val fastMs = System.currentTimeMillis() - launchStartMs
            Log.i(TAG, "LAUNCH warm fast-path ready in ${fastMs}ms (gen=${setup.generation} targetCh=${runtimeChannels[targetIdx].id} visibleLoaded=$foregroundInstalled)")
            LibraryMaintenance.attachWarm(
                applicationContext, client, stationId, snapshotStore,
                snapshot.items, snapshot.commercials, updatedPublished, snapshot.library,
            )
            runOnUiThread {
                startActivity(Intent(this, GuideActivity::class.java))
                finish()
            }

            Thread {
                completeWarmLaunchInBackground(
                    client = client,
                    stationId = stationId,
                    shared = shared,
                    setup = setup,
                    stationZone = stationZone,
                    startDate = startDate,
                    sameGeneration = sameGeneration,
                    runtimeChannels = runtimeChannels,
                    lookup = lookup,
                    snapshot = snapshot,
                    updatedPublished = updatedPublished,
                    snapshotStore = snapshotStore,
                )
            }.apply { isDaemon = true; name = "warm-offscreen-loader" }.start()
            true
        } catch (e: Exception) {
            Log.w(TAG, "LAUNCH warm fast-path fallback (${e.javaClass.simpleName}: ${e.message})")
            false
        }
    }

    private fun completeWarmLaunchInBackground(
        client: JellyfinClient,
        stationId: String,
        shared: io.github.madmax3553.staticgroot.schedule.SharedStation,
        setup: io.github.madmax3553.staticgroot.schedule.StationSetup,
        stationZone: ZoneId,
        startDate: java.time.LocalDate,
        sameGeneration: Boolean,
        runtimeChannels: List<ChannelRuntime>,
        lookup: Map<String, MediaItem>,
        snapshot: io.github.madmax3553.staticgroot.schedule.LaunchSnapshot,
        updatedPublished: HashMap<String, List<io.github.madmax3553.staticgroot.schedule.StoredBlock>>,
        snapshotStore: io.github.madmax3553.staticgroot.schedule.LaunchSnapshotStore,
    ) {
        val tBg = System.currentTimeMillis()
        var missingAnyDay = false
        val fetchPool = Executors.newFixedThreadPool(EPISODE_FETCH_THREADS)
        try {
            for (offset in 0L..2L) {
                val date = startDate.plusDays(offset)
                val dayKey = date.toString()
                val dayStart = io.github.madmax3553.staticgroot.schedule.LocalDays.dayStartFor(date, stationZone)
                val tasks = runtimeChannels.map { ch ->
                    val key = "${ch.id}|$dayKey"
                    ch to fetchPool.submit<Pair<List<io.github.madmax3553.staticgroot.schedule.Block>?, Boolean>> {
                        val cachedStored = synchronized(updatedPublished) {
                            if (sameGeneration) updatedPublished[key] else null
                        }
                        if (cachedStored != null) {
                            shared.resolve(io.github.madmax3553.staticgroot.schedule.SharedDay(cachedStored, setup.generation), lookup) to false
                        } else {
                            shared.existing(ch, dayKey, lookup) to true
                        }
                    }
                }
                var updatedThisDay = false
                for ((ch, future) in tasks) {
                    val (blocks, fromNetwork) = runCatching { future.get() }.getOrDefault(null to false)
                    if (!blocks.isNullOrEmpty()) {
                        AppSession.scheduler?.installPublished(ch.id, dayStart, blocks)
                        if (fromNetwork) {
                            synchronized(updatedPublished) {
                                updatedPublished["${ch.id}|$dayKey"] = shared.storedBlocks(blocks)
                            }
                        }
                        updatedThisDay = true
                    } else {
                        missingAnyDay = true
                    }
                }
                if (updatedThisDay) AppSession.notifySchedulesUpdated()
            }
        } finally {
            fetchPool.shutdownNow()
        }

        // A day that rolled over and is not published yet is built by LibraryMaintenance from the
        // freshest library, with the don't-repeat rules read from the published days.
        val finalChannels = runtimeChannels
        if (missingAnyDay) LibraryMaintenance.requestSoon()

        val activeDayKeys = (0L..2L).map { startDate.plusDays(it).toString() }.toSet()
        val prunedPublished = synchronized(updatedPublished) {
            updatedPublished.filterKeys { key -> key.substringAfter('|') in activeDayKeys }
        }
        // When a day is missing, LibraryMaintenance is already running and saves the snapshot itself;
        // saving the launch-time copy here could overwrite what it has just updated.
        if (!missingAnyDay) {
            snapshotStore.save(
                snapshot.copy(
                    generation = setup.generation,
                    timeZone = stationZone.id,
                    savedAtEpochSeconds = System.currentTimeMillis() / 1000,
                    lastChannelId = AppSession.lastChannelId,
                    channels = finalChannels.map { it.definition },
                    publishedDays = prunedPublished,
                )
            )
        }
        runCatching { shared.publishCatalog(snapshot.items) }
        val musicStore = MusicEnrichmentStore(File(filesDir, "music_enrichment_cache.json"))
        startMusicEnrichment(snapshot.items, musicStore)
        Log.i(TAG, "LAUNCH warm offscreen background complete in ${System.currentTimeMillis() - tBg}ms")
    }

    /**
     * MusicBrainz (one request a second) and Deezer for music-video genres, artists and years.
     * One daemon thread for the process, so a second sign-in does not start a second pass. It stops
     * when the process does; progress is saved as it goes and picked up on the next launch.
     */
    private fun startMusicEnrichment(items: List<MediaItem>, store: MusicEnrichmentStore) {
        if (items.none { it.isMusicVideo } || !musicPassRunning.compareAndSet(false, true)) return
        val http = HttpUrlJellyfinHttp(readTimeoutMs = 20_000)
        val agent = "StaticGroot/${BuildConfig.VERSION_NAME} (https://github.com/madmax3553/NostalgexAPK)"
        val service = MusicEnrichmentService(
            musicBrainz = MusicBrainzClient(http, agent),
            deezer = DeezerClient(http, agent),
            store = store,
            log = { Log.i(TAG, "MUSIC $it") },
        )
        Thread {
            try {
                service.enrich(items)
            } catch (e: Exception) {
                Log.w(TAG, "MUSIC enrichment stopped (${e.javaClass.simpleName})")
            } finally {
                musicPassRunning.set(false)
            }
        }.apply { isDaemon = true; name = "music-enrichment"; priority = Thread.MIN_PRIORITY }.start()
    }

    private val pipeline by lazy { LibraryPipeline(applicationContext) }

    private fun buildLineup(
        config: ChannelConfig, static: List<ChannelRuntime>, items: List<MediaItem>,
        shared: io.github.madmax3553.staticgroot.schedule.SharedStation?, zone: ZoneId, clock: FilterClock,
        disabled: Set<Int>, serverGenerated: List<io.github.madmax3553.staticgroot.schedule.SharedChannel>,
    ): List<ChannelRuntime> = pipeline.buildLineup(config, static, items, shared, zone, clock, disabled, serverGenerated)

    private fun enrichItems(items: List<MediaItem>): Map<String, MediaEnrichment> =
        pipeline.enrich(items) { text -> runOnUiThread { status.text = text } }

    private fun fail(e: Exception) {
        Log.w(TAG, "sign-in or load failed", e)
        runOnUiThread { status.text = e.message ?: "Sign-in failed" }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 28f
        setPadding(0, 0, 0, dp(16))
    }

    private fun field(hint: String, secret: Boolean = false) = EditText(this).apply {
        this.hint = hint
        textSize = 18f
        isSingleLine = true
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    companion object {
        private const val TAG = "StaticGroot"
        private const val QUICK_CONNECT_POLL_MS = 2_000L
        private const val QUICK_CONNECT_TIMEOUT_MS = 5 * 60_000L
        private const val EPISODE_FETCH_THREADS = 6
        private val musicPassRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
