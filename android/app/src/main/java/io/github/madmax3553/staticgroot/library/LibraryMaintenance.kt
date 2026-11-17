package io.github.madmax3553.staticgroot.library

import android.content.Context
import android.util.Log
import io.github.madmax3553.staticgroot.AppSession
import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.jellyfin.CommercialClip
import io.github.madmax3553.staticgroot.jellyfin.JellyfinClient
import io.github.madmax3553.staticgroot.jellyfin.JellyfinException
import io.github.madmax3553.staticgroot.schedule.AiredHistory
import io.github.madmax3553.staticgroot.schedule.Block
import io.github.madmax3553.staticgroot.schedule.ChannelScheduler
import io.github.madmax3553.staticgroot.schedule.GenerationReshuffler
import io.github.madmax3553.staticgroot.schedule.LaunchSnapshot
import io.github.madmax3553.staticgroot.schedule.LaunchSnapshotStore
import io.github.madmax3553.staticgroot.schedule.LibraryMeta
import io.github.madmax3553.staticgroot.schedule.LibraryState
import io.github.madmax3553.staticgroot.schedule.LocalDays
import io.github.madmax3553.staticgroot.schedule.SharedDay
import io.github.madmax3553.staticgroot.schedule.StoredBlock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps a running TV in step with Jellyfin and with the other TVs, without a restart.
 *
 * A warm launch opens the guide from a saved snapshot and never asks Jellyfin for the library,
 * so edits and additions would never arrive. This object runs after launch and does three things
 * on a slow beat, all on one low-priority thread:
 *
 *  1. Poll the shared service. Its library revision says whether another TV saw a change.
 *  2. Reload from Jellyfin when something changed: a cheap count-and-changed-since check first,
 *     then only the changed series and movies, or the whole library when that is not enough
 *     (a deletion, a first run, or three days since the last full load).
 *  3. Keep [HORIZON_DAYS] broadcast days published. After each 03:00 rollover the next day is
 *     built from the freshest library and published, so the schedule extends itself and new
 *     titles join it a day at a time. Published days are never changed.
 *
 * Titles on days that are already published update at once, because a block stores only an item
 * id and the title is looked up when the guide reads it.
 *
 * The expensive step, building every channel's pool, runs only when a reload or a missing day
 * needs it, never on a plain tick. A warm launch starts with placeholder pools.
 */
object LibraryMaintenance {

    private const val TAG = "StaticGroot"
    const val HORIZON_DAYS = 3
    private const val FIRST_DELAY_S = 90L
    private const val TICK_S = 15 * 60L
    private const val PROBE_EVERY_S = 60 * 60L
    private const val FULL_RELOAD_S = 3 * 24 * 3600L
    private const val PROBE_OVERLAP_S = 600L

    private class State(
        val ctx: Context,
        val client: JellyfinClient,
        val stationId: String,
        val store: LaunchSnapshotStore,
        var items: List<MediaItem>,
        var commercials: List<CommercialClip>,
        val published: HashMap<String, List<StoredBlock>>,
        var meta: LibraryMeta,
        var poolsReady: Boolean,
        /** True when [items] came straight from Jellyfin at [loadedAtSec] and nothing has verified them since. */
        var justLoaded: Boolean,
        val loadedAtSec: Long,
    ) {
        var lookup: Map<String, MediaItem> = items.associateBy { it.id }
        var serverLib: LibraryState? = null
        var lastProbeAt = 0L
        var forceFull = false
        /** Channel-day keys that built to nothing, so a tick does not rebuild them forever. */
        val noProgram = HashSet<String>()
        var halted = false
    }

