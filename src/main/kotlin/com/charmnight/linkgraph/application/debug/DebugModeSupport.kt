package com.charmnight.linkgraph.application.debug

/** 解析 `dense12` 这类调试模式参数的正则表达式。括号中捕获一个数字，表示允许展示的节点数。 */
private val DENSE_DEBUG_MODE_REGEX = Regex("^dense(\\d+)$")

/**
 * 从调试模式字符串中提取允许展示的节点数量。
 *
 * 形如 `dense50` 的字符串会被解析为整数 50；非匹配格式返回 null 表示"未指定"。
 * 解析出的数量被约束到 [2, 400] 区间，避免过大值导致渲染卡顿、过小值让示例图不可读。
 */
internal fun parseDenseDebugNodeCount(mode: String): Int? = DENSE_DEBUG_MODE_REGEX
    .matchEntire(mode)
    ?.groupValues
    ?.getOrNull(1)
    ?.toIntOrNull()
    ?.coerceIn(2, 400)
