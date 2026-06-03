package com.charmnight.linkgraph.architecture.view

import com.charmnight.linkgraph.projection.GraphWindowPolicy
import com.charmnight.linkgraph.projection.GraphWindowProjector
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

data class GraphViewportPolicy(
    val maxVisibleNodes: Int = 96,
    val maxVisibleEdges: Int = 144,
    val enableOverflowSummary: Boolean = false,
)

internal data class VisibleGraphWindow(
    val graph: GraphDocument,
    val truncated: Boolean,
    val hiddenNodeCount: Int,
    val hiddenEdgeCount: Int,
)

internal fun GraphDocument.visibleWindow(
    policy: GraphViewportPolicy,
    anchorNodeId: String? = null,
    seedNodeTypes: Set<NodeType> = emptySet(),
    nodePriority: (GraphNode) -> Int = { 0 },
    edgePriority: (GraphEdge) -> Int = { 0 },
): VisibleGraphWindow {
    val projection = GraphWindowProjector().project(
        graph = this,
        policy = GraphWindowPolicy(
            maxVisibleNodes = policy.maxVisibleNodes,
            maxVisibleEdges = policy.maxVisibleEdges,
            enableOverflowSummary = policy.enableOverflowSummary,
        ),
        anchorNodeId = anchorNodeId,
        seedNodeTypes = seedNodeTypes,
        nodePriority = nodePriority,
        edgePriority = edgePriority,
        overflowOwnerContext = "indexed-window",
    )
    return VisibleGraphWindow(
        graph = projection.graph,
        truncated = projection.truncated,
        hiddenNodeCount = projection.hiddenNodeCount,
        hiddenEdgeCount = projection.hiddenEdgeCount,
    )
}
