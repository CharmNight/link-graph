package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.workbench.CandidateDraftChange

/**
 * 保存待确认候选草稿。
 *
 * 候选草稿是 QA 等阶段产出的"建议改动"，需要用户确认后才会升级为正式意图。
 * 该产物只能表示候选层，不等于用户已经确认的正式意图；
 * 任何写盘、生成代码等动作都不应直接消费本产物，必须等待用户确认。
 */
data class CandidateDraftArtifact(
    override val artifactId: String,
    /** 候选草稿变更。 */
    val candidate: CandidateDraftChange,
) : AgentArtifact {
    /** 产物类型固定为候选草稿。 */
    override val type: ArtifactType = ArtifactType.CANDIDATE_DRAFT

    /** 摘要：候选标题为空时使用通用文案；描述取候选的 reason（若有）。 */
    override val summary: ArtifactSummary = ArtifactSummary(
        title = candidate.title.ifBlank { "候选草稿" },
        description = candidate.reason.takeIf { it.isNotBlank() },
    )
}
