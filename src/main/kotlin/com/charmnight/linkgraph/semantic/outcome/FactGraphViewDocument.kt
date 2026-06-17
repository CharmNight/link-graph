package com.charmnight.linkgraph.semantic.outcome

import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.presentation.GraphViewPresentation
import com.charmnight.linkgraph.model.GraphDocument

data class FactGraphSummary(
    val anchorTitle: String? = null,
    val visibleNodeCount: Int = 0,
    val fullNodeCount: Int = 0,
    val hiddenNodeCount: Int = 0,
    val hiddenEdgeCount: Int = 0,
    val truncated: Boolean = false,
)

data class FactGraphViewDocument(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val anchorNodeId: String? = null,
    val summary: FactGraphSummary = FactGraphSummary(),
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
    val presentation: GraphViewPresentation = GraphViewPresentation(),
)
