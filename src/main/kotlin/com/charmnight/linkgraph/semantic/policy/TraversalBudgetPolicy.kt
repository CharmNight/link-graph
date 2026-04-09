package com.charmnight.linkgraph.semantic.policy

/**
 * 控制语义分析遍历深度与规模的预算策略。
 */
data class TraversalBudgetPolicy(
    /** 限制向下游遍历的最大深度。 */
    val maxDownstreamDepth: Int = 4,
    /** 限制向上游回溯的最大深度。 */
    val maxUpstreamDepth: Int = 2,
    /** 限制每个语义单元最多展开的调用数。 */
    val maxInvocationsPerUnit: Int = 12,
    /** 限制每个语义单元最多关联的资源数。 */
    val maxRelatedResourcesPerUnit: Int = 8,
)
