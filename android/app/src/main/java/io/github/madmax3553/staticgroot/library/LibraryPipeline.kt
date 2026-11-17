package io.github.madmax3553.staticgroot.library

import android.content.Context
import android.util.Log
import io.github.madmax3553.staticgroot.BuildConfig
import io.github.madmax3553.staticgroot.channels.ChannelConfig
import io.github.madmax3553.staticgroot.channels.ChannelConfigLoader
import io.github.madmax3553.staticgroot.channels.ChannelMemberships
import io.github.madmax3553.staticgroot.channels.ChannelPools
import io.github.madmax3553.staticgroot.channels.ChannelRuntime
import io.github.madmax3553.staticgroot.channels.DYNAMIC_CHANNEL_ID_BASE
import io.github.madmax3553.staticgroot.channels.FilterClock
import io.github.madmax3553.staticgroot.channels.GeneratedChannelStore
import io.github.madmax3553.staticgroot.channels.LibrarySource
import io.github.madmax3553.staticgroot.channels.Lineup
import io.github.madmax3553.staticgroot.channels.MediaEnrichment
import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.enrichment.EnrichmentService
import io.github.madmax3553.staticgroot.enrichment.EnrichmentStore
import io.github.madmax3553.staticgroot.enrichment.MusicEnrichmentService
import io.github.madmax3553.staticgroot.enrichment.MusicEnrichmentStore
import io.github.madmax3553.staticgroot.enrichment.OmdbClient
import io.github.madmax3553.staticgroot.enrichment.TmdbClient
import io.github.madmax3553.staticgroot.jellyfin.CommercialClip
import io.github.madmax3553.staticgroot.jellyfin.CommercialClips
import io.github.madmax3553.staticgroot.jellyfin.HttpUrlJellyfinHttp
import io.github.madmax3553.staticgroot.jellyfin.JellyfinClient
import io.github.madmax3553.staticgroot.jellyfin.JellyfinItem
import io.github.madmax3553.staticgroot.jellyfin.JellyfinMapping
import io.github.madmax3553.staticgroot.schedule.SharedChannel
import io.github.madmax3553.staticgroot.schedule.SharedStation
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * The steps that turn a Jellyfin library into the guide's channels: load, enrich, build pools,
 * build the lineup. They used to live inside MainActivity, tied to its screen. The launch
 * screen and the background refresher now share this one copy, so a reload mid-session builds
 * channels exactly the way a cold launch does.
 *
 * Blocking calls. Run them on a background thread. [onStatus] receives short progress text
 * and may be ignored.
 */
class LibraryPipeline(private val ctx: Context) {

    class Loaded(val items: List<MediaItem>, val commercials: List<CommercialClip>)

    class Built(val channels: List<ChannelRuntime>, val setupVersion: Int, val zone: ZoneId)

    /** Every library, from scratch. */
    fun loadAll(client: JellyfinClient, onStatus: (String) -> Unit = {}): Loaded {
        onStatus("Loading libraries...")
        val items = ArrayList<MediaItem>()
        val commercials = ArrayList<CommercialClip>()
        val sections = client.loadSections()
        Log.i(TAG, "sections=${sections.size}")
        for (section in sections) {
            if (CommercialClips.isLibrary(section.title)) {
                client.loadItems(section.id, "Movie,Video,MusicVideo").mapNotNullTo(commercials) {
                    CommercialClips.fromItem(it)
                }
                continue
            }
            Log.i(TAG, "section '${section.title}' collectionType=${section.collectionType} type=${section.type}")
            if (section.type == "show") {
                val series = client.loadSeries(section.id)
                val episodes = loadEpisodes(client, series)
                items.addAll(episodes)
                Log.i(TAG, "  series=${series.size} episodes loaded=${episodes.size}")
                continue
            }
            val isMusic = section.type == "musicvideo"
            val musicVideoCount = if (isMusic) client.countItems(section.id, "MusicVideo") else 0
            Log.i(TAG, "  isMusic=$isMusic MusicVideo=$musicVideoCount")
            val raw = client.loadMovies(section.id, includeItemTypes = JellyfinMapping.itemTypesFor(isMusic, musicVideoCount))
            val mapped = raw.mapNotNull { JellyfinMapping.toMediaItem(it, isMusicSection = isMusic) }
            Log.i(TAG, "  loaded raw=${raw.size} mapped=${mapped.size} (no runtime dropped: ${raw.size - mapped.size})")
            items.addAll(mapped)
        }
        applyMusicCache(items)
        return Loaded(items, commercials)
    }

