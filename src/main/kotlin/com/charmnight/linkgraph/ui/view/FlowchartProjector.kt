package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.graph.GraphAssembler
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy

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
        val visibleGraph = fullGraph
        return FlowchartViewDocument(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            anchorNodeId = anchorNodeId,
            summary = deriveFlowchartSummary(visibleGraph = visibleGraph, fullGraph = fullGraph),
        )
    }
}
