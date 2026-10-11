package io.github.madmax3553.staticgroot

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Logs player state and errors under [tag]. Stream URLs are never logged. */
internal fun ExoPlayer.logStateAndErrors(tag: String) {
    addListener(object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w(tag, "player error ${error.errorCodeName}: ${error.message}", error)
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.i(tag, "player state=$playbackState playWhenReady=$playWhenReady")
        }
    })
}
