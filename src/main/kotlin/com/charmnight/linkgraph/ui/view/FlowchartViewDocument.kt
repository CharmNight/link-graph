package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.model.GraphDocument

data class FlowchartSummary(
    val nodeCount: Int = 0,
    val branchCount: Int = 0,
    val exceptionPathCount: Int = 0,
)

data class FlowchartViewDocument(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val anchorNodeId: String? = null,
    val summary: FlowchartSummary = FlowchartSummary(),
)
