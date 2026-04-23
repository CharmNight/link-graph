package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.ui.view.ResourceRelationSummary
import com.charmnight.linkgraph.ui.view.projectReadableFlowchartView
import com.charmnight.linkgraph.workbench.DraftWorkbenchState

internal fun preservedConfirmedDraftState(
    currentState: GraphEditorStateSnapshot,
    nextSelectedMethodSignature: String?,
): DraftWorkbenchState {
    if (currentState.draftWorkbenchState.draftChanges.isEmpty()) {
        return DraftWorkbenchState()
    }
    val currentSignature = currentState.selectedMethodSignature
    if (currentSignature.isNullOrBlank() || nextSelectedMethodSignature.isNullOrBlank()) {
        return DraftWorkbenchState()
    }
    return if (currentSignature == nextSelectedMethodSignature) currentState.draftWorkbenchState else DraftWorkbenchState()
}

internal fun reapplyConfirmedDraftGraph(
    baseGraph: GraphDocument,
    draftState: DraftWorkbenchState,
    graphPatchApplyService: GraphPatchApplyService,
): GraphDocument {
    return draftState.draftChanges.fold(baseGraph) { currentGraph, entry ->
        val patch = entry.graphPatch ?: return@fold currentGraph
        graphPatchApplyService.apply(currentGraph, patch)
    }
}

internal fun buildOutcomeViewDocuments(
    outcome: AnalysisOutcome,
    workingGraph: GraphDocument,
    referenceFactGraph: GraphDocument,
): GraphEditorViewDocuments {
    val fallback = buildViewDocuments(
        visibleGraph = workingGraph,
        factFullGraph = referenceFactGraph,
        selectedNodeId = outcome.anchorNodeId,
        selectedMethodSignature = outcome.selectedMethodSignature,
    )
    val anchorNodeId = resolveSelectedNodeId(
        graph = workingGraph,
        selectedNodeId = outcome.anchorNodeId,
        selectedMethodSignature = outcome.selectedMethodSignature,
    ) ?: workingGraph.nodes.firstOrNull()?.id
    return when (outcome.displayMode) {
        AnalysisDisplayMode.FACT_GRAPH -> fallback
        AnalysisDisplayMode.FLOWCHART -> GraphEditorViewDocuments(
            factGraphView = outcome.factGraphView ?: fallback.factGraphView,
            flowchartView = projectReadableFlowchartView(
                graph = workingGraph,
                anchorNodeId = anchorNodeId,
            ),
            resourceRelationView = outcome.resourceRelationView ?: fallback.resourceRelationView,
        )

        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> GraphEditorViewDocuments(
            factGraphView = outcome.factGraphView ?: fallback.factGraphView,
            flowchartView = outcome.flowchartView ?: fallback.flowchartView,
            resourceRelationView = (outcome.resourceRelationView ?: fallback.resourceRelationView).copy(
                visibleGraph = workingGraph,
                fullGraph = workingGraph,
                anchorNodeId = anchorNodeId,
                summary = ResourceRelationSummary(
                    visibleNodeCount = workingGraph.nodes.size,
                    laneCounts = workingGraph.nodes
                        .groupingBy { it.metadata["resource.lane"] ?: "CODE" }
                        .eachCount()
                        .toSortedMap(),
                ),
            ),
        )
    }
}
