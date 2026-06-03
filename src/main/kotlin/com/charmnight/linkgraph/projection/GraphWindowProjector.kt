package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

data class GraphWindowPolicy(
    val maxVisibleNodes: Int,
    val maxVisibleEdges: Int,
    val enableOverflowSummary: Boolean = false,
    val fillDisconnectedNodes: Boolean = true,
)

data class GraphWindowRoleQuota(
    val role: String,
    val maxNodes: Int,
)

data class GraphWindowProjection(
    val graph: GraphDocument,
    val truncated: Boolean,
    val hiddenNodeCount: Int,
    val hiddenEdgeCount: Int,
)

class GraphWindowProjector(
    private val kernel: GraphProjectionKernel = GraphProjectionKernel(),
) {
    fun project(
        graph: GraphDocument,
        policy: GraphWindowPolicy,
        anchorNodeId: String? = null,
        seedNodeTypes: Set<NodeType> = emptySet(),
        roleMetadataKey: String? = null,
        roleQuotas: List<GraphWindowRoleQuota> = emptyList(),
        nodePriority: (GraphNode) -> Int = { 0 },
        edgePriority: (GraphEdge) -> Int = { 0 },
        overflowOwnerContext: String = "graph-window",
        overflowEdgeType: EdgeType = EdgeType.CALL,
    ): GraphWindowProjection {
        val result = kernel.projectWindow(
            graph = graph,
            policy = GraphProjectionPolicy(
                maxVisibleNodes = policy.maxVisibleNodes,
                maxVisibleEdges = policy.maxVisibleEdges,
                anchorNodeId = anchorNodeId,
                seedNodeTypes = seedNodeTypes,
                roleMetadataKey = roleMetadataKey,
                roleQuotas = roleQuotas,
                enableOverflowSummary = policy.enableOverflowSummary,
                fillDisconnectedNodes = policy.fillDisconnectedNodes,
                overflowOwnerContext = overflowOwnerContext,
                overflowEdgeType = overflowEdgeType,
                nodePriority = nodePriority,
                edgePriority = edgePriority,
            ),
        )
        return GraphWindowProjection(
            graph = result.visibleGraph,
            truncated = result.truncated,
            hiddenNodeCount = result.hiddenNodeCount,
            hiddenEdgeCount = result.hiddenEdgeCount,
        )
    }
}
