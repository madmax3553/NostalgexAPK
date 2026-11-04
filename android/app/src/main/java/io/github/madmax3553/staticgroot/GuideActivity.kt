package io.github.madmax3553.staticgroot

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.media3.ui.PlayerView
import io.github.madmax3553.staticgroot.channels.ChannelRuntime
import io.github.madmax3553.staticgroot.channels.displayTitle
import io.github.madmax3553.staticgroot.schedule.ChannelSchedule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
    private lateinit var programProgress: ProgressBar
    private lateinit var settings: TextView
    private lateinit var playback: TunerPlayback
    private var activeControlsDialog: Dialog? = null
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
        settings = makePillButton("◀ SETTINGS") { showSettings(focused?.id) }.apply {
            setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_BACK)
                ) {
                    focused?.let { grid.select(it.id) }
                    grid.requestFocus()
                    true
                } else false
            }
        }
        nav.addView(settings)
        info.addView(nav)
        description = label("Building program guide…", 13f, Color.WHITE)
        info.addView(description, LinearLayout.LayoutParams(-1, 0, 1f))
        programProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(GrootBrand.CYAN)
        }
        info.addView(programProgress, LinearLayout.LayoutParams(-1, dp(4)))
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
        }, { showSettings(focused?.id) })
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
        val preferredId = AppSession.lastChannelId ?: displaySettings.lastChannelId
        focused = grid.channels.firstOrNull { it.id == preferredId }
            ?: grid.channels.firstOrNull { schedules[it.id]?.nowPlaying != null || it.pool.isNotEmpty() }
            ?: grid.channels.firstOrNull()
        focused?.let { grid.select(it.id) }
        grid.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (!::playback.isInitialized) return
        started = true
        AppSession.onSchedulesUpdated = {
            handler.post { if (started && !isDestroyed) rebuildSchedules() }
        }
        grid.crawlEnabled = true
        playback.resume()
        focused?.let { if (playback.channel == null) playback.tune(it) }
        syncRemoteSelectionOnResume()
        handler.post(refresh)
    }

    override fun onStop() {
        started = false
        AppSession.onSchedulesUpdated = null
        if (::grid.isInitialized) grid.crawlEnabled = false
        handler.removeCallbacks(refresh)
        val activeChannelId = (if (::playback.isInitialized) playback.channel?.id else null) ?: focused?.id
        if (activeChannelId != null) {
            AppSession.lastChannelId = activeChannelId
            displaySettings.lastChannelId = activeChannelId
            AppSession.sharedStation?.let { shared ->
                schedulesWorker.execute { runCatching { shared.select(activeChannelId) } }
            }
        }
        if (::playback.isInitialized) playback.suspend()
        super.onStop()
    }

    override fun onDestroy() {
        AppSession.onSchedulesUpdated = null
        activeControlsDialog?.dismiss()
        if (::playback.isInitialized) playback.release()
        schedulesWorker.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** If another device changed the active channel while this TV was stopped, follow it on resume. */
    private fun syncRemoteSelectionOnResume() {
        val shared = AppSession.sharedStation ?: return
        schedulesWorker.execute {
            val remoteId = runCatching { shared.selection() }.getOrNull() ?: return@execute
            handler.post {
                if (!started || isDestroyed) return@post
                val currentId = playback.channel?.id ?: focused?.id
                if (remoteId != currentId) {
                    val target = grid.channels.firstOrNull { it.id == remoteId }
                        ?: AppSession.channels.firstOrNull { it.id == remoteId }
                    if (target != null) {
                        AppSession.lastChannelId = target.id
                        displaySettings.lastChannelId = target.id
                        focused = target
                        grid.select(target.id)
                        playback.tune(target)
                        showInfo()
                    }
                }
            }
        }
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
        val previous = focused?.id ?: playback.channel?.id ?: AppSession.lastChannelId
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
            programProgress.progress = ((now - block.startUnix).coerceAtLeast(0) * 1000 /
                (block.endUnix - block.startUnix).coerceAtLeast(1)).toInt().coerceIn(0, 1000)
        } else programProgress.progress = 0
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

    private fun stepChannel(delta: Int) {
        val list = grid.channels
        if (list.isEmpty()) return
        val currentId = playback.channel?.id ?: focused?.id
        val currentIdx = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val next = list[Math.floorMod(currentIdx + delta, list.size)]
        focused = next
        grid.select(next.id)
        playback.tune(next)
        showInfo()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::grid.isInitialized && event.action == KeyEvent.ACTION_DOWN && grid.userInteraction()) return true
        if (event.action == KeyEvent.ACTION_DOWN && ::playback.isInitialized) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (fullscreen) { setFullscreen(false); return true }
                    // Back returns to the playing channel first, then exits on a second press, as on tvOS.
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
                KeyEvent.KEYCODE_CHANNEL_UP -> { stepChannel(-1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { stepChannel(1); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (fullscreen) {
                    showPlaybackControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (fullscreen) {
                    stepChannel(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (fullscreen) {
                    stepChannel(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (fullscreen) {
                    if (playback.player.playerError != null) playback.channel?.let { playback.tune(it, true) }
                    else showPlaybackControls()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Broadcast-style Now Playing HUD overlay. Replaces the stock system AlertDialog with a
     * bottom-anchored CRT control deck featuring live program status and horizontal pill controls.
     */
    private fun showPlaybackControls() {
        activeControlsDialog?.dismiss()
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0x66000000))

        val autoHide = Runnable { if (dialog.isShowing) dialog.dismiss() }
        fun bumpAutoHide() {
            handler.removeCallbacks(autoHide)
            handler.postDelayed(autoHide, 7000)
        }

        val outer = FrameLayout(this).apply {
            setPadding(dp(48), dp(32), dp(48), dp(36))
            setOnClickListener { dialog.dismiss() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(24))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0xF20D0F18.toInt())
                setStroke(dp(2), GrootBrand.CYAN)
            }
            isClickable = true
        }

        // Top row: Channel + Now Playing title on left, LIVE / PAUSED status badge on right
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ch = playback.channel ?: focused
        val chColor = ch?.let { runCatching { Color.parseColor(it.definition.colorHex) }.getOrNull() } ?: GrootBrand.CYAN
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chLabel = label(
            ch?.let { "CH ${it.number.toString().padStart(2, '0')}  •  ${it.name.uppercase()}" } ?: "NO CHANNEL",
            14f, chColor
        )
        val item = playback.entry?.item ?: ch?.let { schedules[it.id]?.nowPlaying?.item }
        val programTitle = label(
            if (playback.entry?.block?.isBumper == true) "STATION BREAK"
            else (item?.displayTitle ?: "LIVE BROADCAST"),
            24f, Color.WHITE, accent = true
        )
        titleStack.addView(chLabel)
        titleStack.addView(programTitle)
        headerRow.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))

        val statusBadge = label("", 16f, GrootBrand.CYAN, accent = true).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        fun refreshStatusBadge() {
            val isPaused = playback.paused
            statusBadge.text = if (isPaused) "❚❚ PAUSED" else "● LIVE ON AIR"
            statusBadge.setTextColor(if (isPaused) Color.rgb(255, 229, 0) else GrootBrand.CYAN)
            statusBadge.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFF161B28.toInt())
                setStroke(dp(1), if (isPaused) Color.rgb(255, 229, 0) else GrootBrand.CYAN)
            }
        }
        refreshStatusBadge()
        headerRow.addView(statusBadge)
        card.addView(headerRow)

        // Divider
        card.addView(View(this).apply {
            setBackgroundColor(0x3329F2FF)
        }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(14)
            bottomMargin = dp(16)
        })

        // Horizontal Pill Controls Row
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun addControlPill(initialText: String, onClick: (TextView) -> Unit): TextView {
            val pill = makePillButton(initialText) { view ->
                bumpAutoHide()
                onClick(view)
            }
            val params = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) }
            controlsRow.addView(pill, params)
            return pill
        }

        val playPauseBtn = addControlPill(if (playback.paused) "▶ PLAY" else "❚❚ PAUSE") { btn ->
            playback.togglePause()
            btn.text = if (playback.paused) "▶ PLAY" else "❚❚ PAUSE"
            refreshStatusBadge()
        }

        addControlPill("● SYNC LIVE") {
            playback.returnToLive()
            returnToPlayingChannel()
            dialog.dismiss()
        }

        addControlPill("CC: ${if (displaySettings.captionsEnabled) "ON" else "OFF"}") { btn ->
            val next = !displaySettings.captionsEnabled
            displaySettings.captionsEnabled = next
            playback.setCaptions(next)
            btn.text = "CC: ${if (next) "ON" else "OFF"}"
        }

        addControlPill("CRT 4:3: ${if (displaySettings.retroMode) "ON" else "OFF"}") { btn ->
            val next = !displaySettings.retroMode
            displaySettings.retroMode = next
            applyRetroMode()
            btn.text = "CRT 4:3: ${if (next) "ON" else "OFF"}"
        }

        addControlPill("QUALITY: ${displaySettings.streamQuality.label.uppercase()}") { btn ->
            dialog.dismiss()
            showStreamQuality {
                btn.text = "QUALITY: ${displaySettings.streamQuality.label.uppercase()}"
            }
        }

        if (playback.previousChannel != null) {
            addControlPill("⇄ PREV CH") {
                returnToPreviousChannel()
                dialog.dismiss()
            }
        }

        addControlPill("⚙ SETTINGS") {
            val targetId = focused?.id ?: playback.channel?.id
            dialog.dismiss()
            showSettings(targetId)
        }

        if (fullscreen) {
            addControlPill("▤ GUIDE") {
                dialog.dismiss()
                setFullscreen(false)
            }
        }

        card.addView(controlsRow)
        outer.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        dialog.setContentView(outer)

        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                bumpAutoHide()
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    dialog.dismiss()
                    return@setOnKeyListener true
                }
            }
            false
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(autoHide)
            if (activeControlsDialog === dialog) activeControlsDialog = null
        }

        activeControlsDialog = dialog
        dialog.show()
        playPauseBtn.requestFocus()
        bumpAutoHide()
    }

    /**
     * Styled StaticGroot Settings deck. Remembers the exact channel row that was focused when
     * Settings opened and restores focus to that row when dismissed.
     */
    private fun showSettings(returnChannelId: Int? = focused?.id ?: playback.channel?.id) {
        grid.crawlEnabled = false
        var returnTarget = returnChannelId
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0xB3000000.toInt()))

        val outer = FrameLayout(this).apply {
            setPadding(dp(80), dp(36), dp(80), dp(36))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(22))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(GrootBrand.BACKGROUND)
                setStroke(dp(2), GrootBrand.CYAN)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("STATICGROOT SETTINGS", 26f, GrootBrand.CYAN, accent = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        val closeBtn = makePillButton("DONE") { dialog.dismiss() }
        header.addView(closeBtn)
        panel.addView(header)

        panel.addView(View(this).apply {
            setBackgroundColor(0x3329F2FF)
        }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        })

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun addRow(title: String, initialValue: String = "", onClick: (TextView) -> Unit): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(18), dp(12), dp(18), dp(12))
            }
            val titleView = label(title, 15f, Color.WHITE)
            val valueView = label(initialValue, 15f, GrootBrand.CYAN)
            row.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(valueView)

            fun styleRow(hasFocus: Boolean) {
                row.background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(if (hasFocus) 0xFF1A2234.toInt() else GrootBrand.SURFACE)
                    setStroke(dp(if (hasFocus) 2 else 1), if (hasFocus) GrootBrand.CYAN else 0x22FFFFFF)
                }
                titleView.setTextColor(if (hasFocus) Color.rgb(255, 229, 0) else Color.WHITE)
            }
            styleRow(false)
            row.setOnFocusChangeListener { _, hasFocus -> styleRow(hasFocus) }
            row.setOnClickListener { onClick(valueView) }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick(valueView)
                    true
                } else false
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            return row
        }

        val firstRow = addRow(
            "Retro Mode (CRT effects & fullscreen 4:3 crop)",
            if (displaySettings.retroMode) "ON" else "OFF"
        ) { valueView ->
            val next = !displaySettings.retroMode
            displaySettings.retroMode = next
            valueView.text = if (next) "ON" else "OFF"
            applyRetroMode()
        }

        addRow(
            "Closed captions (subtitles)",
            if (displaySettings.captionsEnabled) "ON" else "OFF"
        ) { valueView ->
            val next = !displaySettings.captionsEnabled
            displaySettings.captionsEnabled = next
            valueView.text = if (next) "ON" else "OFF"
            playback.setCaptions(next)
        }

        addRow(
            "Show off-air channels",
            if (displaySettings.showOffAirChannels) "ON" else "OFF"
        ) { valueView ->
            val next = !displaySettings.showOffAirChannels
            displaySettings.showOffAirChannels = next
            valueView.text = if (next) "ON" else "OFF"
            applyChannelVisibility(AppSession.channels.sortedBy { it.number }, schedules)
            grid.invalidate()
            showInfo()
            rebuildSchedules()
        }

        addRow("Stream quality", displaySettings.streamQuality.label) { valueView ->
            showStreamQuality { valueView.text = displaySettings.streamQuality.label }
        }

        addRow("Time zone", timeZoneSummary()) { valueView ->
            showTimeZone { valueView.text = timeZoneSummary() }
        }

        addRow("Playback controls HUD", "OPEN") {
            dialog.dismiss()
            showPlaybackControls()
        }

        addRow("Return to playing channel", playback.channel?.let { "CH ${it.number}" } ?: "") {
            returnTarget = playback.channel?.id
            returnToPlayingChannel()
            dialog.dismiss()
        }

        if (playback.previousChannel != null) {
            addRow("Previous channel", playback.previousChannel?.let { "CH ${it.number} ${it.name}" }.orEmpty()) {
                returnToPreviousChannel()
                returnTarget = playback.channel?.id
                dialog.dismiss()
            }
        }

        addRow("Refresh commercials only", "${AppSession.commercials.size} clips") { valueView ->
            refreshCommercials(valueView)
        }

        addRow("Shared schedule service", "CONFIGURE") {
            showCompanionSettings()
        }

        addRow("Force regenerate schedules", "REBUILD 3 DAYS") {
            AlertDialog.Builder(this@GuideActivity).setTitle("Regenerate station schedules?")
                .setMessage("Replaces today and the next two days on the shared service. Advertised times will change. Other TVs must restart to load the new lineup.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Regenerate") { _, _ ->
                    dialog.dismiss()
                    startActivity(Intent(this@GuideActivity, MainActivity::class.java)
                        .putExtra("force_regenerate", true))
                    finish()
                }.show()
        }

        addRow("Retry playback", "RETUNE") {
            playback.channel?.let { playback.tune(it, true) }
            dialog.dismiss()
        }

        addRow("About StaticGroot", "INFO") {
            AlertDialog.Builder(this@GuideActivity).setMessage(R.string.about_credit)
                .setPositiveButton("OK", null).show()
        }

        addRow("Sign out", "LOG OUT") {
            dialog.dismiss()
            SessionStore(this@GuideActivity).clearSession()
            startActivity(Intent(this@GuideActivity, MainActivity::class.java)); finish()
        }

        val scroll = ScrollView(this).apply {
            isScrollbarFadingEnabled = false
            addView(list)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        outer.addView(panel, FrameLayout.LayoutParams(-1, -1))
        dialog.setContentView(outer)

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
        firstRow.requestFocus()
    }

    /** Picks a rung of the quality ladder. Applies to the next stream, so the one playing is retuned. */
    private fun showStreamQuality(onUpdated: () -> Unit = {}) {
        val levels = io.github.madmax3553.staticgroot.jellyfin.StreamQuality.entries
        val labels = levels.map { "${it.label}\n${it.detail}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Stream quality")
            .setSingleChoiceItems(labels, levels.indexOf(displaySettings.streamQuality)) { dialog, which ->
                val chosen = levels[which]
                if (chosen != displaySettings.streamQuality) {
                    displaySettings.streamQuality = chosen
                    AppSession.client?.streamQuality = chosen
                    onUpdated()
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

    private fun showTimeZone(onUpdated: () -> Unit = {}) {
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
                    onUpdated()
                    showInfo()
                }
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).show()
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

    private fun refreshCommercials(statusLabel: TextView) {
        if (refreshingCommercials) return
        val client = AppSession.client ?: return
        refreshingCommercials = true
        statusLabel.text = "REFRESHING…"
        Thread {
            val result = runCatching { client.loadCommercials() }
            handler.post {
                refreshingCommercials = false
                if (isDestroyed) return@post
                result.onSuccess {
                    AppSession.commercials = it
                    statusLabel.text = "${it.size} CLIPS"
                }.onFailure { statusLabel.text = "FAILED — RETRY" }
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

    private fun makePillButton(textValue: String, onClick: (TextView) -> Unit): TextView = TextView(this).apply {
        text = textValue
        textSize = 13f
        typeface = resources.getFont(R.font.dmmono_regular)
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        setPadding(dp(16), dp(10), dp(16), dp(10))
        fun applyFocusStyle(focused: Boolean) {
            setTextColor(if (focused) Color.rgb(255, 229, 0) else GrootBrand.CYAN)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (focused) 0xFF1A2234.toInt() else GrootBrand.SURFACE)
                setStroke(dp(if (focused) 2 else 1), if (focused) GrootBrand.CYAN else 0x4429F2FF)
            }
        }
        applyFocusStyle(false)
        setOnFocusChangeListener { _, hasFocus -> applyFocusStyle(hasFocus) }
        setOnClickListener { onClick(this) }
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onClick(this)
                true
            } else false
        }
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
