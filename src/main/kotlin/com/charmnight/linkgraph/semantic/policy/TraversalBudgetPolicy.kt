package com.charmnight.linkgraph.semantic.policy

/**
 * 控制语义分析遍历深度与规模的预算策略。
 *
 * 完整遍历调用与资源关系可能在大型项目上指数级爆炸，
 * 因此需要为各维度设置上限：向下游多少层、向上游多少层、每个节点最多展开多少关联。
 * 上层可按"快速预览 vs 深度分析"切换不同 Policy 实例。
 */
data class TraversalBudgetPolicy(
    /** 限制向下游遍历的最大深度。例如 4 表示从当前节点出发最多走 4 步调用。 */
    val maxDownstreamDepth: Int = 4,
    /** 限制向上游回溯的最大深度。 */
    val maxUpstreamDepth: Int = 2,
    /** 限制每个语义单元最多展开的调用数。 */
    val maxInvocationsPerUnit: Int = 12,
    /** 限制每个语义单元最多关联的资源数。 */
    val maxRelatedResourcesPerUnit: Int = 8,
)
