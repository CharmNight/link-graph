package com.charmnight.linkgraph.llm.runtime

/**
 * 负责执行下一步 runtime 行为。
 * 第一阶段 capability 可以直接生成一个“代理到旧 service”的 step executor，后续再演进为真实多步 tool loop。
 */
fun interface StepExecutor {
    fun executeNextStep(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): AgentStepExecutionResult
}
