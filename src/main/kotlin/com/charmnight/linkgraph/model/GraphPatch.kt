package com.charmnight.linkgraph.model

data class GraphPatch(
    val addedNodeIds: List<String> = emptyList(),
    val removedNodeIds: List<String> = emptyList(),
    val addedEdgeIds: List<String> = emptyList(),
    val removedEdgeIds: List<String> = emptyList(),
)
