package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.llm.GenerationPlan

/**
 * 保存 runtime 生成出的实现计划。
 *
 * 计划阶段产出该产物后，代码生成阶段通过引用本产物拿到结构化计划，
 * 而不是从消息历史里反解。同时摘要里截取了计划的前 120 个字符，便于在列表里预览。
 */
data class PlanArtifact(
    override val artifactId: String,
    /** 生成计划。 */
    val plan: GenerationPlan,
) : AgentArtifact {
    /** 产物类型固定为实现计划。 */
    override val type: ArtifactType = ArtifactType.PLAN

    /** 摘要：截取计划前 120 字符作为描述，避免摘要过长。 */
    override val summary: ArtifactSummary = ArtifactSummary(
        title = "实现计划",
        description = plan.summary.take(120),
    )
}
