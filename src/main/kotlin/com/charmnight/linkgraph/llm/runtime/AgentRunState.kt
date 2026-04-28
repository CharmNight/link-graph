package com.charmnight.linkgraph.llm.runtime

import com.charmnight.linkgraph.llm.artifact.ArtifactRef

/**
 * 保存一次 Agent Run 的最小真实状态。
 * Workflow 只允许读取结果和摘要，不能把 runtime 内部执行逻辑重新吸回去。
 */
data class AgentRunState(
    /** 当前 run 的稳定标识。 */
    val runId: String,
    /** capability 标识。 */
    val capabilityId: String,
    /** 当前阶段。 */
    val phase: AgentRunPhase,
    /** 用户目标。 */
    val userGoal: String,
    /** 预算与实时消耗。 */
    val budget: RunBudget,
    /** 当前 step 序号。 */
    val stepIndex: Int,
    /** 已沉淀的产物引用。 */
    val artifactRefs: List<ArtifactRef>,
    /** step 执行记录。 */
    val stepRecords: List<AgentStepRecord> = emptyList(),
    /** 最近一次模型或工具的输出摘要。 */
    val lastModelOutput: String? = null,
    /** 停止或失败原因。 */
    val failureReason: AgentRunFailureReason? = null,
)
