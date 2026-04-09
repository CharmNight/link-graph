package com.charmnight.linkgraph.semantic.policy

/**
 * 控制分析结果投影到可见图时的预算策略。
 */
data class ProjectionPolicy(
    /** 限制最多可见的节点数量。 */
    val maxVisibleNodes: Int = 26,
    /** 限制最多可见的边数量。 */
    val maxVisibleEdges: Int = 40,
    /** 控制是否启用溢出摘要节点。 */
    val enableOverflowSummary: Boolean = true,
)
