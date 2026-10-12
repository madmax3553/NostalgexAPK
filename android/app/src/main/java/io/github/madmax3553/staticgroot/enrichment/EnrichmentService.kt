package io.github.madmax3553.staticgroot.enrichment

import io.github.madmax3553.staticgroot.channels.MediaEnrichment
import io.github.madmax3553.staticgroot.channels.MediaItem
import io.github.madmax3553.staticgroot.channels.MediaType
import io.github.madmax3553.staticgroot.channels.OmdbResult
import io.github.madmax3553.staticgroot.jellyfin.JellyfinException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What one enrichment pass did. Counts only, so it is safe to log. */
data class EnrichmentOutcome(
    /** Records for titles in the library, keyed by TMDB id. */
    val records: Map<String, MediaEnrichment>,
    val uniqueTitles: Int,
    val fromCache: Int,
    val fetchedNew: Int,
    val omdbBackfilled: Int,
    /** Titles left for a later launch because the time budget ran out. */
    val deferred: Int,
    val tmdbFailures: Int,
    val omdbDisabled: Boolean,
    val tmdbDisabled: Boolean,
)

/**
 * Port of tvOS EnrichmentService.enrichItems, without the Supabase cache layer.
 *
 * Kept from Swift: titles are deduplicated by TMDB id (episodes use their show's id and the
 * "tv" endpoint), ten requests in flight, TMDB first and OMDb second per title, and OMDb is
 * switched off for the rest of the pass on 401, 403, 429, 5xx or an exhausted quota.
 *
 * Different from Swift, because there is no shared cache to lean on:
 *  - A [budgetMs] limit. A first launch over thousands of titles would otherwise hold the
 *    splash screen for minutes. What is not reached stays uncached and is picked up on the
 *    next launch, so the lineup fills in over a few launches.
 *  - Progress is saved to disk as it goes, so a killed launch keeps its work.
 *  - OMDb is not re-asked within [OMDB_RETRY_SECONDS] for a title it already answered.
 */
