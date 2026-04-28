package com.charmnight.linkgraph.llm.artifact

import com.charmnight.linkgraph.model.GraphDiff

/**
 * 保存 runtime 读取到的图 diff。
 * 计划阶段应优先消费它，而不是继续依赖 workflow 入口处的旧 diff 输入。
 */
data class GraphDiffArtifact(
    override val artifactId: String,
    /** 当前图 diff。 */
    val diff: GraphDiff,
) : AgentArtifact {
    override val type: ArtifactType = ArtifactType.GRAPH_DIFF

    override val summary: ArtifactSummary = ArtifactSummary(
        title = "图差异",
        description = "entries=${diff.entries.size}, status=${diff.status}",
    )
}
