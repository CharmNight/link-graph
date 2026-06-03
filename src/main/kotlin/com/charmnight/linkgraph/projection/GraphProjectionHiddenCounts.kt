package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode

data class GraphProjectionHiddenCounts(
    val hiddenNodeCount: Int,
    val hiddenEdgeCount: Int,
) {
    val truncated: Boolean get() = hiddenNodeCount > 0 || hiddenEdgeCount > 0
}

fun graphProjectionHiddenCounts(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
): GraphProjectionHiddenCounts {
    val hiddenNodes = graphProjectionHiddenNodes(visibleGraph = visibleGraph, fullGraph = fullGraph)
    val hiddenEdges = graphProjectionHiddenEdges(visibleGraph = visibleGraph, fullGraph = fullGraph)
    return GraphProjectionHiddenCounts(
        hiddenNodeCount = hiddenNodes.size,
        hiddenEdgeCount = hiddenEdges.size,
    )
}

fun graphProjectionHiddenNodes(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
): List<GraphNode> {
    val visibleOriginalNodeIds = visibleGraph.nodes
        .asSequence()
        .map(GraphNode::id)
        .filter(fullGraph.nodes.mapTo(linkedSetOf(), GraphNode::id)::contains)
        .toSet()
    return fullGraph.nodes.filter { node -> node.id !in visibleOriginalNodeIds }
}

fun graphProjectionHiddenEdges(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
): List<GraphEdge> {
    val fullEdgeIds = fullGraph.edges.mapTo(linkedSetOf(), GraphEdge::id)
    val visibleOriginalEdgeIds = visibleGraph.edges
        .asSequence()
        .flatMap { edge -> edge.projectedSourceEdgeIds().asSequence() }
        .filter(fullEdgeIds::contains)
        .toSet()
    return fullGraph.edges.filter { edge -> edge.id !in visibleOriginalEdgeIds }
}

fun GraphEdge.projectedSourceEdgeIds(): List<String> =
    metadata[GraphProjectionMetadata.SourceEdges.UML_AGGREGATE_EDGE_IDS]
        ?.split(',')
        ?.mapNotNull { edgeId -> edgeId.trim().takeIf(String::isNotBlank) }
        ?.takeIf(List<String>::isNotEmpty)
        ?: listOf(id)
