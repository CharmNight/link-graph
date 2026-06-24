package com.charmnight.linkgraph.usage

/**
 * 类使用搜索的预算与规范化工具。
 *
 * 把"搜索结果最多多少组、每组多少条"等约束集中在此处，
 * 避免各调用方各自写魔法数字。同时提供 clamp / normalize / canRequestMore 等工具，
 * 让 UI 可以判断"是否还能再请求更多结果"。
 */
object ClassUsageSearchLimits {
    /** 默认每组结果数。 */
    const val DEFAULT_USAGE_GROUPS: Int = 50
    /** 默认每条结果数。 */
    const val DEFAULT_USAGE_ENTRIES: Int = 200
    /** 每组结果的上限。 */
    const val MAX_USAGE_GROUPS: Int = 200
    /** 每条结果的上限。 */
    const val MAX_USAGE_ENTRIES: Int = 1000

    /** 把组数约束到合法区间。 */
    fun clampUsageGroups(value: Int): Int = value.coerceIn(0, MAX_USAGE_GROUPS)

    /** 把条目数约束到合法区间。 */
    fun clampUsageEntries(value: Int): Int = value.coerceIn(0, MAX_USAGE_ENTRIES)

    /**
     * 规范化搜索选项：把超范围的字段裁剪到合法区间。
     * 用于在请求进入底层搜索之前做参数消毒。
     */
    fun normalize(options: ClassUsageSearchOptions): ClassUsageSearchOptions =
        options.copy(
            maxUsageGroups = clampUsageGroups(options.maxUsageGroups),
            maxUsageEntries = clampUsageEntries(options.maxUsageEntries),
        )

    /**
     * 判断是否还能向用户暴露"加载更多"的入口。
     *
     * 同时满足两个条件才返回 true：
     * - 本次结果已截断（truncated=true），说明还有未返回的内容；
     * - 当前 maxUsageGroups 或 maxUsageEntries 至少有一项还能再放大。
     *
     * @param truncated 本次搜索是否被截断
     * @param maxUsageGroups 当前请求的组数上限
     * @param maxUsageEntries 当前请求的条目上限
     */
    fun canRequestMore(
        truncated: Boolean,
        maxUsageGroups: Int,
        maxUsageEntries: Int,
    ): Boolean =
        truncated &&
            (maxUsageGroups < MAX_USAGE_GROUPS || maxUsageEntries < MAX_USAGE_ENTRIES)
}
