package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy

internal data class InteractiveProjectionSettings(
    /** 当前可见节点上限。 */
    val maxVisibleNodes: Int = 26,
    /** 当前可见边上限。 */
    val maxVisibleEdges: Int = 40,
    /** 上游展开深度。 */
    val upstreamDepth: Int = 2,
    /** 下游展开深度。 */
    val downstreamDepth: Int = 2,
    /** 单方向邻居展开上限。 */
    val maxNeighborsPerDirection: Int = 5,
) {
    /**
     * 针对摘要节点放宽当前投影预算。
     */
    fun expandFor(node: GraphNode): InteractiveProjectionSettings {
        val direction = node.metadata["linkGraph.overflow.direction"]
        return copy(
            maxVisibleNodes = (maxVisibleNodes + 18).coerceAtMost(180),
            maxVisibleEdges = (maxVisibleEdges + 28).coerceAtMost(260),
            upstreamDepth = if (direction == "UPSTREAM") upstreamDepth + 1 else upstreamDepth,
            downstreamDepth = if (direction == "DOWNSTREAM") downstreamDepth + 1 else downstreamDepth,
            maxNeighborsPerDirection = (maxNeighborsPerDirection + 4).coerceAtMost(48),
        )
    }

    /**
     * 转换为前端投影策略。
     */
    fun toProjectionPolicy(): ProjectionPolicy {
        return ProjectionPolicy(
            maxVisibleNodes = maxVisibleNodes,
            maxVisibleEdges = maxVisibleEdges,
            enableOverflowSummary = true,
        )
    }

    /**
     * 转换为语义分析遍历预算。
     */
    fun toTraversalBudgetPolicy(): TraversalBudgetPolicy {
        return TraversalBudgetPolicy(
            maxDownstreamDepth = downstreamDepth,
            maxUpstreamDepth = upstreamDepth,
            maxInvocationsPerUnit = maxNeighborsPerDirection,
            maxRelatedResourcesPerUnit = maxNeighborsPerDirection,
        )
    }
}

internal data class CurrentMethodNode(
    /** 当前方法对应的图节点。 */
    val node: GraphNode,
    /** 方法签名。 */
    val methodSignature: String,
    /** 方法展示名称。 */
    val methodDisplayName: String,
)

internal data class AnalysisExecutionResult(
    /** 原始语义分析结果。 */
    val analysisResult: SemanticAnalysisResult,
    /** 投影后的展示结果。 */
    val outcome: AnalysisOutcome,
)

internal data class AnalysisOutcomeAsyncResult(
    /** 成功时返回的分析结果。 */
    val result: AnalysisExecutionResult? = null,
    /** 是否已被取消。 */
    val cancelled: Boolean = false,
    /** 失败异常。 */
    val failure: Throwable? = null,
) {
    companion object {
        /** 构造成功结果。 */
        fun success(result: AnalysisExecutionResult?) = AnalysisOutcomeAsyncResult(result = result)

        /** 构造取消结果。 */
        fun cancelled() = AnalysisOutcomeAsyncResult(cancelled = true)

        /** 构造失败结果。 */
        fun failure(throwable: Throwable) = AnalysisOutcomeAsyncResult(failure = throwable)
    }
}
