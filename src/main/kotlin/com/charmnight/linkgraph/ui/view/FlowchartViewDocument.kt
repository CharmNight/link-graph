package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.model.GraphDocument

data class FlowchartSummary(
    val nodeCount: Int = 0,
    val branchCount: Int = 0,
    val exceptionPathCount: Int = 0,
    val fullNodeCount: Int = 0,
    val fullEdgeCount: Int = 0,
    val hiddenNodeCount: Int = 0,
    val hiddenEdgeCount: Int = 0,
    val truncated: Boolean = false,
    val incompleteNodeCount: Int = 0,
    val incompleteEdgeCount: Int = 0,
    val semanticallyIncomplete: Boolean = false,
    val syntheticEdgeCount: Int = 0,
    val syntheticEntryEdgeCount: Int = 0,
)

data class FlowchartViewDocument(
    val visibleGraph: GraphDocument = GraphDocument(),
    val fullGraph: GraphDocument = GraphDocument(),
    val anchorNodeId: String? = null,
    val summary: FlowchartSummary = FlowchartSummary(),
)

internal fun deriveFlowchartSummary(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
): FlowchartSummary {
    val incompleteNodeCount = visibleGraph.nodes.count { node -> node.metadata["flow.incomplete"] == "true" }
    val incompleteEdgeCount = visibleGraph.edges.count { edge -> edge.metadata["flow.incomplete"] == "true" }
    val syntheticEdgeCount = visibleGraph.edges.count { edge -> edge.metadata["flow.synthetic"] == "true" }
    val syntheticEntryEdgeCount = visibleGraph.edges.count { edge ->
        edge.metadata["flow.synthetic"] == "true" && edge.metadata["flow.provenance"] == "SYNTHETIC_PROJECTION"
    }
    return FlowchartSummary(
        nodeCount = visibleGraph.nodes.size,
        branchCount = visibleGraph.nodes.count { it.metadata["flowchart.kind"] == "DECISION" },
        exceptionPathCount = visibleGraph.edges.count { it.label?.trim()?.uppercase() == "EXCEPTION" },
        fullNodeCount = fullGraph.nodes.size,
        fullEdgeCount = fullGraph.edges.size,
        hiddenNodeCount = 0,
        hiddenEdgeCount = 0,
        truncated = false,
        incompleteNodeCount = incompleteNodeCount,
        incompleteEdgeCount = incompleteEdgeCount,
        semanticallyIncomplete = incompleteNodeCount > 0 || incompleteEdgeCount > 0,
        syntheticEdgeCount = syntheticEdgeCount,
        syntheticEntryEdgeCount = syntheticEntryEdgeCount,
    )
}
