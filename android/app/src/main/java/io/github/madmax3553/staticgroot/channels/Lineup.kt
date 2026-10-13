package io.github.madmax3553.staticgroot.channels

import java.time.LocalDate

/**
 * The channels the guide shows: the static lineup without seasonal-bundle channels, the one
 * in-season channel, and the channels derived from the library.
 */
object Lineup {

    data class Result(
        val channels: List<ChannelRuntime>,
        /** Seasonal and library channels, for the shared station and for the log. */
        val generated: List<GeneratedChannel>,
        /** What to save for next launch. Seasonal channels are not saved: the calendar decides them. */
        val records: List<GeneratedRecord>,
    )

    fun build(
        config: ChannelConfig,
        static: List<ChannelRuntime>,
        items: List<MediaItem>,
        previous: List<GeneratedRecord>,
        date: LocalDate,
    ): Result {
        val season = SeasonalChannels.apply(config, static, date)
        val used = (season.remaining.map { it.number } + season.seasonal.map { it.definition.number }).toSet()
        val plan = LibraryChannelPlanner.plan(items, season.remaining + season.seasonal.map(::runtimeOf), previous, used)
        val generated = season.seasonal + plan.channels
        val all = (season.remaining + generated.map(::runtimeOf)).sortedBy { it.number }
        return Result(all, generated, plan.records)
    }

    fun runtimeOf(g: GeneratedChannel) = ChannelRuntime(g.definition, g.pool)
}
