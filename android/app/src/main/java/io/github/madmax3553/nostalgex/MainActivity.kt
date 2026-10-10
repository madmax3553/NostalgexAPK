package io.github.madmax3553.nostalgex

import android.app.Activity
import android.os.Bundle

/**
 * Launch activity for Android TV. Placeholder only: the guide, playback and
 * server screens are ported from the tvOS app (see CLAUDE.md, Android TV port).
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
