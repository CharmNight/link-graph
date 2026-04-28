package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.model.GraphDocument

/**
 * 保存图工具读取到的摘要结果。
 * 当前先记录节点/边数量和选区，用于后续问答 step 决策与日志定位。
 */
data class GraphSummaryArtifact(
    override val artifactId: String,
    /** 当前图。 */
    val graph: GraphDocument,
    /** 当前选区。 */
    val selectedNodeIds: List<String>,
    /** 图来源。 */
    val graphSource: String,
) : AgentArtifact {
    override val type: ArtifactType = ArtifactType.GRAPH_SUMMARY

    override val summary: ArtifactSummary = ArtifactSummary(
        title = "图摘要",
        description = "source=$graphSource, nodes=${graph.nodes.size}, edges=${graph.edges.size}, selected=${selectedNodeIds.size}",
    )
}
