package com.charmnight.linkgraph.architecture.view

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import java.util.ArrayDeque

data class GraphViewportPolicy(
    val maxVisibleNodes: Int = 96,
    val maxVisibleEdges: Int = 144,
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
    if (nodes.isEmpty()) {
        return VisibleGraphWindow(
            graph = this,
            truncated = false,
            hiddenNodeCount = 0,
            hiddenEdgeCount = 0,
        )
    }

    val maxVisibleNodes = policy.maxVisibleNodes.coerceAtLeast(1)
    val maxVisibleEdges = policy.maxVisibleEdges.coerceAtLeast(0)
    if (nodes.size <= maxVisibleNodes && edges.size <= maxVisibleEdges) {
        return VisibleGraphWindow(
            graph = this,
            truncated = false,
            hiddenNodeCount = 0,
            hiddenEdgeCount = 0,
        )
    }

    val nodeById = nodes.associateBy(GraphNode::id)
    val incomingByTarget = edges.groupBy(GraphEdge::toNodeId)
    val outgoingBySource = edges.groupBy(GraphEdge::fromNodeId)
    val nodeComparator = compareBy<GraphNode>({ nodePriority(it) }, { it.title }, { it.id })
    val visibleNodeIds = linkedSetOf<String>()
    val queue = ArrayDeque<String>()

    fun addNode(nodeId: String) {
        if (visibleNodeIds.size >= maxVisibleNodes || nodeId in visibleNodeIds || nodeById[nodeId] == null) {
            return
        }
        visibleNodeIds += nodeId
        queue.addLast(nodeId)
    }

    anchorNodeId?.let(::addNode)
    nodes
        .filter { node -> node.type in seedNodeTypes }
        .sortedWith(nodeComparator)
        .forEach { node -> addNode(node.id) }
    if (visibleNodeIds.isEmpty()) {
        nodes.minWithOrNull(nodeComparator)?.let { node -> addNode(node.id) }
    }

    while (queue.isNotEmpty() && visibleNodeIds.size < maxVisibleNodes) {
        val currentNodeId = queue.removeFirst()
        val candidateEdges = (outgoingBySource[currentNodeId].orEmpty() + incomingByTarget[currentNodeId].orEmpty())
            .sortedWith(
                compareBy<GraphEdge>(
                    { edge -> edgePriority(edge) },
                    { edge -> nodePriority(nodeById[edge.neighborOf(currentNodeId)] ?: nodeById[currentNodeId]!!) },
                    GraphEdge::id,
                ),
            )
        for (edge in candidateEdges) {
            if (visibleNodeIds.size >= maxVisibleNodes) {
                break
            }
            addNode(edge.neighborOf(currentNodeId))
        }
    }

    if (visibleNodeIds.size < maxVisibleNodes) {
        nodes
            .sortedWith(nodeComparator)
            .forEach { node -> addNode(node.id) }
    }

    val visibleEdgeIds = edges
        .asSequence()
        .filter { edge -> edge.fromNodeId in visibleNodeIds && edge.toNodeId in visibleNodeIds }
        .sortedWith(compareBy<GraphEdge>({ edgePriority(it) }, GraphEdge::id))
        .take(maxVisibleEdges)
        .mapTo(linkedSetOf(), GraphEdge::id)
    val visibleGraph = GraphDocument(
        nodes = nodes.filter { node -> node.id in visibleNodeIds },
        edges = edges.filter { edge -> edge.id in visibleEdgeIds },
        patch = patch,
    )
    val hiddenNodeCount = nodes.size - visibleGraph.nodes.size
    val hiddenEdgeCount = edges.size - visibleGraph.edges.size
    return VisibleGraphWindow(
        graph = visibleGraph,
        truncated = hiddenNodeCount > 0 || hiddenEdgeCount > 0,
        hiddenNodeCount = hiddenNodeCount,
        hiddenEdgeCount = hiddenEdgeCount,
    )
}

private fun GraphEdge.neighborOf(nodeId: String): String =
    if (fromNodeId == nodeId) toNodeId else fromNodeId
