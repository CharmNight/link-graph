package com.charmnight.linkgraph.presentation

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode

class GraphHiddenBucketProjector {
    fun project(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        bucketForNode: (GraphNode) -> String,
        labelForBucket: (String) -> String,
    ): List<GraphHiddenBucket> {
        val visibleNodeIds = visibleGraph.nodes.mapTo(linkedSetOf(), GraphNode::id)
        val visibleEdgeIds = visibleGraph.edges.mapTo(linkedSetOf()) { edge -> edge.id }
        val hiddenEdges = fullGraph.edges.filter { edge -> edge.id !in visibleEdgeIds }
        return fullGraph.nodes
            .filter { node -> node.id !in visibleNodeIds }
            .groupBy(bucketForNode)
            .map { (bucketId, nodes) ->
                val nodeIds = nodes.map(GraphNode::id)
                val nodeIdSet = nodeIds.toSet()
                GraphHiddenBucket(
                    id = bucketId,
                    label = labelForBucket(bucketId),
                    count = nodes.size,
                    nodeIds = nodeIds,
                    edgeIds = hiddenEdges
                        .filter { edge -> edge.fromNodeId in nodeIdSet || edge.toNodeId in nodeIdSet }
                        .map { edge -> edge.id },
                )
            }
            .sortedWith(compareBy(GraphHiddenBucket::id))
    }
}
