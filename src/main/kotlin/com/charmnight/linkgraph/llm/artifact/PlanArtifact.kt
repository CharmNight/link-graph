package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.llm.GenerationPlan

/**
 * 保存 runtime 生成出的实现计划。
 */
data class PlanArtifact(
    override val artifactId: String,
    /** 生成计划。 */
    val plan: GenerationPlan,
) : AgentArtifact {
    override val type: ArtifactType = ArtifactType.PLAN

    override val summary: ArtifactSummary = ArtifactSummary(
        title = "实现计划",
        description = plan.summary.take(120),
    )
}
