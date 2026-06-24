package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.model.GraphDocument

/**
 * 保存图工具读取到的摘要结果。
 *
 * 当前先记录节点/边数量和选区，用于后续问答 step 决策与日志定位。
 * 把"工具看到了什么图"沉淀为 Artifact，让多个 step 可以共享同一份图快照，
 * 不必每次重新读图。
 */
data class GraphSummaryArtifact(
    override val artifactId: String,
    /** 当前图。 */
    val graph: GraphDocument,
    /** 当前选区。空列表表示无选中。 */
    val selectedNodeIds: List<String>,
    /** 图来源（导入、索引、设计等）；用于追溯。 */
    val graphSource: String,
) : AgentArtifact {
    /** 产物类型固定为图摘要。 */
    override val type: ArtifactType = ArtifactType.GRAPH_SUMMARY

    /** 摘要：来源、规模、选区一目了然。 */
    override val summary: ArtifactSummary = ArtifactSummary(
        title = "图摘要",
        description = "source=$graphSource, nodes=${graph.nodes.size}, edges=${graph.edges.size}, selected=${selectedNodeIds.size}",
    )
}
