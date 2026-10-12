package io.github.madmax3553.staticgroot.channels

/*
 * Builds the pools for the music-video channels, the channels whose rules say
 * source "musicVideo". Uses the same RuleFilter as every other channel.
 *
 * Not yet ported: the daily schedule. A pool says what a channel may show. It does not say
 * what is on now. Until the schedule lands, the caller must not present a pool entry as the
 * programme that is on.
 */
data class ChannelPool(
    val channel: ChannelDefinition,
    val items: List<MediaItem>,
)

object MusicChannels {

    /** True when the channel's rules restrict it to music videos. */
    fun isMusicChannel(channel: ChannelDefinition): Boolean =
        channel.rules?.source == LibrarySource.MUSIC_VIDEO.raw

    fun build(
        config: ChannelConfig,
        items: List<MediaItem>,
        enrichment: Map<String, MediaEnrichment> = emptyMap(),
        memberships: ChannelMemberships = ChannelMemberships.EMPTY,
        clock: FilterClock,
    ): List<ChannelPool> {
        val cached = items.map { buildFilterCache(it, enrichment.forItem(it)) }
        return config.channels
            .filter(::isMusicChannel)
            .map { ch ->
                ChannelPool(
                    channel = ch,
                    items = RuleFilter.filter(
                        cached = cached,
                        rules = ch.rules,
                        channelID = ch.id,
                        category = ch.category,
                        memberships = memberships,
                        exclusiveRules = config.exclusiveRules,
                        clock = clock,
                    ),
                )
            }
    }
}
