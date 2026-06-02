package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.projection.graphProjectionHiddenCounts

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
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
)

private val decisionFlowScopeKinds = setOf("IF", "SWITCH", "FOREACH", "FOR", "WHILE", "DO_WHILE")

internal fun resolveFlowchartKind(node: GraphNode): String {
    val flowKind = node.metadata["flow.kind"]?.trim()?.uppercase()
    return when {
        node.type == NodeType.FLOW_SCOPE && flowKind in decisionFlowScopeKinds -> "DECISION"
        node.type == NodeType.MERGE -> "MERGE"
        node.type == NodeType.TERMINAL -> "TERMINAL"
        else -> node.metadata["flowchart.kind"] ?: "PROCESS"
    }
}

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
    val hiddenCounts = graphProjectionHiddenCounts(visibleGraph = visibleGraph, fullGraph = fullGraph)
    return FlowchartSummary(
        nodeCount = visibleGraph.nodes.size,
        branchCount = visibleGraph.nodes.count { resolveFlowchartKind(it) == "DECISION" },
        exceptionPathCount = visibleGraph.edges.count { it.label?.trim()?.uppercase() == "EXCEPTION" },
        fullNodeCount = fullGraph.nodes.size,
        fullEdgeCount = fullGraph.edges.size,
        hiddenNodeCount = hiddenCounts.hiddenNodeCount,
        hiddenEdgeCount = hiddenCounts.hiddenEdgeCount,
        truncated = hiddenCounts.truncated,
        incompleteNodeCount = incompleteNodeCount,
        incompleteEdgeCount = incompleteEdgeCount,
        semanticallyIncomplete = incompleteNodeCount > 0 || incompleteEdgeCount > 0,
        syntheticEdgeCount = syntheticEdgeCount,
        syntheticEntryEdgeCount = syntheticEntryEdgeCount,
    )
}
