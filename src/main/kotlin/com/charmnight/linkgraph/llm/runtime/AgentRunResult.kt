package com.charmnight.linkgraph.llm.runtime

/**
 * coordinator 对外暴露的统一运行结果。
 *
 * output 是 capability 收敛后的业务结果，finalState 用于日志、UI 和回归验证。
 * 把"业务输出"与"运行状态"分开返回，让上游既能拿到正常使用的产物，
 * 也能在排查问题时观察完整的运行轨迹。
 *
 * @param O 业务输出的类型，由具体 capability 决定
 */
data class AgentRunResult<O>(
    /** 运行结束时的最终状态。 */
    val finalState: AgentRunState,
    /** 本轮可安全暴露给 workflow/UI 的产物摘要。 */
    val artifactSummaries: List<AgentRunArtifactSummary> = emptyList(),
    /** capability 最终产出；可能为 null（例如运行失败或无可输出业务结果时）。 */
    val output: O?,
)