    @Volatile private var state: State? = null
    private var future: ScheduledFuture<*>? = null
    private val executor by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "library-maintenance").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }

    /** After a cold launch: the items came from Jellyfin moments ago and the channels have real pools. */
    @Synchronized fun attachFresh(
        ctx: Context, client: JellyfinClient, stationId: String, store: LaunchSnapshotStore,
        items: List<MediaItem>, commercials: List<CommercialClip>, published: HashMap<String, List<StoredBlock>>,
    ) = attach(State(ctx.applicationContext, client, stationId, store, items, commercials, published,
        LibraryMeta(fullLoadAt = nowSec()), poolsReady = true, justLoaded = true, loadedAtSec = nowSec()))

    /** After a warm launch: the items are the saved snapshot's, and the channels have placeholder pools. */
    @Synchronized fun attachWarm(
        ctx: Context, client: JellyfinClient, stationId: String, store: LaunchSnapshotStore,
        items: List<MediaItem>, commercials: List<CommercialClip>, published: HashMap<String, List<StoredBlock>>,
        meta: LibraryMeta,
    ) = attach(State(ctx.applicationContext, client, stationId, store, items, commercials, published,
        meta, poolsReady = false, justLoaded = false, loadedAtSec = 0L))

    private fun attach(s: State) {
        if (AppSession.sharedStation == null) return
        state = s
        if (future == null) future = executor.scheduleWithFixedDelay({ tick() }, FIRST_DELAY_S, TICK_S, TimeUnit.SECONDS)
    }

    /** Runs a pass now, off the caller's thread. For a launch that finds a day missing. */
    fun requestSoon() {
        if (state != null) executor.execute { tick() }
    }

    private fun pipeline(s: State) = LibraryPipeline(s.ctx)

    @Synchronized private fun tick() {
        val s = state ?: return
        if (s.halted) return
        val started = System.currentTimeMillis()
        for ((name, step) in listOf<Pair<String, (State) -> Unit>>("poll" to ::poll, "refresh" to ::refresh, "horizon" to ::keepHorizon)) {
            try {
                step(s)
            } catch (e: Exception) {
                Log.w(TAG, "LIBRARY $name failed (${e.javaClass.simpleName}: ${e.message})")
            }
        }
        Log.i(TAG, "LIBRARY tick done in ${System.currentTimeMillis() - started}ms items=${s.items.size} poolsReady=${s.poolsReady}")
    }

    // MARK: - 1. The shared service

    private fun poll(s: State) {
        val shared = AppSession.sharedStation ?: return
        val lib = try {
            shared.library()
        } catch (e: JellyfinException) {
            s.serverLib = null // an older service has no library route; carry on as before
            shared.libraryRevision = null
            return
        }
        s.serverLib = lib
        if (s.meta.stamp.isNotEmpty() && lib.stamp == s.meta.stamp && lib.revision != s.meta.revision) {
            s.meta = s.meta.copy(revision = lib.revision) // agree on content: take the service's revision
        }
        shared.libraryRevision = s.meta.revision
    }

    // MARK: - 2. The library

    private enum class Mode { NONE, INCREMENTAL, FULL }

    private fun refresh(s: State) {
        val pipeline = pipeline(s)
        val now = nowSec()
        if (s.meta.stamp.isEmpty()) s.meta = s.meta.copy(stamp = pipeline.contentStamp(s.items))

        if (s.justLoaded) {
            // Items came from Jellyfin at launch. Record what the library looked like, and tell the service.
            val probe = s.client.probeLibrary(null)
            s.meta = s.meta.copy(counts = probe.counts, probeAtIso = iso(s.loadedAtSec - PROBE_OVERLAP_S))
            s.justLoaded = false
            s.lastProbeAt = now
            announce(s, s.meta.stamp, s.items.size)
            saveSnapshot(s)
            return
        }

        val server = s.serverLib
        val serverDiffers = server != null && server.stamp.isNotEmpty() && server.stamp != s.meta.stamp
        val probeDue = now - s.lastProbeAt >= PROBE_EVERY_S
        if (!probeDue && !serverDiffers && !s.forceFull && s.meta.fullLoadAt != 0L) return

        val sinceIso = s.meta.probeAtIso.ifEmpty { null }
        val probeIso = iso(now - PROBE_OVERLAP_S)
        val probe = s.client.probeLibrary(sinceIso)
        s.lastProbeAt = now

        val old = s.meta.counts
        val decreased = old.isNotEmpty() && probe.counts.any { (k, v) -> v < (old[k] ?: 0) }
        val newLibrary = old.isNotEmpty() && probe.counts.keys.any { it !in old }
        val grew = old.isNotEmpty() && probe.counts.any { (k, v) -> v > (old[k] ?: v) }
        val fullDue = s.forceFull || s.meta.fullLoadAt == 0L || now - s.meta.fullLoadAt > FULL_RELOAD_S ||
            sinceIso == null || old.isEmpty()
        val mode = when {
            fullDue || decreased || newLibrary -> Mode.FULL
            probe.changed > 0 || grew -> Mode.INCREMENTAL
            serverDiffers -> Mode.FULL // another TV saw a change this check could not find
            else -> Mode.NONE
        }
        Log.i(TAG, "LIBRARY check mode=$mode changed=${probe.changed} grew=$grew decreased=$decreased " +
            "serverDiffers=$serverDiffers full=$fullDue")

        if (mode == Mode.NONE) {
            s.meta = s.meta.copy(counts = probe.counts, probeAtIso = probeIso)
            saveSnapshot(s)
            return
        }

        var full = mode == Mode.FULL
        var items: List<MediaItem>
        var commercials = s.commercials
        if (!full) {
            items = try {
                pipeline.loadChanged(s.client, sinceIso!!, s.items)
            } catch (e: Exception) {
                Log.w(TAG, "LIBRARY incremental failed (${e.message}), loading everything")
                full = true
                s.items
            }
        } else items = s.items
        if (full) {
            val loaded = pipeline.loadAll(s.client)
            items = loaded.items
            commercials = loaded.commercials
        }
        s.forceFull = false
        applyItems(s, items, commercials, probe.counts, probeIso, full)
    }

    /**
     * Swaps a new library in: rebuilds every channel from it, re-reads the published days so their
     * titles come from it, tells the service, saves the snapshot and asks the guide to redraw.
     * Also used with the unchanged items to turn a warm launch's placeholder pools into real ones.
     */
    private fun applyItems(
        s: State, items: List<MediaItem>, commercials: List<CommercialClip>,
        counts: Map<String, Int>, probeIso: String, full: Boolean,
    ) {
        val shared = AppSession.sharedStation ?: return
        val started = System.currentTimeMillis()
        val built = pipeline(s).buildChannels(items, shared, AppSession.stationZone)
        val lookup = items.associateBy { it.id }
        val generation = shared.generationNow
        val snapshot = synchronized(s.published) { HashMap(s.published) }
        for ((key, stored) in snapshot) {
            val channelId = key.substringBefore('|').toIntOrNull() ?: continue
            val date = LocalDate.parse(key.substringAfter('|'))
            val blocks = shared.resolve(SharedDay(stored, generation), lookup)
            AppSession.scheduler?.installPublished(channelId, LocalDays.dayStartFor(date, AppSession.stationZone), blocks)
        }
        val stamp = pipeline(s).contentStamp(items)
        s.items = items
        s.lookup = lookup
        s.commercials = commercials
        s.poolsReady = true
        s.meta = s.meta.copy(
            stamp = stamp, counts = counts, probeAtIso = probeIso, setupVersion = built.setupVersion,
            fullLoadAt = if (full) nowSec() else s.meta.fullLoadAt,
        )
        AppSession.channels = built.channels
        if (full) AppSession.commercials = commercials
        announce(s, stamp, items.size)
        runCatching { shared.publishCatalog(items) }
        saveSnapshot(s)
        AppSession.notifySchedulesUpdated()
        Log.i(TAG, "LIBRARY applied items=${items.size} channels=${built.channels.size} full=$full " +
            "in ${System.currentTimeMillis() - started}ms stamp=$stamp revision=${s.meta.revision}")
    }

    /** Tells the service what this TV has, or takes its revision when the content already matches. */
    private fun announce(s: State, stamp: String, count: Int) {
        val shared = AppSession.sharedStation ?: return
        val lib = try {
            val current = shared.library()
            if (current.stamp == stamp) current else shared.postLibrary(stamp, count)
        } catch (e: JellyfinException) {
            shared.libraryRevision = null // service without a library route
            return
        }
        s.serverLib = lib
        s.meta = s.meta.copy(revision = lib.revision)
        shared.libraryRevision = lib.revision
    }

    // MARK: - 3. The horizon

    private fun keepHorizon(s: State) {
        val shared = AppSession.sharedStation ?: return
        val scheduler = AppSession.scheduler ?: return
        val zone = AppSession.stationZone
        // A TV that is behind on the library must not lock in a new day. It reloads first.
        val server = s.serverLib
        if (server != null && server.stamp.isNotEmpty() && server.stamp != s.meta.stamp) return

        val today = LocalDays.broadcastDate(nowSec(), zone)
        val dates = (0 until HORIZON_DAYS).map { today.plusDays(it.toLong()) }
        fun key(channelId: Int, date: LocalDate) = "$channelId|$date"
        fun published(channelId: Int, date: LocalDate) = synchronized(s.published) { s.published[key(channelId, date)] }
        fun install(channelId: Int, date: LocalDate, blocks: List<Block>) {
            scheduler.installPublished(channelId, LocalDays.dayStartFor(date, zone), blocks)
            synchronized(s.published) { s.published[key(channelId, date)] = shared.storedBlocks(blocks) }
        }

        // What is missing, after taking what other TVs already published.
        val missing = LinkedHashMap<LocalDate, MutableList<Int>>()
        for (channel in AppSession.channels) for (date in dates) {
            if (published(channel.id, date) != null || key(channel.id, date) in s.noProgram) continue
            val theirs = try { shared.existing(channel, date.toString(), s.lookup) } catch (e: Exception) { null }
            if (!theirs.isNullOrEmpty()) install(channel.id, date, theirs)
            else missing.getOrPut(date) { ArrayList() }.add(channel.id)
        }
        if (missing.isEmpty()) return

        if (!s.poolsReady) applyItems(s, s.items, s.commercials, s.meta.counts, s.meta.probeAtIso, full = false)
        val channels = AppSession.channels
        var built = 0
        for ((date, ids) in missing.toSortedMap()) {
            val dayStart = LocalDays.dayStartFor(date, zone)
            val planner = ChannelScheduler(zone, PublishedHistory(s.published))
            val day = LinkedHashMap<Int, List<Block>>()
            val locked = HashSet<Int>()
            val toPublish = ArrayList<Int>()
            for (channel in channels) {
                val stored = published(channel.id, date)
                if (stored != null) {
                    day[channel.id] = shared.resolve(SharedDay(stored, shared.generationNow), s.lookup)
                    locked.add(channel.id)
                } else if (key(channel.id, date) in s.noProgram) {
                    day[channel.id] = emptyList()
                    locked.add(channel.id)
                } else {
                    day[channel.id] = planner.dayBlocks(channel.id, channel.unrestrictedPool, channel.isPremiere, dayStart + 3600)
                    toPublish.add(channel.id)
                }
            }
            locked.addAll(channels.filter { it.isPremiere }.map { it.id })
            val optimized = if (locked.size >= channels.size) GenerationReshuffler.Result(day, 0)
            else GenerationReshuffler.optimize(day, dayStart, locked)
            for (channel in channels) {
                if (channel.id !in toPublish) continue
                val proposed = optimized.days[channel.id].orEmpty()
                if (proposed.isEmpty()) { s.noProgram.add(key(channel.id, date)); continue }
                val saved = try {
                    shared.publish(channel, date.toString(), proposed, s.lookup)
                } catch (e: JellyfinException) {
                    if (e.status == 409 && e.message?.contains("Library changed") == true) {
                        Log.i(TAG, "LIBRARY service has a newer library; reloading before publishing")
                        s.forceFull = true
                        executor.schedule({ tick() }, 5, TimeUnit.SECONDS)
                        return
                    }
                    if (e.status == 409) {
                        Log.w(TAG, "LIBRARY schedule was regenerated elsewhere; this TV needs a restart to follow it")
                        s.halted = true
                        return
                    }
                    Log.w(TAG, "LIBRARY could not publish ch=${channel.id} day=$date (${e.message})")
                    continue
                }
                if (saved.isNotEmpty()) { install(channel.id, date, saved); built++ }
            }
            Log.i(TAG, "LIBRARY horizon day=$date built for ${toPublish.size} channels")
        }
        if (built > 0 || missing.isNotEmpty()) {
            saveSnapshot(s)
            AppSession.notifySchedulesUpdated()
        }
    }

    /** "Yesterday" for the don't-repeat rules, read from the published days, which every TV shares. */
    private class PublishedHistory(private val published: HashMap<String, List<StoredBlock>>) : AiredHistory {
        private fun programs(channelId: Int, dayKey: String) =
            synchronized(published) { published["$channelId|$dayKey"] }.orEmpty().filter { !it.isBumper }
        override fun airedKeys(channelId: Int, dayKey: String): Set<String> =
            programs(channelId, dayKey).map { it.itemId }.toSet()
        override fun lastAired(channelId: Int, dayKey: String): String? =
            programs(channelId, dayKey).lastOrNull()?.itemId
        override fun record(channelId: Int, dayKey: String, orderedItemIds: List<String>) = Unit
    }

    // MARK: - Snapshot

    private fun saveSnapshot(s: State) {
        val shared = AppSession.sharedStation
        val zone = AppSession.stationZone
        val today = LocalDays.broadcastDate(nowSec(), zone).toString()
        val pruned = synchronized(s.published) {
            s.published.keys.removeAll { it.substringAfter('|') < today }
            HashMap(s.published)
        }
        s.store.save(LaunchSnapshot(
            stationId = s.stationId,
            generation = shared?.generationNow ?: 0,
            timeZone = zone.id,
            savedAtEpochSeconds = nowSec(),
            lastChannelId = AppSession.lastChannelId,
            channels = AppSession.channels.map { it.definition },
            items = s.items,
            commercials = s.commercials,
            publishedDays = pruned,
            library = s.meta,
        ))
    }

    private fun nowSec() = System.currentTimeMillis() / 1000
    private fun iso(epochSeconds: Long): String = Instant.ofEpochSecond(epochSeconds).toString()
}
