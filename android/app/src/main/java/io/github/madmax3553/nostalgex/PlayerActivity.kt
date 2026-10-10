package io.github.madmax3553.nostalgex

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
 * Plays the first item of a channel pool. Not scheduled yet: the schedule port decides
 * what is on now. Until then this screen is for testing the Jellyfin stream path only.
 */
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val index = intent.getIntExtra(EXTRA_POOL_INDEX, -1)
        val pool = AppSession.pools.getOrNull(index)
        val client = AppSession.client
        val item = pool?.items?.firstOrNull()

        val playerView = PlayerView(this).apply { useController = true }
        label = TextView(this).apply {
            textSize = 20f
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xAA000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            text = if (pool != null && item != null) {
                "CH ${pool.channel.number} ${pool.channel.name}: ${item.title} (not scheduled)"
            } else {
                "Nothing to play"
            }
        }
        setContentView(FrameLayout(this).apply {
            addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        })

        if (client == null || item == null) return

        Thread {
            try {
                val resolution = client.resolvePlayback(item.id)
                runOnUiThread {
                    if (resolution == null) {
                        label.text = "The server offered no playable stream for ${item.title}"
                    } else {
                        val p = ExoPlayer.Builder(this).build()
                        player = p
                        playerView.player = p
                        p.setMediaItem(ExoMediaItem.fromUri(resolution.url))
                        p.prepare()
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
