package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

data class GraphProjectionPolicy(
    val maxVisibleNodes: Int,
    val maxVisibleEdges: Int,
    val anchorNodeId: String? = null,
    val seedNodeTypes: Set<NodeType> = emptySet(),
    val directionPolicy: GraphProjectionDirectionPolicy = GraphProjectionDirectionPolicy.BIDIRECTIONAL,
    val roleMetadataKey: String? = null,
    val roleQuotas: List<GraphWindowRoleQuota> = emptyList(),
    val enableOverflowSummary: Boolean = false,
    val fillDisconnectedNodes: Boolean = true,
    val overflowOwnerContext: String = "graph-window",
    val overflowEdgeType: EdgeType = EdgeType.CALL,
    val nodePriority: (GraphNode) -> Int = { 0 },
    val edgePriority: (GraphEdge) -> Int = { 0 },
)

enum class GraphProjectionDirectionPolicy {
    BIDIRECTIONAL,
    UPSTREAM,
    DOWNSTREAM,
}
