package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.llm.EvidenceTraceEntry

/**
 * 保存 QA runtime 本轮源码取证轨迹，包括成功读取和失败原因。
 *
 * 取证轨迹是 QA 可解释性的基础——把"为什么得出这个结论"用证据片段串起来。
 * 沉淀为 Artifact 后，结论产物（QaConclusionArtifact）只需引用本轨迹即可，
 * 不需要再保留独立的痕迹字段。
 */
data class QaEvidenceTraceArtifact(
    override val artifactId: String,
    /** 本轮源码取证轨迹。 */
    val traces: List<EvidenceTraceEntry>,
) : AgentArtifact {
    /** 产物类型固定为 QA 证据链。 */
    override val type: ArtifactType = ArtifactType.QA_EVIDENCE_TRACE

    /** 摘要：轨迹总数 + 进入 prompt 的轨迹数，便于判断本轮证据规模。 */
    override val summary: ArtifactSummary = ArtifactSummary(
        title = "取证轨迹",
        description = "trace=${traces.size}, prompt=${traces.count(EvidenceTraceEntry::includedInPrompt)}",
    )
}
