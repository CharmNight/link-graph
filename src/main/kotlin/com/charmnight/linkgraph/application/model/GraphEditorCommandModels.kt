package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

enum class GraphSceneId {
    WORKSPACE_FACT,
    WORKSPACE_FLOWCHART,
    WORKSPACE_RESOURCE_RELATION,
    WORKSPACE_ARCHITECTURE_GRAPH,
    WORKSPACE_CLASS_DIAGRAM,
    WORKSPACE_REVIEW_GRAPH,
    DIFF,
}

fun AnalysisDisplayMode.toWorkspaceSceneId(): GraphSceneId = when (this) {
    AnalysisDisplayMode.FACT_GRAPH -> GraphSceneId.WORKSPACE_FACT
    AnalysisDisplayMode.FLOWCHART -> GraphSceneId.WORKSPACE_FLOWCHART
    AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> GraphSceneId.WORKSPACE_RESOURCE_RELATION
    AnalysisDisplayMode.ARCHITECTURE_GRAPH -> GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH
    AnalysisDisplayMode.CLASS_DIAGRAM -> GraphSceneId.WORKSPACE_CLASS_DIAGRAM
    AnalysisDisplayMode.REVIEW_GRAPH -> GraphSceneId.WORKSPACE_REVIEW_GRAPH
}

fun GraphSceneId.toAnalysisDisplayMode(): AnalysisDisplayMode? = when (this) {
    GraphSceneId.WORKSPACE_FACT -> AnalysisDisplayMode.FACT_GRAPH
    GraphSceneId.WORKSPACE_FLOWCHART -> AnalysisDisplayMode.FLOWCHART
    GraphSceneId.WORKSPACE_RESOURCE_RELATION -> AnalysisDisplayMode.RESOURCE_RELATION_VIEW
    GraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH -> AnalysisDisplayMode.ARCHITECTURE_GRAPH
    GraphSceneId.WORKSPACE_CLASS_DIAGRAM -> AnalysisDisplayMode.CLASS_DIAGRAM
    GraphSceneId.WORKSPACE_REVIEW_GRAPH -> AnalysisDisplayMode.REVIEW_GRAPH
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
