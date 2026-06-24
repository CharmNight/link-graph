package com.charmnight.linkgraph.llm.runtime

/**
 * 负责执行下一步 runtime 行为。
 *
 * 第一阶段 capability 可以直接生成一个"代理到旧 service"的 step executor，
 * 后续再演进为真实多步 tool loop（一个 step 可能是一次工具调用 + 一次模型推理）。
 *
 * 采用函数式接口让实现可以是闭包或类，便于在不同阶段替换策略。
 */
fun interface StepExecutor {
    /**
     * 在给定运行状态与上下文下推进一步。
     *
     * @param state 当前 Agent 运行状态（消息历史、产物引用等）
     * @param runtimeContext 本次 run 的运行上下文（工具集、配置等）
     * @return 本步骤执行的结果，可能携带新消息、产物或终止信号
     */
    fun executeNextStep(
        state: AgentRunState,
        runtimeContext: AgentRuntimeContext,
    ): AgentStepExecutionResult
}
