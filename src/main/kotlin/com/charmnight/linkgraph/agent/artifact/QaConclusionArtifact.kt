package com.charmnight.linkgraph.agent.artifact

import com.charmnight.linkgraph.agent.model.GraphPatchResult

/**
 * 保存问答链路的最终结构化结论。
 *
 * 第一阶段先把旧 GraphPatchResult 沉淀为 Artifact，避免 runtime 只能靠临时变量传递结果。
 * 后续阶段（例如生成计划）需要消费问答结论时，直接通过 artifactId 取回，
 * 不再需要把整个会话历史搬过来。
 */
data class QaConclusionArtifact(
    override val artifactId: String,
    /** 问答最终结果。 */
    val result: GraphPatchResult,
) : AgentArtifact {
    /** 产物类型固定为 QA 结论。 */
    override val type: ArtifactType = ArtifactType.QA_CONCLUSION

    /** 摘要：截取回答前 120 字符作为描述，便于列表预览。 */
    override val summary: ArtifactSummary = ArtifactSummary(
        title = "问答结论",
        description = result.answer.take(120),
    )
}
