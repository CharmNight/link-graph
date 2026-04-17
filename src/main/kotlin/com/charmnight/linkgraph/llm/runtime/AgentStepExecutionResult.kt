package com.charmnight.linkgraph.llm.runtime

/**
 * 描述单个 step 执行后的控制流结果。
 * 第一阶段先支持继续、完成、失败三种分支，足够承载“代理到旧 service”的 runtime 外壳。
 */
sealed class AgentStepExecutionResult(
    open val state: AgentRunState,
) {
    /** 继续下一步。 */
    data class Continue(
        override val state: AgentRunState,
    ) : AgentStepExecutionResult(state)

    /** 已完成本轮 run。 */
    data class Complete(
        override val state: AgentRunState,
    ) : AgentStepExecutionResult(state)

    /** 当前 run 失败。 */
    data class Fail(
        override val state: AgentRunState,
    ) : AgentStepExecutionResult(state)

    companion object {
        fun continueWith(state: AgentRunState): AgentStepExecutionResult = Continue(state)

        fun complete(state: AgentRunState): AgentStepExecutionResult = Complete(state)

        fun fail(state: AgentRunState): AgentStepExecutionResult = Fail(state)
    }
}
