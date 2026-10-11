package io.github.madmax3553.staticgroot

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Compatibility entry for old activity intents. Fullscreen now belongs to the tuner. */
class PlayerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, GuideActivity::class.java))
        finish()
    }

    companion object { const val EXTRA_POOL_INDEX = "pool_index" }
}
