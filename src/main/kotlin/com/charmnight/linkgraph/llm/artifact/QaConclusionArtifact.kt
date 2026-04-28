package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.llm.GraphPatchResult

/**
 * 保存问答链路的最终结构化结论。
 * 第一阶段先把旧 GraphPatchResult 沉淀为 Artifact，避免 runtime 只能靠临时变量传递结果。
 */
data class QaConclusionArtifact(
    override val artifactId: String,
    /** 问答最终结果。 */
    val result: GraphPatchResult,
) : AgentArtifact {
    override val type: ArtifactType = ArtifactType.QA_CONCLUSION

    override val summary: ArtifactSummary = ArtifactSummary(
        title = "问答结论",
        description = result.answer.take(120),
    )
}
