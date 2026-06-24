package com.charmnight.linkgraph.application.workflow.subject

import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.projection.GraphProjectionMetadata
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy

/**
 * 交互式主题图投影设置。
 *
 * 保存当前可见节点/边上限、上下游展开深度以及单方向邻居展开上限，
 * 用户在工具窗口内点开摘要节点时会通过 [expandFor] 放宽预算并重新投影。
 */
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
        val direction = node.metadata[GraphProjectionMetadata.Overflow.DIRECTION]
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

/**
 * 当前方法节点上下文。
 *
 * 在主题图工作流中保存被选中的"当前方法"信息，包含对应的图节点、
 * 方法签名以及面向用户展示的名称，便于后续投影和导航。
 */
internal data class CurrentMethodNode(
    /** 当前方法对应的图节点。 */
    val node: GraphNode,
    /** 方法签名。 */
    val methodSignature: String,
    /** 方法展示名称。 */
    val methodDisplayName: String,
)

/**
 * 单次语义分析的执行结果。
 *
 * 同时携带原始语义分析结果和投影后用于展示的 outcome，方便上层既能拿到
 * 完整的语义事实，又能直接渲染投影图。
 */
internal data class AnalysisExecutionResult(
    /** 原始语义分析结果。 */
    val analysisResult: SemanticAnalysisResult,
    /** 投影后的展示结果。 */
    val outcome: AnalysisOutcome,
)

/**
 * 异步语义分析执行结果封装。
 *
 * 通过 success/cancelled/failure 三种工厂方法构造，分别表示分析成功返回结果、
 * 被用户取消、以及执行过程中抛出异常，调用方据此走不同分支。
 */
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
