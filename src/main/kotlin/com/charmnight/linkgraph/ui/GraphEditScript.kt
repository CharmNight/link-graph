package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode

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
