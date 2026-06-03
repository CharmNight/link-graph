package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.projection.GraphWindowPolicy
import com.charmnight.linkgraph.projection.GraphWindowProjector
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.graph.GraphAssembler
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy

class FlowchartProjector(
    private val graphAssembler: GraphAssembler = GraphAssembler(),
    private val windowProjector: GraphWindowProjector = GraphWindowProjector(),
) {
    fun project(
        analysisResult: SemanticAnalysisResult,
        projectionPolicy: ProjectionPolicy = ProjectionPolicy(),
    ): FlowchartViewDocument {
        val fullGraph = graphAssembler.assemble(analysisResult, AnalysisDisplayMode.FLOWCHART)
        val anchorNodeId = analysisResult.anchors.firstOrNull()?.targetUnitId
            ?: fullGraph.nodes.firstOrNull()?.id
        val readableView = projectReadableFlowchartView(
            graph = fullGraph,
            anchorNodeId = anchorNodeId,
        )
        val visibleGraph = projectGraph(
            graph = readableView.visibleGraph,
            anchorNodeId = readableView.anchorNodeId,
            projectionPolicy = projectionPolicy,
        )
        val resolvedAnchorNodeId = resolveProjectedFlowchartNodeId(visibleGraph, readableView.anchorNodeId)
            ?: visibleGraph.nodes.firstOrNull()?.id
        return FlowchartViewDocument(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = resolvedAnchorNodeId,
            summary = deriveFlowchartSummary(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
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
            overflowOwnerContext = "flowchart",
        ).graph
}
