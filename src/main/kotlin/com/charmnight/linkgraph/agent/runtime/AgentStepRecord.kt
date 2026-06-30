package com.charmnight.linkgraph.agent.runtime

/**
 * 记录一次 runtime step 的最小执行摘要。
 *
 * 先保证 step 粒度的可观测性存在，后续再补工具名、耗时等更细字段。
 * 该结构主要供日志、UI 时间线、回放使用，不参与 step 之间的状态传递。
 */
data class AgentStepRecord(
    /** 当前 step 序号。0-based，与运行轨迹中的位置对应。 */
    val stepIndex: Int,
    /** 该 step 结束后的阶段。 */
    val phase: AgentRunPhase,
    /** step 摘要。人类可读的简短描述，例如"读取 Foo.java 提取方法签名"。 */
    val summary: String,
    /** 涉及的工具名；若 step 不是工具调用则为 null。 */
    val toolName: String? = null,
    /** 本 step 直接关联的 nodeId；若不针对具体节点则为 null。 */
    val nodeId: String? = null,
)
