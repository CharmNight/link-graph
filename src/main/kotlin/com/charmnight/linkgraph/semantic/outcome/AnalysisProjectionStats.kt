package com.charmnight.linkgraph.semantic.outcome

/**
 * 记录分析结果投影阶段的统计信息。
 */
data class AnalysisProjectionStats(
    /** 记录被隐藏的节点数量。 */
    val hiddenNodeCount: Int = 0,
    /** 记录被隐藏的边数量。 */
    val hiddenEdgeCount: Int = 0,
    /** 标记结果是否因为预算限制而被截断。 */
    val truncated: Boolean = false,
)