    /**
     * Only what Jellyfin saved since [sinceIso], merged into [previous]. A series that changed, or
     * that has a changed episode, is reloaded whole, because its title, year and TMDB id come from
     * the series and every episode carries them. Returns the merged list.
     *
     * It cannot see a deletion; the caller falls back to [loadAll] when item counts fall.
     */
    fun loadChanged(client: JellyfinClient, sinceIso: String, previous: List<MediaItem>): List<MediaItem> {
        val merged = LinkedHashMap<String, MediaItem>(previous.size * 2)
        previous.forEach { merged[it.id] = it }
        for (section in client.loadSections()) {
            if (CommercialClips.isLibrary(section.title)) continue
            if (section.type == "show") {
                val changedSeries = client.loadSeries(section.id, sinceIso)
                val changedEpisodes = client.loadItems(section.id, "Episode", sinceIso)
                val seriesIds = (changedSeries.map { it.Id } + changedEpisodes.mapNotNull { it.SeriesId }).distinct()
                if (seriesIds.isEmpty()) continue
                val shows = client.loadSeries(section.id).associateBy { it.Id }
                val reload = seriesIds.mapNotNull { shows[it] }
                val episodes = loadEpisodes(client, reload)
                val reloaded = reload.map { it.Id }.toSet()
                merged.values.removeAll { it.seriesId in reloaded }
                episodes.forEach { merged[it.id] = it }
                Log.i(TAG, "LIBRARY incremental series reloaded=${reload.size} episodes=${episodes.size}")
                continue
            }
            val isMusic = section.type == "musicvideo"
            val musicVideoCount = if (isMusic) client.countItems(section.id, "MusicVideo") else 0
            val raw = client.loadMovies(section.id, JellyfinMapping.itemTypesFor(isMusic, musicVideoCount), sinceIso)
            val mapped = raw.mapNotNull { JellyfinMapping.toMediaItem(it, isMusicSection = isMusic) }
            mapped.forEach { merged[it.id] = it }
            Log.i(TAG, "LIBRARY incremental movies changed=${mapped.size}")
        }
        val out = ArrayList(merged.values)
        applyMusicCache(out)
        return out
    }

    /** Music genres, artists and years resolved on earlier launches. */
    private fun applyMusicCache(items: MutableList<MediaItem>) {
        val cached = MusicEnrichmentStore(File(ctx.filesDir, "music_enrichment_cache.json")).load()
        if (cached.isNotEmpty()) {
            for (i in items.indices) items[i] = MusicEnrichmentService.apply(listOf(items[i]), cached).first()
        }
        Log.i(TAG, "MUSIC cached rows=${cached.size}")
    }

