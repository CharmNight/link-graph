package com.charmnight.linkgraph.semantic.policy

/**
 * 控制分析结果投影到可见图时的预算策略。
 *
 * 投影是把完整语义分析结果裁剪为可视化图的过程。本结构集中维护各种预算阈值，
 * 上层只需切换不同的 Policy 实例即可改变投影规模，而无需修改投影器代码。
 */
data class ProjectionPolicy(
    /** 限制最多可见的节点数量。超出部分被聚合为 overflow 摘要节点或直接隐藏。 */
    val maxVisibleNodes: Int = 26,
    /** 限制最多可见的边数量。 */
    val maxVisibleEdges: Int = 40,
    /** 控制是否启用溢出摘要节点。开启时被裁掉的节点会合并为一个"还有 N 项"节点展示。 */
    val enableOverflowSummary: Boolean = true,
)
