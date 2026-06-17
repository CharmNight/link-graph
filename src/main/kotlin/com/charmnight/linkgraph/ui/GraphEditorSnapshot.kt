package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.review.ReviewGraphResult
import com.charmnight.linkgraph.semantic.outcome.FactGraphViewDocument
import com.charmnight.linkgraph.semantic.outcome.FlowchartViewDocument
import com.charmnight.linkgraph.semantic.outcome.ResourceRelationViewDocument

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
    val architectureGraphView: ArchitectureGraphResult = ArchitectureGraphResult(),
    val classDiagramView: ClassDiagramResult = ClassDiagramResult(),
    val reviewGraphView: ReviewGraphResult = ReviewGraphResult(),
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
        architectureGraphView = architectureGraphView,
        classDiagramView = classDiagramView,
        reviewGraphView = reviewGraphView,
        analysisDisplayMode = analysisDisplayMode,
        currentSceneId = currentSceneId,
        sceneStates = sceneStates,
        semanticRevision = semanticRevision,
        workspaceRevision = workspaceRevision,
        snapshotRevision = snapshotRevision,
    )
}
