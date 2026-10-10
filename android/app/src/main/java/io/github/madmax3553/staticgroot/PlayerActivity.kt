package io.github.madmax3553.staticgroot

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/*
 * Full-screen playback of what the channel is showing now, starting at the same point in the
 * programme the guide showed. Opened from the guide with OK.
 */
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val index = intent.getIntExtra(EXTRA_POOL_INDEX, -1)
        val runtime = AppSession.channels.getOrNull(index)
        val client = AppSession.client
        val now = System.currentTimeMillis() / 1000
        val sched = runtime?.let { AppSession.scheduleFor(it, now) }
        val np = sched?.nowPlaying

        val playerView = PlayerView(this).apply { useController = true }
        label = TextView(this).apply {
            textSize = 22f
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xAA000000.toInt())
            setTextColor(0xFF7CFF7C.toInt())
            text = if (runtime != null && np != null) {
                "CH ${runtime.number} ${runtime.name}: ${np.item.title}"
            } else {
                "Nothing is on this channel right now"
            }
        }
        setContentView(FrameLayout(this).apply {
            addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        })

        if (client == null || np == null || sched == null) return
        val seekMs = sched.elapsedSeconds * 1000

        Thread {
            try {
                val resolution = client.resolvePlayback(np.item.id)
                runOnUiThread {
                    if (resolution == null) {
                        label.text = "The server offered no playable stream for ${np.item.title}"
                    } else {
                        val p = ExoPlayer.Builder(this).build()
                        player = p
                        playerView.player = p
                        p.setMediaItem(ExoMediaItem.fromUri(resolution.url))
                        p.prepare()
                        p.seekTo(seekMs)
                        p.playWhenReady = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { label.text = e.message ?: "Playback failed" }
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_POOL_INDEX = "pool_index"
    }
}
