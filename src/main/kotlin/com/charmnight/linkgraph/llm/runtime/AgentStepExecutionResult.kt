package com.charmnight.linkgraph.llm.runtime

/**
 * 描述单个 step 执行后的控制流结果。
 *
 * 第一阶段先支持继续、完成、失败三种分支，足够承载"代理到旧 service"的 runtime 外壳。
 * runtime 主循环根据具体子类决定是否继续推进。
 */
sealed class AgentStepExecutionResult(
    /** step 执行后的最新 run 状态。 */
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
        /** 便捷工厂：构造继续结果。 */
        fun continueWith(state: AgentRunState): AgentStepExecutionResult = Continue(state)

        /** 便捷工厂：构造完成结果。 */
        fun complete(state: AgentRunState): AgentStepExecutionResult = Complete(state)

        /** 便捷工厂：构造失败结果。 */
        fun fail(state: AgentRunState): AgentStepExecutionResult = Fail(state)
    }
}
