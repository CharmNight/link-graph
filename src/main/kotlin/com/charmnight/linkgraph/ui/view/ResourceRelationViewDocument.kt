package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.model.GraphDocument

data class ResourceRelationSummary(
    val visibleNodeCount: Int = 0,
    val laneCounts: Map<String, Int> = emptyMap(),
)

data class ResourceRelationViewDocument(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val anchorNodeId: String? = null,
    val summary: ResourceRelationSummary = ResourceRelationSummary(),
)
