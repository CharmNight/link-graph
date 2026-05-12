package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

enum class GraphSceneId {
    WORKSPACE_FACT,
    WORKSPACE_FLOWCHART,
    WORKSPACE_RESOURCE_RELATION,
    DIFF,
}

fun AnalysisDisplayMode.toWorkspaceSceneId(): GraphSceneId = when (this) {
    AnalysisDisplayMode.FACT_GRAPH -> GraphSceneId.WORKSPACE_FACT
    AnalysisDisplayMode.FLOWCHART -> GraphSceneId.WORKSPACE_FLOWCHART
    AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> GraphSceneId.WORKSPACE_RESOURCE_RELATION
}

fun GraphSceneId.toAnalysisDisplayMode(): AnalysisDisplayMode? = when (this) {
    GraphSceneId.WORKSPACE_FACT -> AnalysisDisplayMode.FACT_GRAPH
    GraphSceneId.WORKSPACE_FLOWCHART -> AnalysisDisplayMode.FLOWCHART
    GraphSceneId.WORKSPACE_RESOURCE_RELATION -> AnalysisDisplayMode.RESOURCE_RELATION_VIEW
    GraphSceneId.DIFF -> null
}

data class GraphLayoutPosition(
    val x: Double,
    val y: Double,
)

data class GraphEditScript(
    val sceneId: GraphSceneId,
    val baseWorkspaceRevision: Long,
    val operations: List<GraphEditOperation>,
)

sealed interface GraphEditOperation {
    data class UpsertNode(
        val node: GraphNode,
    ) : GraphEditOperation

    data class RemoveNode(
        val nodeId: String,
    ) : GraphEditOperation

    data class UpsertEdge(
        val edge: GraphEdge,
    ) : GraphEditOperation

    data class RemoveEdge(
        val edgeId: String,
    ) : GraphEditOperation
}