    /**
     * A short hash of what Jellyfin told us, the same on every TV that loaded the same library.
     * Left out on purpose: anything this TV derives locally (music years and genres from the
     * MusicBrainz cache, enrichment), which would make two TVs disagree forever.
     */
    fun contentStamp(items: List<MediaItem>): String {
        val md = MessageDigest.getInstance("SHA-256")
        items.map {
            if (it.librarySource == LibrarySource.MUSIC_VIDEO) "${it.id}|${it.title}"
            else "${it.id}|${it.title}|${it.seTag.orEmpty()}|${it.episodeTitle.orEmpty()}|${it.year ?: ""}|${it.tmdbID.orEmpty()}|${it.duration}"
        }.sorted().forEach { md.update(it.toByteArray()); md.update('\n'.code.toByte()) }
        return md.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    /** Episodes for many series at once. Six requests in flight, as a simple cap. */
    fun loadEpisodes(client: JellyfinClient, series: List<JellyfinItem>): List<MediaItem> {
        val pool = Executors.newFixedThreadPool(EPISODE_FETCH_THREADS)
        try {
            val futures = series.map { show ->
                pool.submit<List<MediaItem>> {
                    client.loadEpisodes(show.Id).mapNotNull { JellyfinMapping.toEpisode(it, show) }
                }
            }
            return futures.flatMap { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * TMDB and OMDb metadata for the rule filter, from the cache and the network. Never throws:
     * a channel lineup built without enrichment is worse than one built with it, but a launch
     * that cannot finish is worse than both. Skipped without a TMDB key, as on tvOS.
     */
    fun enrich(items: List<MediaItem>, onStatus: (String) -> Unit = {}): Map<String, MediaEnrichment> = try {
        val tmdbKey = BuildConfig.TMDB_API_KEY
        val omdbKey = BuildConfig.OMDB_API_KEY
        val enrichHttp = HttpUrlJellyfinHttp(readTimeoutMs = 15_000)
        val service = EnrichmentService(
            tmdb = if (tmdbKey.isNotBlank()) TmdbClient(enrichHttp, tmdbKey) else null,
            omdb = if (omdbKey.isNotBlank()) OmdbClient(enrichHttp, omdbKey) else null,
            store = EnrichmentStore(File(ctx.filesDir, "enrichment_cache.json")),
            log = { Log.i(TAG, "ENRICHMENT $it") },
        )
        if (tmdbKey.isBlank()) Log.i(TAG, "ENRICHMENT no TMDB key configured, using cache only")
        onStatus("Reading metadata cache…")
        service.enrich(items) { done, total -> onStatus("Fetching metadata $done/$total") }.records
    } catch (e: Exception) {
        Log.w(TAG, "ENRICHMENT failed, building channels without it", e)
        emptyMap()
    }

    /**
     * Channels for the current library: reads the shared setup (which channels are on, the station
     * zone), builds every pool, then the lineup. What a cold launch does between loading the
     * library and publishing days.
     */
    fun buildChannels(
        items: List<MediaItem>,
        shared: SharedStation?,
        zone: ZoneId,
        onStatus: (String) -> Unit = {},
    ): Built {
        val enrichment = enrich(items, onStatus)
        var config = ChannelConfigLoader.parse(ctx.assets.open("channels.json").bufferedReader().use { it.readText() })
        var stationZone = zone
        var disabled = emptySet<Int>()
        var serverGenerated: List<SharedChannel> = emptyList()
        var setupVersion = 0
        if (shared != null) {
            val setup = shared.setup(config.channels, stationZone.id)
            stationZone = ZoneId.of(setup.timeZone)
            setupVersion = setup.version
            config = config.copy(channels = setup.channels
                .filter { it.enabled && it.id < DYNAMIC_CHANNEL_ID_BASE }.map { it.definition })
            disabled = setup.channels.filter { !it.enabled && it.id >= DYNAMIC_CHANNEL_ID_BASE }.map { it.id }.toSet()
            serverGenerated = setup.channels.filter { it.enabled && it.id >= DYNAMIC_CHANNEL_ID_BASE }
        }
        val memberships = ChannelMemberships.parse(
            ctx.assets.open("channels-memberships.json").bufferedReader().use { it.readText() },
        )
        val clock = FilterClock(System.currentTimeMillis() / 1000, stationZone)
        val static = ChannelPools.buildAll(config, items, enrichment = enrichment, memberships = memberships, clock = clock)
        val channels = buildLineup(config, static, items, shared, stationZone, clock, disabled, serverGenerated)
        return Built(channels, setupVersion, stationZone)
    }

    /**
     * The guide's channels: static ones less the seasonal bundles, the one in-season channel, and
     * channels derived from the library (see [Lineup]). With a shared station the generated ones
     * are registered on the service first, since it refuses days for channels it does not know;
     * if it cannot register them they are left out rather than failing the launch.
     */
    fun buildLineup(
        config: ChannelConfig, static: List<ChannelRuntime>, items: List<MediaItem>,
        shared: SharedStation?, zone: ZoneId, clock: FilterClock,
        disabled: Set<Int>, serverGenerated: List<SharedChannel>,
    ): List<ChannelRuntime> {
        val store = GeneratedChannelStore(File(ctx.filesDir, "generated_channels.json"))
        val lineup = try {
            Lineup.build(config, static, items, store.load(), java.time.Instant.ofEpochSecond(clock.nowEpochSeconds).atZone(zone).toLocalDate())
        } catch (e: Exception) {
            Log.w(TAG, "LINEUP failed (${e.javaClass.simpleName}), using the static channels", e)
            return static
        }
        val generatedIds = lineup.generated.map { it.definition.id }.toSet()
        var channels = lineup.channels
        var records = lineup.records
        if (shared != null && (lineup.generated.isNotEmpty() || serverGenerated.isNotEmpty())) {
            try {
                val saved = if (lineup.generated.isNotEmpty()) {
                    shared.addChannels(lineup.generated.map { it.definition }).channels.associateBy { it.id }
                } else {
                    serverGenerated.associateBy { it.id }
                }
                channels = channels.mapNotNull { rt ->
                    if (rt.id !in generatedIds) rt
                    else saved[rt.id]?.takeIf { it.enabled }?.let { ChannelRuntime(it.definition, rt.pool) }
                }
                val presentIds = channels.map { it.id }.toSet()
                val remoteOnly = saved.values
                    .filter { it.enabled && it.id >= DYNAMIC_CHANNEL_ID_BASE && it.id !in presentIds }
                    .map { ChannelRuntime(it.definition, emptyList()) }
                if (remoteOnly.isNotEmpty()) {
                    channels = (channels + remoteOnly).sortedBy { it.number }
                }
                records = records.map { r -> saved[r.id]?.let { r.copy(number = it.definition.number, name = it.name) } ?: r }
            } catch (e: Exception) {
                // An older service cannot take generated channels. Fall back to the original lineup,
                // less any seasonal bundle that is out of season, so in-season channels (already
                // known to the service) are not lost along with the generated ones.
                Log.w(TAG, "LINEUP could not register generated channels (${e.message}), using the static lineup")
                val inSeason = java.time.Instant.ofEpochSecond(clock.nowEpochSeconds).atZone(zone).toLocalDate()
                val offSeason = config.bundles.filter {
                    it.activeMonths != null && !io.github.madmax3553.staticgroot.channels.SeasonalChannels.isInSeason(it.activeMonths, inSeason)
                }.flatMap { it.channelIDs }.toSet()
                channels = static.filter { it.id !in offSeason }
                records = emptyList()
            }
        } else if (disabled.isNotEmpty()) {
            channels = channels.filter { it.id !in disabled }
        }
        runCatching { store.save(records) }
        Log.i(TAG, "LINEUP static=${static.size} shown=${channels.size} generated=" +
            lineup.generated.joinToString { "${it.spec.kind}:${it.definition.name}(${it.pool.size})" })
        return channels
    }

    companion object {
        private const val TAG = "StaticGroot"
        private const val EPISODE_FETCH_THREADS = 6
    }
}
