package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument

private fun GraphDocument.hasGraphContent(): Boolean = nodes.isNotEmpty() || edges.isNotEmpty() || patch != null

internal fun currentVisibleGraph(snapshot: GraphEditorStateSnapshot): GraphDocument {
    return if (snapshot.currentSceneId == GraphSceneId.DIFF) {
        snapshot.diffGraph ?: GraphDocument()
    } else {
        when (snapshot.currentSceneId.toAnalysisDisplayMode()) {
            com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.FACT_GRAPH -> snapshot.factGraphView.visibleGraph
            com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.FLOWCHART -> snapshot.flowchartView.visibleGraph
            com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> snapshot.resourceRelationView.visibleGraph
            com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.ARCHITECTURE_GRAPH -> snapshot.architectureGraphView.visibleGraph
            com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.CLASS_DIAGRAM -> snapshot.classDiagramView.visibleGraph
            com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.REVIEW_GRAPH -> snapshot.reviewGraphView.visibleGraph
            null -> GraphDocument()
        }
    }
}

internal fun currentWorkspaceGraph(snapshot: GraphEditorStateSnapshot): GraphDocument = snapshot.workspaceGraph

internal fun currentWorkingGraph(snapshot: GraphEditorStateSnapshot): GraphDocument = snapshot.workspaceGraph

internal fun currentWorkingGraphSource(snapshot: GraphEditorStateSnapshot): String {
    return if (snapshot.workspaceGraph.hasGraphContent()) "workspaceGraph" else "emptyGraph"
}
