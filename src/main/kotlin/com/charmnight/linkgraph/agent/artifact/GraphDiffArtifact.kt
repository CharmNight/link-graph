package com.charmnight.linkgraph.agent.artifact

import com.charmnight.linkgraph.model.GraphDiff

/**
 * 保存 runtime 读取到的图 diff。
 *
 * 计划阶段应优先消费它，而不是继续依赖 workflow 入口处的旧 diff 输入。
 * 这样做保证 runtime 内部所有阶段看到的是同一份 diff，
 * 避免出现"workflow 输入的 diff"与"runtime 当前 diff"不一致的问题。
 */
data class GraphDiffArtifact(
    override val artifactId: String,
    /** 当前图 diff。 */
    val diff: GraphDiff,
) : AgentArtifact {
    /** 产物类型固定为图差异。 */
    override val type: ArtifactType = ArtifactType.GRAPH_DIFF

    /** 摘要：差异条目数与整体状态，便于一眼判断规模与新鲜度。 */
    override val summary: ArtifactSummary = ArtifactSummary(
        title = "图差异",
        description = "entries=${diff.entries.size}, status=${diff.status}",
    )
}