class EnrichmentService(
    private val tmdb: TmdbClient?,
    private val omdb: OmdbClient?,
    private val store: EnrichmentStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxConcurrency: Int = 10,
    private val budgetMs: Long = DEFAULT_BUDGET_MS,
    private val log: (String) -> Unit = {},
) {

    private class Work(val tmdbID: String, var imdbID: String?, val mediaType: String)

    fun enrich(items: List<MediaItem>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): EnrichmentOutcome {
        val work = LinkedHashMap<String, Work>()
        for (item in items) {
            val id = item.tmdbID ?: continue
            val existing = work[id]
            if (existing == null) {
                work[id] = Work(id, item.imdbID, if (item.type == MediaType.EPISODE) "tv" else "movie")
            } else if (existing.imdbID == null && item.imdbID != null) {
                existing.imdbID = item.imdbID
            }
        }

        val cache = HashMap(store.load())
        val toFetch = ArrayList<Work>()
        val toBackfill = ArrayList<Pair<Work, MediaEnrichment>>()
        val nowSec = nowMs() / 1000
        for (w in work.values) {
            val have = cache[w.tmdbID]
            if (have == null) {
                if (tmdb != null) toFetch.add(w)
            } else if (omdb != null && !have.hasOmdb && (w.imdbID ?: have.imdbID) != null &&
                (have.omdbCheckedAt == 0L || nowSec - have.omdbCheckedAt >= OMDB_RETRY_SECONDS)
            ) {
                toBackfill.add(w to have)
            }
        }
        val total = toFetch.size + toBackfill.size
        log("unique titles=${work.size} cached=${work.keys.count { it in cache }} toFetch=${toFetch.size} omdbBackfill=${toBackfill.size}")

        val fetched = ConcurrentHashMap<String, MediaEnrichment>()
        val done = AtomicInteger(0)
        val newCount = AtomicInteger(0)
        val backfilled = AtomicInteger(0)
        val tmdbFailures = AtomicInteger(0)
        val rateLimited = AtomicInteger(0)
        val omdbOff = AtomicBoolean(false)
        val tmdbOff = AtomicBoolean(false)
        val deadline = nowMs() + budgetMs

        fun omdbFetch(imdbID: String): OmdbResult? {
            val client = omdb ?: return null
            if (omdbOff.get()) return null
            return try {
                client.fetch(imdbID)
            } catch (e: OmdbRateLimited) {
                omdbOff.set(true); null
            } catch (e: OmdbNotFound) {
                null
            } catch (e: JellyfinException) {
                val code = e.status
                if (code == 401 || code == 403 || code == 429 || (code != null && code >= 500)) omdbOff.set(true)
                null
            } catch (e: Exception) {
                null
            }
        }

        fun finishOne() {
            val n = done.incrementAndGet()
            if (n % PROGRESS_EVERY == 0 || n == total) onProgress(n, total)
            if (n % SAVE_EVERY == 0) store.save(cache.values + fetched.values)
        }

        val tasks = ArrayList<Callable<Unit>>()
        for (w in toFetch) {
            tasks.add(Callable {
                try {
                    if (nowMs() > deadline || tmdbOff.get()) return@Callable
                    val base = try {
                        tmdb!!.fetch(w.tmdbID, w.imdbID, w.mediaType, nowMs() / 1000)
                    } catch (e: TmdbRateLimited) {
                        if (rateLimited.incrementAndGet() >= TMDB_RATE_LIMIT_GIVE_UP) tmdbOff.set(true)
                        tmdbFailures.incrementAndGet()
                        return@Callable
                    } catch (e: JellyfinException) {
                        if (e.status == 401 || e.status == 403) tmdbOff.set(true)
                        tmdbFailures.incrementAndGet()
                        return@Callable
                    }
                    val imdb = w.imdbID
                    val merged = if (imdb != null) {
                        omdbFetch(imdb)?.let { base.merging(it, nowMs() / 1000) } ?: base
                    } else base
                    fetched[w.tmdbID] = merged
                    newCount.incrementAndGet()
                } finally {
                    finishOne()
                }
            })
        }
        for ((w, have) in toBackfill) {
            tasks.add(Callable {
                try {
                    if (nowMs() > deadline) return@Callable
                    val imdb = (w.imdbID ?: have.imdbID) ?: return@Callable
                    if (omdbOff.get()) return@Callable
                    val r = omdbFetch(imdb)
                    // Record that OMDb was asked even when it had nothing, so the next
                    // launch does not ask again for 30 days. A session-level failure
                    // (quota, bad key) is not an answer, so those stay unrecorded.
                    fetched[w.tmdbID] = if (r != null) {
                        have.merging(r, nowMs() / 1000)
                    } else if (!omdbOff.get()) {
                        have.copy(omdbCheckedAt = nowMs() / 1000)
                    } else return@Callable
                    backfilled.incrementAndGet()
                } finally {
                    finishOne()
                }
            })
        }

        if (tasks.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(maxConcurrency.coerceAtLeast(1))
            try {
                pool.invokeAll(tasks)
            } finally {
                pool.shutdownNow()
            }
            cache.putAll(fetched)
            store.save(cache.values)
        }

        val deferred = total - fetched.size - tmdbFailures.get()
        val records = work.keys.mapNotNull { id -> cache[id]?.let { id to it } }.toMap()
        val outcome = EnrichmentOutcome(
            records = records,
            uniqueTitles = work.size,
            fromCache = work.keys.count { cache.containsKey(it) && !fetched.containsKey(it) },
            fetchedNew = newCount.get(),
            omdbBackfilled = backfilled.get(),
            deferred = deferred.coerceAtLeast(0),
            tmdbFailures = tmdbFailures.get(),
            omdbDisabled = omdbOff.get(),
            tmdbDisabled = tmdbOff.get(),
        )
        log(
            "done records=${records.size} fromCache=${outcome.fromCache} fetchedNew=${outcome.fetchedNew} " +
                "omdbBackfilled=${outcome.omdbBackfilled} deferred=${outcome.deferred} " +
                "tmdbFailures=${outcome.tmdbFailures} omdbDisabled=${outcome.omdbDisabled} tmdbDisabled=${outcome.tmdbDisabled}",
        )
        return outcome
    }

    companion object {
        /** Longest a launch waits on the network for enrichment. Not in tvOS; see the class note. */
        const val DEFAULT_BUDGET_MS = 90_000L
        const val OMDB_RETRY_SECONDS = 30L * 24 * 3600
        private const val PROGRESS_EVERY = 5
        private const val SAVE_EVERY = 250
        private const val TMDB_RATE_LIMIT_GIVE_UP = 20
    }
}
