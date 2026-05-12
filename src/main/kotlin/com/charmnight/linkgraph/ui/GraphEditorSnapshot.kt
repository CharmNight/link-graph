package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument

typealias GraphLayoutPosition = com.charmnight.linkgraph.application.model.GraphLayoutPosition

data class GraphLayoutState(
    val positions: Map<String, GraphLayoutPosition> = emptyMap(),
)

data class GraphEditorSnapshot(
    val visibleGraph: GraphDocument = GraphDocument(),
    val workspaceGraph: GraphDocument = GraphDocument(),
    val workspaceBaseGraph: GraphDocument = GraphDocument(),
    val semanticFactGraph: GraphDocument = GraphDocument(),
    val designBaselineGraph: GraphDocument? = null,
    val factGraphView: FactGraphViewDocument = FactGraphViewDocument(),
    val flowchartView: FlowchartViewDocument = FlowchartViewDocument(),
    val resourceRelationView: ResourceRelationViewDocument = ResourceRelationViewDocument(),
    val analysisDisplayMode: com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode =
        com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.FACT_GRAPH,
    val currentSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    val sceneStates: Map<GraphSceneId, GraphSceneState> = defaultGraphSceneStates(),
    val semanticRevision: Long = 0,
    val workspaceRevision: Long = 0,
    val snapshotRevision: Long = 0,
)

fun GraphEditorStateSnapshot.editorSnapshot(): GraphEditorSnapshot {
    return GraphEditorSnapshot(
        visibleGraph = currentVisibleGraph(this),
        workspaceGraph = currentWorkspaceGraph(this),
        workspaceBaseGraph = workspaceBaseGraph,
        semanticFactGraph = semanticFactGraph,
        designBaselineGraph = designBaselineGraph,
        factGraphView = factGraphView,
        flowchartView = flowchartView,
        resourceRelationView = resourceRelationView,
        analysisDisplayMode = analysisDisplayMode,
        currentSceneId = currentSceneId,
        sceneStates = sceneStates,
        semanticRevision = semanticRevision,
        workspaceRevision = workspaceRevision,
        snapshotRevision = snapshotRevision,
    )
}
