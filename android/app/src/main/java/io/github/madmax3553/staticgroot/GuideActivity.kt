package io.github.madmax3553.staticgroot

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.util.Log
import androidx.media3.ui.PlayerView
import io.github.madmax3553.staticgroot.channels.ChannelRuntime
import io.github.madmax3553.staticgroot.schedule.ChannelSchedule
import io.github.madmax3553.staticgroot.schedule.ScheduleEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The guide: a channel list on the left, and on the right a muted live preview of the focused
 * channel, seeked to what is on now, with now and next details. D-pad only. Pressing OK opens
 * the full-screen player at the same point in the programme.
 *
 * Skin: black background, VT323 phosphor text, and each channel's own colour from channels.json.
 * The CRT overlay and static transition from tvOS are not ported yet.
 */
class GuideActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var previewGeneration = 0
    private lateinit var list: ListView
    private lateinit var preview: PlayerView
    private lateinit var details: TextView
    private lateinit var header: TextView
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val refresh = object : Runnable {
        override fun run() {
            showFocused()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val loadPreview = Runnable { startPreview() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val channels = AppSession.channels
        val typeface = resources.getFont(R.font.vt323_regular)

        header = TextView(this).apply {
            text = "STATICGROOT GUIDE"
            setTextColor(PHOSPHOR)
            textSize = 34f
            this.typeface = typeface
            setPadding(0, 0, 0, 12)
        }

        list = ListView(this).apply {
            isFocusable = true
            adapter = ChannelAdapter(channels, typeface)
            divider = null
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    showFocused()
                    handler.removeCallbacks(loadPreview)
                    handler.postDelayed(loadPreview, PREVIEW_DEBOUNCE_MS)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                openFullScreen(position)
            }
        }

        preview = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
        }

        details = TextView(this).apply {
            setTextColor(PHOSPHOR)
            textSize = 26f
            this.typeface = typeface
            setPadding(0, 16, 0, 0)
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f))
            addView(details, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(32, 32, 32, 32)
            gravity = Gravity.FILL
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
        })

        if (channels.isEmpty()) {
            details.text = "No channels. Sign in first."
        } else {
            list.requestFocus()
            showFocused()
            handler.postDelayed(loadPreview, PREVIEW_DEBOUNCE_MS)
        }
        handler.postDelayed(refresh, REFRESH_MS)
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun focused(): ChannelRuntime? =
        AppSession.channels.getOrNull(list.selectedItemPosition.takeIf { it >= 0 } ?: 0)

    /** What the details panel shows for the focused channel. Recomputed on focus and every refresh. */
    private fun showFocused() {
        val ch = focused() ?: return
        val now = System.currentTimeMillis() / 1000
        val sched = AppSession.scheduleFor(ch, now)
        val np = sched?.nowPlaying
        val text = StringBuilder()
        text.append("CH ${ch.number}  ${ch.name}\n")
        text.append(clock.format(Date()))
        text.append("\n\n")
        if (np == null) {
            text.append("NO PROGRAMME")
        } else {
            text.append("ON NOW  ${titleOf(np)}\n")
            val total = np.durationSeconds() / 60
            val elapsed = sched.elapsedSeconds / 60
            text.append("${elapsed} of ${total} min\n")
            sched.upNext?.let { text.append("\nUP NEXT  ${titleOf(it)}") }
        }
        details.text = text.toString()
    }

    private fun startPreview() {
        val ch = focused() ?: return
        val now = System.currentTimeMillis() / 1000
        val sched = AppSession.scheduleFor(ch, now) ?: return
        val np = sched.nowPlaying ?: return
        val client = AppSession.client ?: return
        val gen = ++previewGeneration
        val seekMs = sched.elapsedSeconds * 1000
        Thread {
            try {
                val resolution = client.resolvePlayback(np.item.id)
                handler.post {
                    if (gen != previewGeneration || isFinishing) return@post
                    if (resolution == null) return@post
                    player?.release()
                    val p = ExoPlayer.Builder(this).build()
                    p.logStateAndErrors("StaticGroot")
                    player = p
                    preview.player = p
                    p.volume = 0f
                    p.setMediaItem(androidx.media3.common.MediaItem.fromUri(resolution.url))
                    p.prepare()
                    p.seekTo(seekMs)
                    p.playWhenReady = true
                }
            } catch (e: Exception) {
                // The preview is best-effort. The details panel still shows the schedule.
            }
        }.start()
    }

    private fun openFullScreen(position: Int) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_POOL_INDEX, position),
        )
    }

    private fun titleOf(e: ScheduleEntry): String {
        val item = e.item
        val tag = item.seTag?.let { " $it" }.orEmpty()
        val ep = item.episodeTitle?.let { " - $it" }.orEmpty()
        return "${item.title}$tag$ep"
    }

    private fun ChannelSchedule.durationOf(e: ScheduleEntry) = e.durationSeconds()

    private fun ScheduleEntry.durationSeconds(): Long = endUnix - startUnix

    /** One row per channel, in the channel's own colour. */
    private inner class ChannelAdapter(
        private val rows: List<ChannelRuntime>,
        private val typeface: Typeface,
    ) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ch = rows[position]
            val tv = (convertView as? TextView) ?: TextView(this@GuideActivity).apply {
                this.typeface = typeface
                textSize = 26f
                setPadding(16, 10, 16, 10)
            }
            val label = if (ch.pool.isEmpty()) "CH ${ch.number}  ${ch.name}  (empty)" else "CH ${ch.number}  ${ch.name}"
            tv.text = label
            tv.setTextColor(ch.color())
            return tv
        }
    }

    private fun ChannelRuntime.color(): Int = runCatching {
        Color.parseColor(definition.colorHex)
    }.getOrDefault(PHOSPHOR)

    companion object {
        private const val PHOSPHOR = 0xFF7CFF7C.toInt()
        private const val REFRESH_MS = 15_000L
        private const val PREVIEW_DEBOUNCE_MS = 600L
    }
}
