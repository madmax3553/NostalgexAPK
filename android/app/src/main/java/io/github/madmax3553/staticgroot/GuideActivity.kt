package io.github.madmax3553.staticgroot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.media3.ui.PlayerView
import io.github.madmax3553.staticgroot.channels.ChannelRuntime
import io.github.madmax3553.staticgroot.channels.displayTitle
import io.github.madmax3553.staticgroot.schedule.ChannelSchedule
import java.util.concurrent.Executors
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** TunerView proportions: information left, 4:3 preview right, EPG underneath. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GuideActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val schedulesWorker = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private lateinit var top: LinearLayout
    private lateinit var info: LinearLayout
    private lateinit var videoBox: FrameLayout
    private lateinit var video: PlayerView
    private lateinit var retroOverlay: RetroOverlay
    private lateinit var bumper: BumperView
    private lateinit var displaySettings: DisplaySettings
    private lateinit var grid: GuideGrid
    private lateinit var message: TextView
    private lateinit var description: TextView
    private lateinit var programmeProgress: ProgressBar
    private lateinit var settings: Button
    private lateinit var playback: TunerPlayback
    private var focused: ChannelRuntime? = null
    private var fullscreen = false
    private var started = false
    private var rebuilding = false
    private var refreshingCommercials = false
    private var schedules: Map<Int, ChannelSchedule> = emptyMap()
    private val refresh = object : Runnable {
        override fun run() {
            if (!started) return
            rebuildSchedules()
            handler.postDelayed(this, 30000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppSession.client == null) {
            startActivity(Intent(this, MainActivity::class.java)); finish(); return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        displaySettings = DisplaySettings(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(GrootBrand.BACKGROUND)
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nav.addView(GrootLogoView(this), LinearLayout.LayoutParams(0, dp(72), 1f))
        settings = Button(this).apply {
            text = "SETTINGS"; textSize = 12f; isFocusableInTouchMode = true
            setTextColor(GrootBrand.CYAN)
            backgroundTintList = android.content.res.ColorStateList.valueOf(GrootBrand.SURFACE)
            setOnClickListener { showSettings() }
        }
        settings.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                grid.requestFocus(); true
            } else false
        }
        nav.addView(settings)
        info.addView(nav)
        description = label("Building programme guide…", 13f, Color.WHITE)
        info.addView(description, LinearLayout.LayoutParams(-1, 0, 1f))
        programmeProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(GrootBrand.CYAN)
        }
        info.addView(programmeProgress, LinearLayout.LayoutParams(-1, dp(4)))
        video = PlayerView(this).apply { useController = false; isFocusable = false }
        retroOverlay = RetroOverlay(this)
        bumper = BumperView(this).apply { visibility = View.GONE }
        message = label("", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setBackgroundColor(0x88000000.toInt()) }
        videoBox = FrameLayout(this).apply {
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(bumper, FrameLayout.LayoutParams(-1, -1))
            addView(retroOverlay, FrameLayout.LayoutParams(-1, -1))
            addView(message, FrameLayout.LayoutParams(-1, -1))
        }
        top.addView(info)
        top.addView(videoBox)
        grid = GuideGrid(this, { ch -> focused = ch; showInfo() }, { ch ->
            if (playback.channel?.id == ch.id && (playback.player.isPlaying || playback.entry?.block?.isBumper == true)) setFullscreen(true)
            else { playback.tune(ch, force = true); showInfo() }
        }, { settings.requestFocus() })
        grid.displayTimeZone = displaySettings.displayTimeZone(AppSession.stationZone)
        val nowInit = System.currentTimeMillis() / 1000
        schedules = AppSession.channels.mapNotNull { ch -> AppSession.scheduleFor(ch, nowInit)?.let { ch.id to it } }.toMap()
        grid.schedules = schedules
        grid.windowStart = nowInit / 1800 * 1800
        grid.channels = AppSession.channels.sortedBy { it.number }.filter {
            displaySettings.showOffAirChannels || schedules[it.id]?.nowPlaying != null || it.pool.isNotEmpty()
        }
        root.addView(top)
        root.addView(grid)
        setContentView(root)
        playback = TunerPlayback(this, { text ->
            message.text = text
            message.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }, { showInfo() })
        video.player = playback.player
        playback.setCaptions(displaySettings.captionsEnabled)
        AppSession.client?.streamQuality = displaySettings.streamQuality
        root.post { layoutTuner() }
        focused = grid.channels.firstOrNull { it.id == AppSession.lastChannelId }
            ?: grid.channels.firstOrNull { schedules[it.id]?.nowPlaying != null || it.pool.isNotEmpty() }
            ?: grid.channels.firstOrNull()
        focused?.let { grid.select(it.id) }
        grid.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (!::playback.isInitialized) return
        started = true
        grid.crawlEnabled = true
        playback.resume()
        focused?.let { if (playback.channel == null) playback.tune(it) }
        handler.post(refresh)
    }

    override fun onStop() {
        started = false
        if (::grid.isInitialized) grid.crawlEnabled = false
        handler.removeCallbacks(refresh)
        if (::playback.isInitialized) playback.suspend()
        super.onStop()
    }

    override fun onDestroy() {
        if (::playback.isInitialized) playback.release()
        schedulesWorker.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun rebuildSchedules() {
        if (rebuilding) return
        rebuilding = true
        val now = System.currentTimeMillis() / 1000
        val channels = AppSession.channels.sortedBy { it.number }
        schedulesWorker.execute {
            val built = channels.mapNotNull { ch -> AppSession.scheduleFor(ch, now)?.let { ch.id to it } }.toMap()
            handler.post {
                rebuilding = false
                if (!started || isDestroyed) return@post
                schedules = built
                applyChannelVisibility(channels, built)
                grid.schedules = built
                grid.windowStart = now / 1800 * 1800
                grid.invalidate()
                showInfo()
            }
        }
    }

    private fun applyChannelVisibility(channels: List<ChannelRuntime>, built: Map<Int, ChannelSchedule>) {
        val previous = focused?.id
        grid.channels = channels.filter { displaySettings.showOffAirChannels || built[it.id]?.nowPlaying != null }
        focused = grid.channels.firstOrNull { it.id == previous } ?: grid.channels.firstOrNull()
        focused?.let { grid.select(it.id) }
        if (focused == null) description.text = "No channels are on air. Enable Show off-air channels in Settings."
    }

    private fun showInfo() {
        if (!::description.isInitialized) return
        if (::playback.isInitialized) {
            val onBreak = playback.entry?.block?.isBumper == true
            bumper.visibility = if (onBreak && !playback.commercialPlaying) View.VISIBLE else View.GONE
            if (onBreak) {
                bumper.channelName = playback.channel?.let { "CH ${it.number}  ${it.name}" }.orEmpty()
                bumper.remainingSeconds = playback.bumperRemainingSeconds
                bumper.upcoming = playback.comingNext()
                bumper.invalidate()
            }
        }
        val ch = focused ?: return
        val scheduled = schedules[ch.id]
        val item = if (::playback.isInitialized && playback.channel?.id == ch.id)
            playback.entry?.item else scheduled?.nowPlaying?.item
        val colour = runCatching { Color.parseColor(ch.definition.colorHex) }.getOrDefault(Color.YELLOW)
        val text = SpannableStringBuilder()
        fun section(value: String, tint: Int = Color.WHITE, scale: Float = 1f) {
            val start = text.length
            text.append(value)
            text.setSpan(ForegroundColorSpan(tint), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(scale), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        section("CH ${ch.number.toString().padStart(2, '0')}  ${ch.name}\n\n", colour)
        section((item?.displayTitle ?: "OFF AIR") + "\n", Color.WHITE, 1.35f)
        val episode = listOfNotNull(item?.seTag, item?.episodeTitle).joinToString("  ")
        if (episode.isNotEmpty()) section("$episode\n", Color.LTGRAY, .85f)
        item?.let {
            section("\n" + listOfNotNull(it.year?.toString(), it.contentRating, "${it.duration} MIN")
                .joinToString("  •  ") + "\n", colour, .85f)
            val synopsis = it.summary.replace(Regex("\\s+"), " ").trim()
            section("\n" + (synopsis.take(180).let { value -> if (synopsis.length > 180) "$value…" else value }
                .ifEmpty { "From your Jellyfin library. ${it.genres.joinToString(" / ")}" }) + "\n", Color.LTGRAY, .85f)
        }
        val now = System.currentTimeMillis() / 1000
        val block = scheduled?.nowPlaying
        val format = SimpleDateFormat("h:mm a", Locale.getDefault()).apply {
            timeZone = displaySettings.displayTimeZone(AppSession.stationZone)
        }
        if (block != null) {
            val remaining = ((block.endUnix - now).coerceAtLeast(0) + 59) / 60
            section("${format.format(Date(block.startUnix * 1000))} – ${format.format(Date(block.endUnix * 1000))}" +
                "   $remaining MIN LEFT\n", colour, .85f)
            programmeProgress.progress = ((now - block.startUnix).coerceAtLeast(0) * 1000 /
                (block.endUnix - block.startUnix).coerceAtLeast(1)).toInt().coerceIn(0, 1000)
        } else programmeProgress.progress = 0
        scheduled?.entries?.filter { !it.block.isBumper && it.startUnix > now }?.take(2)?.forEachIndexed { i, next ->
            section("\n${if (i == 0) "UP NEXT" else "LATER"}  ${format.format(Date(next.startUnix * 1000))}  ${next.item.displayTitle}",
                Color.LTGRAY, .8f)
        }
        description.text = text
    }

    private fun layoutTuner() {
        val w = root.width; val h = root.height
        if (w == 0 || h == 0) return
        val topHeight = (h * PREVIEW_HEIGHT_FRACTION).toInt()
        val previewWidth = (topHeight * 4f / 3f).toInt()
        if (fullscreen) {
            top.visibility = View.GONE; grid.visibility = View.GONE
            root.addView(videoBox, FrameLayout.LayoutParams(-1, -1))
        } else {
            top.visibility = View.VISIBLE; grid.visibility = View.VISIBLE
            top.layoutParams = FrameLayout.LayoutParams(w, topHeight)
            info.layoutParams = LinearLayout.LayoutParams(w - previewWidth, topHeight)
            videoBox.layoutParams = LinearLayout.LayoutParams(previewWidth, topHeight)
            grid.layoutParams = FrameLayout.LayoutParams(w, h - topHeight).apply { topMargin = topHeight }
        }
        applyRetroMode()
    }

    private fun applyRetroMode() {
        val retro = displaySettings.retroMode
        val height = if (fullscreen) root.height else (root.height * PREVIEW_HEIGHT_FRACTION).toInt()
        val width = if (fullscreen) root.width else (height * 4f / 3f).toInt()
        val crop = !fullscreen || retro
        val targetWidth = if (crop) minOf(width, (height * 4f / 3f).toInt()) else width
        video.layoutParams = FrameLayout.LayoutParams(targetWidth, -1, Gravity.CENTER)
        bumper.layoutParams = FrameLayout.LayoutParams(targetWidth, -1, Gravity.CENTER)
        retroOverlay.layoutParams = FrameLayout.LayoutParams(targetWidth, -1, Gravity.CENTER)
        retroOverlay.visibility = if (retro) View.VISIBLE else View.GONE
        video.resizeMode = if (crop) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun setFullscreen(value: Boolean) {
        if (fullscreen == value) return
        (videoBox.parent as? ViewGroup)?.removeView(videoBox)
        fullscreen = value
        grid.crawlEnabled = started && !value
        if (!value) top.addView(videoBox)
        layoutTuner()
        if (!value) grid.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::grid.isInitialized && event.action == KeyEvent.ACTION_DOWN && grid.userInteraction()) return true
        if (event.action == KeyEvent.ACTION_DOWN && ::playback.isInitialized) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (fullscreen) { setFullscreen(false); return true }
                    // Settings stays on Menu and the button above the first row. Back is for
                    // going home, then out, as on tvOS (GuideExitAction).
                    return when (GuideExitAction.decide(playback.channel?.id, focused?.id)) {
                        is GuideExitAction.JumpToLiveChannel -> { returnToPlayingChannel(); true }
                        GuideExitAction.LetSystemHandle -> super.dispatchKeyEvent(event)
                    }
                }
                KeyEvent.KEYCODE_MENU -> { showSettings(focused?.id); return true }
                KeyEvent.KEYCODE_LAST_CHANNEL -> { returnToPreviousChannel(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    playback.togglePause()
                    return true
                }
                KeyEvent.KEYCODE_CAPTIONS -> {
                    displaySettings.captionsEnabled = !displaySettings.captionsEnabled
                    playback.setCaptions(displaySettings.captionsEnabled)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> if (fullscreen) { showPlaybackControls(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER -> if (fullscreen) {
                    if (playback.player.playerError != null) playback.channel?.let { playback.tune(it, true) }
                    else showPlaybackControls()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showSettings(returnChannelId: Int? = playback.channel?.id ?: focused?.id) {
        grid.crawlEnabled = false
        var returnTarget = returnChannelId
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        content.addView(CheckBox(this).apply {
            text = "Retro Mode (CRT effects and fullscreen 4:3 crop)"
            isChecked = displaySettings.retroMode
            setOnCheckedChangeListener { _, checked ->
                displaySettings.retroMode = checked
                applyRetroMode()
            }
        })
        content.addView(CheckBox(this).apply {
            text = "Show off-air channels"
            isChecked = displaySettings.showOffAirChannels
            setOnCheckedChangeListener { _, checked ->
                displaySettings.showOffAirChannels = checked
                applyChannelVisibility(AppSession.channels.sortedBy { it.number }, schedules)
                grid.invalidate()
                showInfo()
                rebuildSchedules()
            }
        })
        content.addView(Button(this).apply {
            text = "Stream quality: ${displaySettings.streamQuality.label}"
            setOnClickListener { showStreamQuality(this) }
        })
        content.addView(Button(this).apply {
            text = "Time zone: ${timeZoneSummary()}"
            setOnClickListener { showTimeZone(this) }
        })
        content.addView(Button(this).apply {
            text = "Playback controls"
            setOnClickListener { showPlaybackControls() }
        })
        content.addView(Button(this).apply {
            text = "Return to playing channel"
            setOnClickListener { returnTarget = playback.channel?.id; returnToPlayingChannel() }
        })
        content.addView(Button(this).apply {
            text = "Previous channel"
            isEnabled = playback.previousChannel != null
            setOnClickListener { returnToPreviousChannel(); returnTarget = playback.channel?.id }
        })
        content.addView(Button(this).apply {
            text = "Refresh commercials only"
            setOnClickListener { refreshCommercials(this) }
        })
        content.addView(Button(this).apply {
            text = "Shared schedule service"
            setOnClickListener { showCompanionSettings() }
        })
        content.addView(Button(this).apply {
            text = "Force regenerate schedules"
            setOnClickListener {
                AlertDialog.Builder(this@GuideActivity).setTitle("Regenerate station schedules?")
                    .setMessage("Replaces today and the next two days on the shared service. Advertised times will change. Other TVs must restart to load the new lineup.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Regenerate") { _, _ ->
                        startActivity(Intent(this@GuideActivity, MainActivity::class.java)
                            .putExtra("force_regenerate", true))
                        finish()
                    }.show()
            }
        })
        content.addView(Button(this).apply {
            text = "Retry playback"
            setOnClickListener { playback.channel?.let { playback.tune(it, true) } }
        })
        content.addView(Button(this).apply {
            text = "Sign out"
            setOnClickListener {
                SessionStore(this@GuideActivity).clearSession()
                startActivity(Intent(this@GuideActivity, MainActivity::class.java)); finish()
            }
        })
        content.addView(Button(this).apply {
            text = "About"
            setOnClickListener {
                AlertDialog.Builder(this@GuideActivity).setMessage(R.string.about_credit)
                    .setPositiveButton("OK", null).show()
            }
        })
        val dialog = AlertDialog.Builder(this).setTitle("StaticGroot Settings").setView(
            ScrollView(this).apply { addView(content) })
            .setPositiveButton("Done", null).create()
        dialog.setOnDismissListener {
            if (!isFinishing && !isDestroyed) {
                val target = grid.channels.firstOrNull { it.id == returnTarget }
                    ?: grid.channels.firstOrNull { it.id == focused?.id }
                    ?: grid.channels.firstOrNull()
                focused = target
                target?.let { grid.select(it.id) }
                grid.requestFocus()
                showInfo()
                grid.userInteraction()
                grid.crawlEnabled = started && !fullscreen
            }
        }
        dialog.show()
    }

    /** Picks a rung of the quality ladder. Applies to the next stream, so the one playing is retuned. */
    private fun showStreamQuality(button: Button) {
        val levels = io.github.madmax3553.staticgroot.jellyfin.StreamQuality.entries
        val labels = levels.map { "${it.label}\n${it.detail}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Stream quality")
            .setSingleChoiceItems(labels, levels.indexOf(displaySettings.streamQuality)) { dialog, which ->
                val chosen = levels[which]
                if (chosen != displaySettings.streamQuality) {
                    displaySettings.streamQuality = chosen
                    AppSession.client?.streamQuality = chosen
                    button.text = "Stream quality: ${chosen.label}"
                    playback.channel?.let { playback.tune(it, true) }
                }
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun timeZoneSummary(): String {
        val chosen = displaySettings.timeZone
        val resolved = displaySettings.resolveZoneId(AppSession.stationZone).id
        return if (chosen == NorthAmericaTimeZone.AUTO) "Auto ($resolved)" else chosen.label
    }

    private fun showTimeZone(button: Button) {
        val zones = NorthAmericaTimeZone.entries
        val labels = zones.map { z ->
            if (z == NorthAmericaTimeZone.AUTO) "Auto (${z.resolve(this, AppSession.stationZone).id})" else z.label
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Time zone (North America)")
            .setSingleChoiceItems(labels, zones.indexOf(displaySettings.timeZone)) { dialog, which ->
                val chosen = zones[which]
                if (chosen != displaySettings.timeZone) {
                    displaySettings.timeZone = chosen
                    grid.displayTimeZone = displaySettings.displayTimeZone(AppSession.stationZone)
                    button.text = "Time zone: ${timeZoneSummary()}"
                    showInfo()
                }
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showPlaybackControls() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Playback controls").setView(content)
            .setPositiveButton("Done", null).create()
        content.addView(Button(this).apply {
            text = if (playback.paused) "Play" else "Pause"
            setOnClickListener { playback.togglePause(); text = if (playback.paused) "Play" else "Pause" }
        })
        content.addView(Button(this).apply {
            text = "Return to live"
            setOnClickListener { playback.returnToLive(); returnToPlayingChannel(); dialog.dismiss() }
        })
        content.addView(CheckBox(this).apply {
            text = "Closed captions (available subtitle tracks)"
            isChecked = displaySettings.captionsEnabled
            setOnCheckedChangeListener { _, checked ->
                displaySettings.captionsEnabled = checked
                playback.setCaptions(checked)
            }
        })
        dialog.show()
    }

    private fun returnToPlayingChannel() {
        val target = playback.channel ?: return
        focused = target
        grid.returnToChannel(target.id)
        showInfo()
    }

    private fun returnToPreviousChannel() {
        val target = playback.previousChannel ?: return
        focused = target
        grid.returnToChannel(target.id)
        playback.tune(target)
        showInfo()
    }

    private fun refreshCommercials(button: Button) {
        if (refreshingCommercials) return
        val client = AppSession.client ?: return
        refreshingCommercials = true
        button.isEnabled = false
        button.text = "Refreshing commercials…"
        Thread {
            val result = runCatching { client.loadCommercials() }
            handler.post {
                refreshingCommercials = false
                if (isDestroyed) return@post
                button.isEnabled = true
                result.onSuccess {
                    AppSession.commercials = it
                    button.text = "Commercials refreshed: ${it.size} clips"
                }.onFailure { button.text = "Refresh failed. Try again." }
            }
        }.start()
    }

    private fun showCompanionSettings() {
        val saved = io.github.madmax3553.staticgroot.schedule.CompanionSettings(this)
        val defaultUrl = AppSession.client?.serverUrl?.let {
            io.github.madmax3553.staticgroot.schedule.SharedStation.defaultUrl(it)
        }.orEmpty()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val enabled = CheckBox(this).apply {
            text = "Sync schedule across devices (${defaultUrl.ifEmpty { "port 8787" }})"
            isChecked = saved.enabled
        }
        val url = EditText(this).apply {
            hint = "Custom URL override (blank = $defaultUrl)"
            setText(saved.url)
            isSingleLine = true
        }
        box.addView(enabled); box.addView(url)
        box.addView(TextView(this).apply { text = "Automatically connects to $defaultUrl using your Jellyfin server & user hash. Restart after saving." })
        val dialog = AlertDialog.Builder(this).setTitle("Shared schedules").setView(box)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { saved.save(url.text.toString(), enabled.isChecked); dialog.dismiss() }
                catch (e: IllegalArgumentException) { url.error = e.message }
            }
        }
        dialog.show()
    }

    private fun label(value: String, size: Float, colour: Int, accent: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(colour)
        typeface = resources.getFont(if (accent) R.font.vt323_regular else R.font.dmmono_regular)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        // Larger preview requested for StaticGroot; grid keeps the remaining 40% of the screen.
        private const val PREVIEW_HEIGHT_FRACTION = .60f
    }
}
