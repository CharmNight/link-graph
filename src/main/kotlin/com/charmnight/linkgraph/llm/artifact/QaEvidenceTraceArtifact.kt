package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.llm.EvidenceTraceEntry

/**
 * 保存 QA runtime 本轮源码取证轨迹，包括成功读取和失败原因。
 */
data class QaEvidenceTraceArtifact(
    override val artifactId: String,
    /** 本轮源码取证轨迹。 */
    val traces: List<EvidenceTraceEntry>,
) : AgentArtifact {
    override val type: ArtifactType = ArtifactType.QA_EVIDENCE_TRACE

    override val summary: ArtifactSummary = ArtifactSummary(
        title = "取证轨迹",
        description = "trace=${traces.size}, prompt=${traces.count(EvidenceTraceEntry::includedInPrompt)}",
    )
}
