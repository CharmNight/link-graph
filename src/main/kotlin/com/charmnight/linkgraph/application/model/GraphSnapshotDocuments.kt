package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.application.model.toAnalysisDisplayMode
import com.charmnight.linkgraph.model.GraphDocument

private fun GraphDocument.hasGraphContent(): Boolean = nodes.isNotEmpty() || edges.isNotEmpty() || patch != null

internal fun currentVisibleGraph(snapshot: WorkflowEditorSnapshot): GraphDocument {
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

internal fun currentWorkspaceGraph(snapshot: WorkflowEditorSnapshot): GraphDocument = snapshot.workspaceGraph

internal fun currentWorkingGraph(snapshot: WorkflowEditorSnapshot): GraphDocument = snapshot.workspaceGraph

internal fun currentWorkingGraphSource(snapshot: WorkflowEditorSnapshot): String {
    return if (snapshot.workspaceGraph.hasGraphContent()) "workspaceGraph" else "emptyGraph"
}
