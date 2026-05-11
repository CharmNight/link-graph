package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.model.GraphDocument

enum class GraphProjectionMappingKind {
    EXACT,
    MERGED_ALIAS,
    PATH_ALIAS,
    SYNTHETIC_READONLY,
    OVERFLOW_READONLY,
}

enum class GraphEditCommandKind {
    ADD_NODE,
    UPDATE_NODE,
    DELETE_NODE,
    DELETE_NODE_SUBTREE,
    CONNECT_NODES,
    DELETE_EDGE,
    INSERT_NODE_INTO_EDGE,
}

data class GraphProjectionNodeMapping(
    val projectedNodeId: String,
    val mappingKind: GraphProjectionMappingKind,
    val canonicalNodeIds: List<String> = emptyList(),
    val editableCommandKinds: Set<GraphEditCommandKind> = emptySet(),
)

data class GraphProjectionEdgeMapping(
    val projectedEdgeId: String,
    val mappingKind: GraphProjectionMappingKind,
    val canonicalEdgeIds: List<String> = emptyList(),
    val canonicalPathNodeIds: List<String> = emptyList(),
    val editableCommandKinds: Set<GraphEditCommandKind> = emptySet(),
)

data class GraphProjectionIndex(
    val nodeMappings: Map<String, GraphProjectionNodeMapping> = emptyMap(),
    val edgeMappings: Map<String, GraphProjectionEdgeMapping> = emptyMap(),
) {
    fun nodeMapping(nodeId: String): GraphProjectionNodeMapping? = nodeMappings[nodeId]

    fun edgeMapping(edgeId: String): GraphProjectionEdgeMapping? = edgeMappings[edgeId]

    companion object {
        val EMPTY = GraphProjectionIndex()
    }
}

data class ApplicationGraphView(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
)
