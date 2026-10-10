package io.github.madmax3553.staticgroot.channels

/*
 * Pools for every channel in channels.json, not only the music ones. Each pool is the
 * output of RuleFilter, then the time-of-day restrictions from Channel.filteredPool,
 * the same two steps tvOS takes before scheduling.
 */
data class ChannelRuntime(
    val definition: ChannelDefinition,
    /** The pool the scheduler packs. Already time-restricted for [hour]. */
    val pool: List<MediaItem>,
) {
    val id: Int get() = definition.id
    val number: Int get() = definition.number
    val name: String get() = definition.name
    val isPremiere: Boolean get() = definition.premiere
}

object ChannelPools {

    fun buildAll(
        config: ChannelConfig,
        items: List<MediaItem>,
        enrichment: Map<String, MediaEnrichment> = emptyMap(),
        memberships: ChannelMemberships = ChannelMemberships.EMPTY,
        clock: FilterClock,
    ): List<ChannelRuntime> {
        val cached = items.map { buildFilterCache(it, enrichment[it.id]) }
        val hour = clock.now.hour
        return config.channels.map { ch ->
            val matched = RuleFilter.filter(
                cached = cached,
                rules = ch.rules,
                channelID = ch.id,
                category = ch.category,
                memberships = memberships,
                exclusiveRules = config.exclusiveRules,
                clock = clock,
            )
            ChannelRuntime(ch, filteredPool(matched, ch.timeRestrictions, hour))
        }
    }
}
