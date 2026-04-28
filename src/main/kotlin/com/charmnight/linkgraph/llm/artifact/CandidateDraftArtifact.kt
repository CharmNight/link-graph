package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.workbench.CandidateDraftChange

/**
 * 保存待确认候选草稿。
 * 该产物只能表示候选层，不等于用户已经确认的正式意图。
 */
data class CandidateDraftArtifact(
    override val artifactId: String,
    /** 候选草稿变更。 */
    val candidate: CandidateDraftChange,
) : AgentArtifact {
    override val type: ArtifactType = ArtifactType.CANDIDATE_DRAFT

    override val summary: ArtifactSummary = ArtifactSummary(
        title = candidate.title.ifBlank { "候选草稿" },
        description = candidate.reason.takeIf { it.isNotBlank() },
    )
}
