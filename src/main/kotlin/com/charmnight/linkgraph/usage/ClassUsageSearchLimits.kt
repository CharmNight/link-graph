package com.charmnight.linkgraph.usage

object ClassUsageSearchLimits {
    const val DEFAULT_USAGE_GROUPS: Int = 50
    const val DEFAULT_USAGE_ENTRIES: Int = 200
    const val MAX_USAGE_GROUPS: Int = 200
    const val MAX_USAGE_ENTRIES: Int = 1000

    fun clampUsageGroups(value: Int): Int = value.coerceIn(0, MAX_USAGE_GROUPS)

    fun clampUsageEntries(value: Int): Int = value.coerceIn(0, MAX_USAGE_ENTRIES)

    fun normalize(options: ClassUsageSearchOptions): ClassUsageSearchOptions =
        options.copy(
            maxUsageGroups = clampUsageGroups(options.maxUsageGroups),
            maxUsageEntries = clampUsageEntries(options.maxUsageEntries),
        )

    fun canRequestMore(
        truncated: Boolean,
        maxUsageGroups: Int,
        maxUsageEntries: Int,
    ): Boolean =
        truncated &&
            (maxUsageGroups < MAX_USAGE_GROUPS || maxUsageEntries < MAX_USAGE_ENTRIES)
}
