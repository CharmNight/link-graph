package com.charmnight.linkgraph.application.debug

/** 解析 `dense12` 这类调试模式参数的正则表达式。 */
private val DENSE_DEBUG_MODE_REGEX = Regex("^dense(\\d+)$")

/** 从调试模式字符串中提取允许展示的节点数量。 */
internal fun parseDenseDebugNodeCount(mode: String): Int? = DENSE_DEBUG_MODE_REGEX
    .matchEntire(mode)
    ?.groupValues
    ?.getOrNull(1)
    ?.toIntOrNull()
    ?.coerceIn(2, 400)
