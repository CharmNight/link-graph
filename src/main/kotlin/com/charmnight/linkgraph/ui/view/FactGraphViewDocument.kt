package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.model.GraphDocument

data class FactGraphSummary(
    val anchorTitle: String? = null,
    val visibleNodeCount: Int = 0,
    val fullNodeCount: Int = 0,
)

data class FactGraphViewDocument(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val anchorNodeId: String? = null,
    val summary: FactGraphSummary = FactGraphSummary(),
)
