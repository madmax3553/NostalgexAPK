package io.github.madmax3553.staticgroot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import io.github.madmax3553.staticgroot.channels.ChannelRuntime
import io.github.madmax3553.staticgroot.schedule.ScheduleEntry
import java.util.concurrent.Executors

/** One player for the tuner and fullscreen. All player access stays on the main thread. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TunerPlayback(context: Context, private val status: (String) -> Unit,
    private val changed: () -> Unit) {
    private val http = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(10000).setReadTimeoutMs(15000)
        .setDefaultRequestProperties(AppSession.client?.playbackHeaders().orEmpty())
    val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http)).build()
    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val cleanup = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var active = false
    private var released = false
    private var sessionId: String? = null
    var channel: ChannelRuntime? = null
        private set
    var previousChannel: ChannelRuntime? = null
        private set
    var entry: ScheduleEntry? = null
        private set
    private var pending = false
    private var firstFrame = false
    private var startedAt = 0L
    private var bumperDeadline = 0L
    private val ads = java.util.ArrayDeque<io.github.madmax3553.staticgroot.jellyfin.CommercialClip>()
    var commercialPlaying = false
        private set
    private var commercialDeadline = 0L
    private var learningResolution: io.github.madmax3553.staticgroot.jellyfin.PlaybackResolution? = null
    private var sampleStart = 0L
    private var samplePosition = 0L
    private var startupMs = 0L
    private var observationSent = false
    var paused = false
        private set
    private var pausedAt = 0L

    fun setCaptions(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !enabled)
            .setPreferredTextLanguage(java.util.Locale.getDefault().language)
            .setSelectUndeterminedTextLanguage(enabled)
            .build()
    }

    fun togglePause() {
        val now = android.os.SystemClock.elapsedRealtime()
        paused = !paused
        if (paused) pausedAt = now else {
            val duration = now - pausedAt
            if (entry?.block?.isBumper == true) {
                bumperDeadline += duration
                commercialDeadline += duration
            }
        }
        player.playWhenReady = !paused
        if (!paused) {
            sampleStart = android.os.SystemClock.elapsedRealtime()
            samplePosition = player.currentPosition
        }
        changed()
    }

    fun returnToLive() { channel?.let { tune(it, force = true) } }
    val bumperRemainingSeconds: Long
        get() = ((bumperDeadline - (if (paused) pausedAt else android.os.SystemClock.elapsedRealtime()) + 999) / 1000).coerceAtLeast(0)

    fun comingNext(): List<String> {
        val current = entry ?: return emptyList()
        val target = channel ?: return emptyList()
        return AppSession.scheduleFor(target, current.startUnix)?.entries.orEmpty()
            .filter { !it.block.isBumper && it.startUnix >= current.endUnix }.take(3)
            .map { it.item.title + (it.item.seTag?.let { tag -> "  $tag" } ?: "") }
    }

    init {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData) {
                Log.i("StaticGroot", "VIDEO load ${loadEventInfo.uri.lastPathSegment}")
            }
            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData) {
                Log.i("StaticGroot", "VIDEO loaded bytes=${loadEventInfo.bytesLoaded} ms=${loadEventInfo.loadDurationMs}")
            }
        })
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!firstFrame) {
                    startupMs = System.currentTimeMillis() - startedAt
                    sampleStart = android.os.SystemClock.elapsedRealtime()
                    samplePosition = player.currentPosition
                }
                firstFrame = true
                if (commercialPlaying) {
                    val duration = currentAdDuration
                    commercialDeadline = minOf(bumperDeadline - 10000,
                        android.os.SystemClock.elapsedRealtime() + duration * 1000)
                }
                status("")
                Log.i("StaticGroot", "VIDEO first frame after ${System.currentTimeMillis() - startedAt}ms")
            }
            override fun onPlaybackStateChanged(state: Int) {
                Log.i("StaticGroot", "VIDEO state=$state positionMs=${player.currentPosition}")
                if (state == Player.STATE_BUFFERING) status("BUFFERING…")
                if (state == Player.STATE_READY && firstFrame) status("")
                if (state == Player.STATE_ENDED) {
                    if (commercialPlaying) nextCommercial() else advance()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                // Do not log nested exception text: data-source errors can contain token-bearing URLs.
                Log.w("StaticGroot", "VIDEO error=${error.errorCodeName}")
                reportObservation("failure")
                if (commercialPlaying) { generation++; commercialPlaying = false; ads.clear(); player.stop(); endSession(); status(""); changed(); return }
                status("PLAYBACK FAILED: ${error.errorCodeName}\nPress OK to retry")
            }
        })
    }

    fun resume() {
        active = true
        channel?.let { tune(it, force = true) }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
    }

    fun suspend() {
        active = false
        generation++
        pending = false
        handler.removeCallbacksAndMessages(null)
        player.stop()
        endSession()
    }

    fun release() {
        suspend()
        released = true
        player.release()
        network.shutdown()
        cleanup.shutdown()
    }

    fun tune(target: ChannelRuntime, force: Boolean = false) {
        if (!active || released) return
        val now = System.currentTimeMillis() / 1000
        val schedule = AppSession.scheduleFor(target, now)
        val programme = schedule?.nowPlaying
        if (!force && channel?.id == target.id && entry?.block == programme?.block &&
            (pending || player.playbackState != Player.STATE_IDLE)) return
        generation++
        paused = false
        val token = generation
        ads.clear()
        commercialPlaying = false
        player.stop()
        endSession()
        if (channel?.id != target.id) previousChannel = channel
        channel = target
        AppSession.lastChannelId = target.id
        AppSession.sharedStation?.let { shared -> cleanup.execute { runCatching { shared.select(target.id) } } }
        entry = programme
        changed()
        if (programme == null) {
            status("OFF AIR\nNo programmes available on this channel")
            return
        }
        resolve(programme, schedule.elapsedSeconds, token)
    }

    private fun resolve(programme: ScheduleEntry, elapsed: Long, token: Int) {
        if (programme.block.isBumper) {
            pending = false
            player.stop()
            bumperDeadline = android.os.SystemClock.elapsedRealtime() +
                (programme.endUnix - programme.startUnix - elapsed).coerceAtLeast(0) * 1000
            ads.clear()
            ads.addAll(io.github.madmax3553.staticgroot.jellyfin.CommercialClips.fit(
                AppSession.commercials.shuffled(), bumperRemainingSeconds))
            status("")
            changed()
            handler.removeCallbacks(bumperTick)
            handler.post(bumperTick)
            nextCommercial()
            return
        }
        handler.removeCallbacks(bumperTick)
        loadStream(programme.item.id, programme.block.mediaOffsetSeconds + elapsed, token)
    }

    private fun loadStream(itemId: String, elapsed: Long, token: Int) {
        val client = AppSession.client ?: return
        pending = true
        firstFrame = false
        learningResolution = null
        sampleStart = 0
        observationSent = false
        startupMs = 0
        startedAt = System.currentTimeMillis()
        status("TUNING…")
        network.execute {
            try {
                val resolution = client.resolvePlayback(itemId)
                handler.post {
                    if (token != generation || !active || released) {
                        if (!cleanup.isShutdown) cleanup.execute { runCatching { client.stopPlayback(resolution?.playSessionId) } }
                        return@post
                    }
                    pending = false
                    if (resolution == null) {
                        if (commercialPlaying) { commercialPlaying = false; ads.clear(); status(""); changed(); return@post }
                        status("OFF AIR\nJellyfin offered no playable stream. Press OK to retry")
                        return@post
                    }
                    sessionId = resolution.playSessionId
                    learningResolution = resolution
                    Log.i("StaticGroot", "VIDEO resolved direct=${resolution.isDirectPlay} offsetSeconds=$elapsed")
                    // Jellyfin's HLS playlist starts at zero. Match the measured Swift path:
                    // never send StartTimeTicks; seek client-side for both direct and HLS streams.
                    player.setMediaItem(MediaItem.fromUri(resolution.url),
                        elapsed * 1000)
                    player.prepare()
                    player.playWhenReady = !paused
                }
            } catch (e: Exception) {
                handler.post {
                    if (token == generation && active && !released) {
                        pending = false
                        if (commercialPlaying) { commercialPlaying = false; ads.clear(); status(""); changed(); return@post }
                        status("COULD NOT TUNE\n${e.message ?: "Request failed"}\nPress OK to retry")
                    }
                }
            }
        }
    }

    /** Natural end joins the next programme from its beginning, as on tvOS. */
    private fun advance() {
        val target = channel ?: return
        val current = entry ?: return
        val schedule = AppSession.scheduleFor(target, current.endUnix)
        val next = schedule?.entries?.firstOrNull { it.startUnix >= current.endUnix }
        if (next == null) { tune(target, true); return }
        generation++
        player.stop()
        commercialPlaying = false
        ads.clear()
        endSession()
        entry = next
        changed()
        val elapsed = io.github.madmax3553.staticgroot.schedule.ProgrammeBoundary.elapsedInBreak(
            next.block, System.currentTimeMillis() / 1000)
        resolve(entry!!, elapsed, generation)
    }

    private fun endSession() {
        val old = sessionId ?: return
        sessionId = null
        val client = AppSession.client ?: return
        cleanup.execute { runCatching { client.stopPlayback(old) } }
    }

    private val bumperTick = object : Runnable {
        override fun run() {
            if (!active || released || entry?.block?.isBumper != true) return
            if (paused) { handler.postDelayed(this, 250); return }
            if (bumperRemainingSeconds == 0L) { generation++; advance(); return }
            if (commercialPlaying && android.os.SystemClock.elapsedRealtime() >= commercialDeadline) nextCommercial()
            changed()
            handler.postDelayed(this, 250)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active || released) return
            if (paused) { handler.postDelayed(this, 1000); return }
            if (!pending && player.playbackState == Player.STATE_BUFFERING &&
                System.currentTimeMillis() - startedAt > 30000) {
                reportObservation("failure")
                player.stop()
                endSession()
                status("STREAM DID NOT START\nJellyfin media segments stalled. Press OK to retry")
            }
            val current = entry
            if (firstFrame && sampleStart > 0 && !observationSent) {
                if (!player.playWhenReady) {
                    // A user pause is not a server/decoder performance failure.
                    sampleStart = android.os.SystemClock.elapsedRealtime()
                    samplePosition = player.currentPosition
                } else {
                    val wall = android.os.SystemClock.elapsedRealtime() - sampleStart
                    val played = (player.currentPosition - samplePosition).coerceAtLeast(0)
                    if (io.github.madmax3553.staticgroot.jellyfin.PlaybackLearning.sustained(played, wall))
                        reportObservation("success")
                }
            }
            if (!commercialPlaying && current != null && !current.block.isBumper && player.isPlaying) {
                val target = channel
                val next = target?.let { AppSession.scheduleFor(it, current.endUnix) }
                    ?.entries?.firstOrNull { it.startUnix >= current.endUnix }
                if (io.github.madmax3553.staticgroot.schedule.ProgrammeBoundary.shouldInterrupt(
                        current.block, next?.block, player.currentPosition / 1000)) advance()
            }
            if (player.isPlaying) Log.d("StaticGroot", "VIDEO playing positionMs=${player.currentPosition} frame=$firstFrame")
            changed()
            handler.postDelayed(this, 1000)
        }
    }

    private fun nextCommercial() {
        generation++
        player.stop()
        endSession()
        pending = false
        commercialPlaying = false
        val next = ads.pollFirst()
        if (next == null || next.durationSeconds + 10 > bumperRemainingSeconds) {
            ads.clear()
            status("")
            changed()
            return
        }
        commercialPlaying = true
        currentAdDuration = next.durationSeconds
        commercialDeadline = android.os.SystemClock.elapsedRealtime() + next.durationSeconds * 1000
        changed()
        loadStream(next.id, 0, generation)
    }
    private var currentAdDuration = 0L

    private fun reportObservation(outcome: String) {
        val resolution = learningResolution ?: return
        val source = resolution.sourceFingerprint ?: return
        val device = resolution.deviceProfile ?: return
        val itemId = resolution.itemId ?: return
        val shared = AppSession.sharedStation ?: return
        // Report a failure even after a successful initial sample: the server must invalidate
        // a preferred setting that later stalls. Avoid recording the same verdict repeatedly.
        if (observationSent && outcome == "success") return
        observationSent = true
        val wall = if (sampleStart > 0) android.os.SystemClock.elapsedRealtime() - sampleStart else 0
        val played = if (sampleStart > 0) (player.currentPosition - samplePosition).coerceAtLeast(0) else 0
        val sample = io.github.madmax3553.staticgroot.jellyfin.PlaybackObservation(itemId, source, device,
            outcome, resolution.settings, startupMs, played, wall)
        if (outcome == "failure") AppSession.client?.invalidateLearnedPlayback(itemId, source, device)
        if (!cleanup.isShutdown) cleanup.execute {
            runCatching { shared.reportPlayback(sample) }
                .onSuccess { Log.i("StaticGroot", "PLAYBACK profile recorded outcome=$outcome") }
                .onFailure { Log.w("StaticGroot", "PLAYBACK profile not recorded") }
        }
    }
}
