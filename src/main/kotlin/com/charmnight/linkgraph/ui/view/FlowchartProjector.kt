package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.graph.GraphAssembler
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy
import java.util.ArrayDeque

class FlowchartProjector(
    private val graphAssembler: GraphAssembler = GraphAssembler(),
) {
    fun project(
        analysisResult: SemanticAnalysisResult,
        projectionPolicy: ProjectionPolicy = ProjectionPolicy(),
    ): FlowchartViewDocument {
        val fullGraph = graphAssembler.assemble(analysisResult, AnalysisDisplayMode.FLOWCHART)
        val anchorNodeId = analysisResult.anchors.firstOrNull()?.targetUnitId
            ?: fullGraph.nodes.firstOrNull()?.id
        val visibleGraph = projectGraph(fullGraph, anchorNodeId, projectionPolicy)
        return FlowchartViewDocument(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = anchorNodeId,
            summary = deriveFlowchartSummary(visibleGraph = visibleGraph, fullGraph = fullGraph),
        )
    }

    private fun projectGraph(
        graph: GraphDocument,
        anchorNodeId: String?,
        projectionPolicy: ProjectionPolicy,
    ): GraphDocument {
        if (graph.nodes.size <= projectionPolicy.maxVisibleNodes && graph.edges.size <= projectionPolicy.maxVisibleEdges) {
            return graph
        }
        val nodeById = graph.nodes.associateBy { it.id }
        val outgoingBySource = graph.edges.groupBy { it.fromNodeId }
        val seed = anchorNodeId?.takeIf(nodeById::containsKey) ?: graph.nodes.firstOrNull()?.id ?: return graph
        val queue = ArrayDeque<String>()
        val visibleNodeIds = linkedSetOf<String>()
        val visibleEdgeIds = linkedSetOf<String>()
        queue.add(seed)
        visibleNodeIds += seed

        while (queue.isNotEmpty() && visibleNodeIds.size < projectionPolicy.maxVisibleNodes) {
            val current = queue.removeFirst()
            val candidateEdges = outgoingBySource[current]
                .orEmpty()
                .sortedBy { it.id }
            for (edge in candidateEdges) {
                if (visibleEdgeIds.size >= projectionPolicy.maxVisibleEdges) {
                    break
                }
                val neighborId = edge.toNodeId
                if (neighborId !in visibleNodeIds && visibleNodeIds.size >= projectionPolicy.maxVisibleNodes) {
                    continue
                }
                if (neighborId !in visibleNodeIds) {
                    visibleNodeIds += neighborId
                    queue.addLast(neighborId)
                }
                if (edge.fromNodeId in visibleNodeIds && edge.toNodeId in visibleNodeIds) {
                    visibleEdgeIds += edge.id
                }
            }
        }

        return GraphDocument(
            nodes = graph.nodes.filter { it.id in visibleNodeIds },
            edges = graph.edges.filter { it.id in visibleEdgeIds && it.fromNodeId in visibleNodeIds && it.toNodeId in visibleNodeIds },
        )
    }
}
