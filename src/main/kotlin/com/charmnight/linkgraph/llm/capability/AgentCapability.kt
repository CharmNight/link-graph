package com.charmnight.linkgraph.llm.capability

import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.llm.runtime.StepExecutor
import com.charmnight.linkgraph.llm.runtime.StopPolicy

/**
 * 统一定义问答、讲解、计划、代码能力的 runtime 接入契约。
 * Workflow 只负责装配输入和接收结果，不持有 capability 内部执行细节。
 */
interface AgentCapability<I, O> {
    /** capability 标识。 */
    val capabilityId: String

    /** 基于当前输入构造初始 run state。 */
    fun buildInitialState(input: I, runtimeContext: AgentRuntimeContext): AgentRunState

    /** 当前 capability 允许使用的工具集合。 */
    fun allowedTools(input: I): Set<String>

    /** 当前输入对应的停止策略。 */
    fun stopPolicy(input: I): StopPolicy

    /** 把 run state 收敛为业务结果。 */
    fun finalize(runState: AgentRunState, runtimeContext: AgentRuntimeContext): O

    /**
     * 第一阶段 capability 直接提供一个 step executor。
     * 后续如果引入统一 planner/step loop，可以把这里逐步收敛回 runtime 内部。
     */
    fun createStepExecutor(input: I): StepExecutor {
        error("Capability[$capabilityId] 尚未提供 StepExecutor")
    }
}
