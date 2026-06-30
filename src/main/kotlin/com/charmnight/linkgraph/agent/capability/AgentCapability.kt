package com.charmnight.linkgraph.agent.capability

import com.charmnight.linkgraph.agent.runtime.AgentRunState
import com.charmnight.linkgraph.agent.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.agent.runtime.StepExecutor
import com.charmnight.linkgraph.agent.runtime.StopPolicy

/**
 * 统一定义问答、讲解、计划、代码能力的 runtime 接入契约。
 *
 * Workflow 只负责装配输入和接收结果，不持有 capability 内部执行细节。
 * 每种能力（QA / 计划 / 代码等）实现本接口，runtime 按统一流程调度。
 *
 * 类型参数：
 * @param I 输入类型
 * @param O 输出类型
 */
interface AgentCapability<I, O> {
    /** capability 标识。用于路由与日志。 */
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
     *
     * 后续如果引入统一 planner/step loop，可以把这里逐步收敛回 runtime 内部。
     * 默认实现抛出异常，强制子类显式提供。
     */
    fun createStepExecutor(input: I): StepExecutor {
        error("Capability[$capabilityId] 尚未提供 StepExecutor")
    }
}
