package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.GraphDocument

data class GraphProjectionResult(
    val visibleGraph: GraphDocument,
    val fullGraph: GraphDocument,
    val hiddenNodeCount: Int,
    val hiddenEdgeCount: Int,
    val hiddenLayerCounts: Map<String, Int> = emptyMap(),
    val collapsedLayerCounts: Map<String, Int> = emptyMap(),
    val truncated: Boolean = hiddenNodeCount > 0 || hiddenEdgeCount > 0,
    val projectedSourceEdgeIds: Map<String, List<String>> = emptyMap(),
)
