package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.semantic.outcome.ResourceRelationSummary
import com.charmnight.linkgraph.semantic.outcome.ResourceRelationViewDocument
import com.charmnight.linkgraph.semantic.outcome.graphProjectionIndexForVisibleGraph
import com.charmnight.linkgraph.semantic.outcome.graphProjectionIndexForVisibleGraph
import com.charmnight.linkgraph.projection.GraphWindowPolicy
import com.charmnight.linkgraph.projection.GraphWindowProjector
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.graph.GraphAssembler
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy

class ResourceRelationProjector(
    private val graphAssembler: GraphAssembler = GraphAssembler(),
    private val windowProjector: GraphWindowProjector = GraphWindowProjector(),
) : GraphProjector {
    fun project(
        analysisResult: SemanticAnalysisResult,
        projectionPolicy: ProjectionPolicy = ProjectionPolicy(),
    ): ResourceRelationViewDocument {
        val fullGraph = graphAssembler.assemble(analysisResult, AnalysisDisplayMode.RESOURCE_RELATION_VIEW)
        val anchorNodeId = analysisResult.anchors.firstOrNull()?.targetUnitId
            ?: fullGraph.nodes.firstOrNull()?.id
        val visibleGraph = projectGraph(fullGraph, anchorNodeId, projectionPolicy)
        return ResourceRelationViewDocument(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = anchorNodeId,
            summary = ResourceRelationSummary(
                visibleNodeCount = visibleGraph.nodes.size,
                relationCount = visibleGraph.edges.size,
                resourceCount = fullGraph.nodes.count { node ->
                    node.metadata["resource.lane"] != null || node.type.name.contains("RESOURCE") || node.type.name in setOf("SQL", "HTTP_ENDPOINT", "MQ_TOPIC", "CONFIG_ITEM")
                },
                fallbackReason = resourceFallbackReason(fullGraph),
                laneCounts = visibleGraph.nodes
                    .groupingBy { it.metadata?.get("resource.lane") ?: "CODE" }
                    .eachCount()
                    .toSortedMap(),
            ),
            projectionIndex = graphProjectionIndexForVisibleGraph(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
            ),
        )
    }

    private fun projectGraph(
        graph: GraphDocument,
        anchorNodeId: String?,
        projectionPolicy: ProjectionPolicy,
    ): GraphDocument =
        windowProjector.project(
            graph = graph,
            policy = GraphWindowPolicy(
                maxVisibleNodes = projectionPolicy.maxVisibleNodes,
                maxVisibleEdges = projectionPolicy.maxVisibleEdges,
                enableOverflowSummary = projectionPolicy.enableOverflowSummary,
                fillDisconnectedNodes = false,
            ),
            anchorNodeId = anchorNodeId,
            overflowOwnerContext = "resource-relation",
        ).graph

    private fun resourceFallbackReason(graph: GraphDocument): String {
        if (graph.edges.isNotEmpty()) {
            return "NONE"
        }
        val resourceCount = graph.nodes.count { node ->
            node.metadata["resource.lane"] != null || node.type.name.contains("RESOURCE") || node.type.name in setOf("SQL", "HTTP_ENDPOINT", "MQ_TOPIC", "CONFIG_ITEM")
        }
        return if (resourceCount == 0) "NO_RESOURCE_UNITS" else "NO_BINDING_RELATIONS"
    }
}
