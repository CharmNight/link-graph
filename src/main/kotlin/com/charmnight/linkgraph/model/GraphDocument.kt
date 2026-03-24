package com.charmnight.linkgraph.model

data class GraphDocument(
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
    val patch: GraphPatch? = null,
)
