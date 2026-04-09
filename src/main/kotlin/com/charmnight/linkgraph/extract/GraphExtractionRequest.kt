package com.charmnight.linkgraph.extract

import com.intellij.psi.PsiMethod

/**
 * 表示一次链路提取请求。
 */
data class GraphExtractionRequest(
    /** 保存本次提取的入口方法列表。 */
    val entryMethods: List<PsiMethod>,
    /** 保存提取阶段的预算限制。 */
    val limits: GraphExtractionLimits = GraphExtractionLimits(),
)

/**
 * 当前方法链路提取的安全边界。
 * 这些限制发生在“真实提图阶段”，不是前端展示裁剪。
 */
data class GraphExtractionLimits(
    /** 限制最多提取的方法节点数量。 */
    val maxMethodNodes: Int = 160,
    /** 限制向下游展开调用深度。 */
    val maxCallDepthDownstream: Int = 4,
    /** 限制向上游回溯调用深度。 */
    val maxCallDepthUpstream: Int = 2,
    /** 限制每个方法最多展开的被调方法数。 */
    val maxCallsPerMethod: Int = 12,
    /** 限制每个方法最多展开的调用者数量。 */
    val maxCallersPerMethod: Int = 10,
    /** 限制每个方法最多展开的解析器补充方法数。 */
    val maxResolverMethodsPerMethod: Int = 8,
) {
    /**
     * 对配置项做安全兜底，避免预算值为负数。
     */
    fun sanitized(): GraphExtractionLimits {
        // 所有预算都至少保留 0 或 1，防止错误配置直接破坏提取流程。
        return copy(
            maxMethodNodes = maxMethodNodes.coerceAtLeast(1),
            maxCallDepthDownstream = maxCallDepthDownstream.coerceAtLeast(0),
            maxCallDepthUpstream = maxCallDepthUpstream.coerceAtLeast(0),
            maxCallsPerMethod = maxCallsPerMethod.coerceAtLeast(0),
            maxCallersPerMethod = maxCallersPerMethod.coerceAtLeast(0),
            maxResolverMethodsPerMethod = maxResolverMethodsPerMethod.coerceAtLeast(0),
        )
    }
}
