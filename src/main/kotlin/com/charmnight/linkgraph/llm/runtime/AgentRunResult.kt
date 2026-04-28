package com.charmnight.linkgraph.llm.runtime

/**
 * coordinator 对外暴露的统一运行结果。
 * output 是 capability 收敛后的业务结果，finalState 用于日志、UI 和回归验证。
 */
data class AgentRunResult<O>(
    /** 运行结束时的最终状态。 */
    val finalState: AgentRunState,
    /** 本轮可安全暴露给 workflow/UI 的产物摘要。 */
    val artifactSummaries: List<AgentRunArtifactSummary> = emptyList(),
    /** capability 最终产出。 */
    val output: O?,
)
