package com.charmnight.linkgraph.agent.runtime

/**
 * 对外暴露给 workflow 和 UI 的最小产物摘要。
 *
 * 这里只保留可展示、可追踪的信息，避免把 runtime 内部对象直接泄漏到状态层。
 * 当 UI 需要查看完整产物时，再凭 artifactId 去 store 取回。
 */
data class AgentRunArtifactSummary(
    /** 产物稳定标识。 */
    val artifactId: String,
    /** 产物类型名称。 */
    val artifactType: String,
    /** 产物标题。 */
    val title: String,
    /** 产物说明。 */
    val description: String? = null,
)
