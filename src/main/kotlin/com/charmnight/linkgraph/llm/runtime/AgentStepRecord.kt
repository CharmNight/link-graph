package com.charmnight.linkgraph.llm.runtime

/**
 * 记录一次 runtime step 的最小执行摘要。
 * 先保证 step 粒度的可观测性存在，后续再补工具名、耗时等更细字段。
 */
data class AgentStepRecord(
    /** 当前 step 序号。 */
    val stepIndex: Int,
    /** 该 step 结束后的阶段。 */
    val phase: AgentRunPhase,
    /** step 摘要。 */
    val summary: String,
    /** 涉及的工具名。 */
    val toolName: String? = null,
    /** 本 step 直接关联的 nodeId。 */
    val nodeId: String? = null,
)
