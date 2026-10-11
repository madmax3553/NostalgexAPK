package io.github.madmax3553.staticgroot

import android.content.Context

/** Device-local display preferences. Retro mode defaults to on, like the Swift app. */
class DisplaySettings(context: Context) {
    private val prefs = context.getSharedPreferences("staticgroot_display", Context.MODE_PRIVATE)

    var retroMode: Boolean
        get() = prefs.getBoolean("retro_mode", true)
        set(value) { prefs.edit().putBoolean("retro_mode", value).apply() }

    var showOffAirChannels: Boolean
        get() = prefs.getBoolean("show_off_air", false)
        set(value) { prefs.edit().putBoolean("show_off_air", value).apply() }

    var captionsEnabled: Boolean
        get() = prefs.getBoolean("captions_enabled", false)
        set(value) { prefs.edit().putBoolean("captions_enabled", value).apply() }
}
