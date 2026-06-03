package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.GraphDocument

class InteractiveGraphProjector(
    maxVisibleNodes: Int = 26,
    maxVisibleEdges: Int = 40,
    upstreamDepth: Int = 2,
    downstreamDepth: Int = 2,
    maxNeighborsPerDirection: Int = 5,
) {
    private val kernel = GraphProjectionKernel(
        maxVisibleNodes = maxVisibleNodes,
        maxVisibleEdges = maxVisibleEdges,
        upstreamDepth = upstreamDepth,
        downstreamDepth = downstreamDepth,
        maxNeighborsPerDirection = maxNeighborsPerDirection,
    )

    fun project(
        graph: GraphDocument,
        anchorNodeId: String? = null,
    ): InteractiveGraphProjection =
        kernel.projectInteractive(graph = graph, anchorNodeId = anchorNodeId)
}
