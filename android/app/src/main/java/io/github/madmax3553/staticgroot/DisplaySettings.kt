package io.github.madmax3553.staticgroot

import android.content.Context
import android.provider.Settings
import java.time.ZoneId
import java.util.TimeZone

/** North American time zones for the guide clock. Auto reads the device zone (including Fire OS notifiedTimeZone). */
enum class NorthAmericaTimeZone(val id: String, val label: String, val zoneId: String?) {
    AUTO("auto", "Auto (Device local time)", null),
    PACIFIC("pacific", "Pacific Time (PT)", "America/Vancouver"),
    MOUNTAIN("mountain", "Mountain Time (MT)", "America/Edmonton"),
    CENTRAL("central", "Central Time (CT)", "America/Chicago"),
    EASTERN("eastern", "Eastern Time (ET)", "America/Toronto"),
    ATLANTIC("atlantic", "Atlantic Time (AT)", "America/Halifax"),
    NEWFOUNDLAND("newfoundland", "Newfoundland Time (NT)", "America/St_Johns");

    fun resolve(context: Context, stationZone: ZoneId): ZoneId {
        if (zoneId != null) return ZoneId.of(zoneId)
        return deviceZoneId(context, stationZone)
    }

    companion object {
        private val GMT_IDS = setOf("GMT", "UTC", "Etc/GMT", "Etc/UTC", "GMT0")
        private val ATZ_REGEX = Regex(""""updatedTimeZone"\s*:\s*"([^"]+)"""")

        fun fromId(id: String?): NorthAmericaTimeZone = entries.firstOrNull { it.id == id } ?: AUTO

        /**
         * Reads the device's local timezone. On Amazon Fire OS, `persist.sys.timezone` (read by
         * `ZoneId.systemDefault()`) can remain `GMT` even when Fire OS automatic timezone has
         * resolved the real zone in `Settings.Global` (`notifiedTimeZone` / `atz_response_provider`).
         */
        fun deviceZoneId(context: Context, fallback: ZoneId): ZoneId {
            val sys = ZoneId.systemDefault()
            if (sys.id !in GMT_IDS) return sys
            val resolver = context.contentResolver
            val notified = runCatching { Settings.Global.getString(resolver, "notifiedTimeZone") }
                .getOrNull()?.trim().orEmpty()
            if (notified.isNotEmpty() && notified !in GMT_IDS) {
                runCatching { return ZoneId.of(notified) }
            }
            val atz = runCatching { Settings.Global.getString(resolver, "atz_response_provider") }
                .getOrNull().orEmpty()
            val extracted = ATZ_REGEX.find(atz)?.groupValues?.getOrNull(1)?.replace("\\/", "/").orEmpty()
            if (extracted.isNotEmpty() && extracted !in GMT_IDS) {
                runCatching { return ZoneId.of(extracted) }
            }
            return fallback
        }
    }
}

/** Device-local display preferences. Retro mode defaults to on, like the Swift app. */
class DisplaySettings(private val context: Context) {
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

    var streamQuality: io.github.madmax3553.staticgroot.jellyfin.StreamQuality
        get() = io.github.madmax3553.staticgroot.jellyfin.StreamQuality.fromId(prefs.getString("stream_quality", null))
        set(value) { prefs.edit().putString("stream_quality", value.id).apply() }

    var timeZone: NorthAmericaTimeZone
        get() = NorthAmericaTimeZone.fromId(prefs.getString("time_zone", null))
        set(value) { prefs.edit().putString("time_zone", value.id).apply() }

    fun resolveZoneId(stationZone: ZoneId): ZoneId = timeZone.resolve(context, stationZone)

    fun displayTimeZone(stationZone: ZoneId): TimeZone =
        TimeZone.getTimeZone(resolveZoneId(stationZone))
}
