package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.application.model.GraphEditRejected
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.architecture.view.ArchitectureGraphViewDocument
import com.charmnight.linkgraph.architecture.view.ClassDiagramViewDocument
import com.charmnight.linkgraph.review.ReviewGraphViewDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState

internal fun GraphEditorStateSnapshot.withLoadedGraph(
    graph: GraphDocument,
    source: String,
): GraphEditorStateSnapshot {
    val selectedNodeId = resolveSelectedNodeId(
        graph = graph,
        selectedNodeId = currentSceneState().selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextViewDocuments = buildViewDocuments(
        workspaceGraph = graph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextScene = AnalysisDisplayMode.FACT_GRAPH.toWorkspaceSceneId()
    return resetDerivedGraphState(
        preserveDrafts = false,
        preserveWorkbenchPlan = false,
    ).copy(
        semanticFactGraph = graph,
        workspaceBaseGraph = graph,
        workspaceGraph = graph,
        designBaselineGraph = null,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(graph),
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        analysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(
            nextScene,
            GraphSceneState(
                selectedNodeId = selectedNodeId,
                anchorNodeId = selectedNodeId ?: graph.nodes.firstOrNull()?.id,
                layoutState = extractLayoutState(graph),
            ),
        ),
        workingGraphDirty = false,
        semanticRevision = semanticRevision + 1,
        workspaceRevision = workspaceRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedMethodSignature = selectedMethodSignature,
        lastGraphSource = source,
        lastMessageType = "loadGraph",
    ).withAssistantContextFromCurrentState()
}

internal fun GraphEditorStateSnapshot.withLoadedGraphProjection(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    source: String,
    selectedMethodSignatureOverride: String? = null,
): GraphEditorStateSnapshot {
    val effectiveSignature = selectedMethodSignatureOverride ?: selectedMethodSignature
    val selectedNodeId = resolveSelectedNodeId(
        graph = visibleGraph,
        selectedNodeId = currentSceneState().selectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    val nextViewDocuments = buildViewDocuments(
        workspaceGraph = fullGraph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    val nextScene = AnalysisDisplayMode.FACT_GRAPH.toWorkspaceSceneId()
    return resetDerivedGraphState(
        preserveDrafts = false,
        preserveWorkbenchPlan = false,
    ).copy(
        semanticFactGraph = fullGraph,
        workspaceBaseGraph = fullGraph,
        workspaceGraph = fullGraph,
        designBaselineGraph = null,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(fullGraph, visibleGraph),
        factGraphView = nextViewDocuments.factGraphView.copy(
            visibleGraph = visibleGraph,
            anchorNodeId = selectedNodeId,
        ),
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        analysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(
            nextScene,
            GraphSceneState(
                selectedNodeId = selectedNodeId,
                anchorNodeId = selectedNodeId ?: visibleGraph.nodes.firstOrNull()?.id,
                layoutState = extractLayoutState(visibleGraph),
            ),
        ),
        workingGraphDirty = false,
        semanticRevision = semanticRevision + 1,
        workspaceRevision = workspaceRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedMethodSignature = effectiveSignature,
        lastGraphSource = source,
        lastMessageType = "loadGraph",
    ).withAssistantContextFromCurrentState()
}

internal fun GraphEditorStateSnapshot.withLoadedAnalysisOutcome(
    outcome: AnalysisOutcome,
    source: String,
    graphPatchApplyService: GraphPatchApplyService,
    runtimeTrace: ((() -> String) -> Unit)? = null,
): GraphEditorStateSnapshot {
    val nextSemanticFactGraph = outcome.factGraphView?.fullGraph ?: outcome.fullGraph
    val nextWorkspaceBaseGraph = when (outcome.displayMode) {
        AnalysisDisplayMode.FACT_GRAPH -> nextSemanticFactGraph
        AnalysisDisplayMode.FLOWCHART -> outcome.flowchartView?.fullGraph ?: outcome.fullGraph
        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> outcome.resourceRelationView?.fullGraph ?: outcome.fullGraph
        AnalysisDisplayMode.ARCHITECTURE_GRAPH,
        AnalysisDisplayMode.CLASS_DIAGRAM,
        AnalysisDisplayMode.REVIEW_GRAPH,
        -> nextSemanticFactGraph
    }
    val preservedDraftState = preservedConfirmedDraftState(this, outcome.selectedMethodSignature)
    val hasPreservedDrafts = preservedDraftState.draftChanges.isNotEmpty()
    val nextWorkspaceGraph = if (hasPreservedDrafts) {
        reapplyConfirmedDraftGraph(nextWorkspaceBaseGraph, preservedDraftState, graphPatchApplyService)
    } else {
        nextWorkspaceBaseGraph
    }
    val nextViewDocuments = buildViewDocuments(
        workspaceGraph = nextWorkspaceGraph,
        selectedNodeId = outcome.anchorNodeId,
        selectedMethodSignature = outcome.selectedMethodSignature,
        runtimeTrace = runtimeTrace,
    )
    val nextScene = outcome.displayMode.toWorkspaceSceneId()
    val nextVisibleGraph = when (outcome.displayMode) {
        AnalysisDisplayMode.FACT_GRAPH -> nextViewDocuments.factGraphView.visibleGraph
        AnalysisDisplayMode.FLOWCHART -> nextViewDocuments.flowchartView.visibleGraph
        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> nextViewDocuments.resourceRelationView.visibleGraph
        AnalysisDisplayMode.ARCHITECTURE_GRAPH -> architectureGraphView.visibleGraph
        AnalysisDisplayMode.CLASS_DIAGRAM -> classDiagramView.visibleGraph
        AnalysisDisplayMode.REVIEW_GRAPH -> reviewGraphView.visibleGraph
    }
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = nextVisibleGraph,
        selectedNodeId = outcome.anchorNodeId,
        selectedMethodSignature = outcome.selectedMethodSignature,
    )
    return resetDerivedGraphState(
        preserveDrafts = hasPreservedDrafts,
        preserveWorkbenchPlan = false,
    ).copy(
        semanticFactGraph = nextSemanticFactGraph,
        workspaceBaseGraph = nextWorkspaceBaseGraph,
        workspaceGraph = nextWorkspaceGraph,
        designBaselineGraph = null,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(
            nextWorkspaceGraph,
            nextSemanticFactGraph,
            outcome.flowchartView?.fullGraph,
            outcome.resourceRelationView?.fullGraph,
            architectureGraphView.visibleGraph,
            architectureGraphView.fullGraph,
            classDiagramView.visibleGraph,
            classDiagramView.fullGraph,
            reviewGraphView.visibleGraph,
            reviewGraphView.fullGraph,
        ),
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        draftWorkbenchState = preservedDraftState,
        draftVersion = if (hasPreservedDrafts) draftVersion else 0,
        workingGraphDirty = hasPreservedDrafts,
        analysisDisplayMode = outcome.displayMode,
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(
            nextScene,
            GraphSceneState(
                selectedNodeId = nextSelectedNodeId,
                anchorNodeId = nextSelectedNodeId ?: nextVisibleGraph.nodes.firstOrNull()?.id,
                layoutState = extractLayoutState(nextVisibleGraph),
            ),
        ),
        semanticRevision = semanticRevision + 1,
        workspaceRevision = workspaceRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedMethodSignature = outcome.selectedMethodSignature,
        lastGraphSource = source,
        operationFeedback = OperationFeedback(
            level = outcome.feedbackLevel,
            message = outcome.statusMessage,
        ),
        lastMessageType = "loadAnalysisOutcome",
    ).withAssistantContextFromCurrentState()
}

internal fun GraphEditorStateSnapshot.withSwitchedAnalysisDisplayMode(
    displayMode: AnalysisDisplayMode,
): GraphEditorStateSnapshot {
    val nextScene = displayMode.toWorkspaceSceneId()
    val nextVisibleGraph = resolveVisibleGraphForDisplayMode(this, displayMode)
    val currentSceneState = sceneState(nextScene)
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = nextVisibleGraph,
        selectedNodeId = currentSceneState.selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextSceneState = currentSceneState.copy(
        selectedNodeId = nextSelectedNodeId,
        anchorNodeId = currentSceneState.anchorNodeId
            ?.takeIf { anchorNodeId -> nextVisibleGraph.nodes.any { it.id == anchorNodeId } }
            ?: nextSelectedNodeId
            ?: nextVisibleGraph.nodes.firstOrNull()?.id,
    )
    return copy(
        analysisDisplayMode = displayMode,
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(nextScene, nextSceneState),
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "displayModeSwitch",
    ).withAssistantContextFromCurrentState()
}

internal fun GraphEditorStateSnapshot.withIndexedGraphRequestStarted(
    view: IndexedGraphView,
    requestState: AsyncRequestState,
    statusMessage: String,
): GraphEditorStateSnapshot {
    val displayMode = view.toAnalysisDisplayMode()
    val nextScene = displayMode.toWorkspaceSceneId()
    val clearedState = clearIndexedGraphView(view)
    val nextVisibleGraph = resolveVisibleGraphForDisplayMode(clearedState, displayMode)
    val currentSceneState = sceneState(nextScene)
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = nextVisibleGraph,
        selectedNodeId = currentSceneState.selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextSceneState = currentSceneState.copy(
        selectedNodeId = nextSelectedNodeId,
        anchorNodeId = currentSceneState.anchorNodeId
            ?.takeIf { anchorNodeId -> nextVisibleGraph.nodes.any { it.id == anchorNodeId } }
            ?: nextSelectedNodeId
            ?: nextVisibleGraph.nodes.firstOrNull()?.id,
    )
    return clearedState.copy(
        indexedGraphRequestStates = clearedState.indexedGraphRequestStates + (view to requestState),
        analysisDisplayMode = displayMode,
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = clearedState.sceneStates.withSceneState(nextScene, nextSceneState),
        operationFeedback = OperationFeedback(
            level = OperationFeedbackLevel.INFO,
            message = statusMessage,
        ),
        snapshotRevision = clearedState.snapshotRevision + 1,
        lastMessageType = "indexedGraphRequestStarted",
    )
}

internal fun GraphEditorStateSnapshot.withIndexedGraphRequestFailed(
    view: IndexedGraphView,
    requestState: AsyncRequestState,
    statusMessage: String,
): GraphEditorStateSnapshot {
    if (!shouldApplyIndexedGraphRequestState(view, requestState)) {
        return this
    }
    return copy(
        indexedGraphRequestStates = indexedGraphRequestStates + (view to requestState),
        operationFeedback = OperationFeedback(
            level = OperationFeedbackLevel.ERROR,
            message = statusMessage,
        ),
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "indexedGraphRequestFailed",
    )
}

internal fun GraphEditorStateSnapshot.withWorkspaceGraphChanged(
    graph: GraphDocument,
    selectedMethodSignatureOverride: String? = null,
    preserveDraftPatchUndo: Boolean = false,
    workingGraphDirtyOverride: Boolean = true,
    graphEditTransaction: GraphEditTransaction? = null,
): GraphEditorStateSnapshot {
    val effectiveSignature = selectedMethodSignatureOverride ?: selectedMethodSignature
    val nextViewDocuments = buildViewDocuments(
        workspaceGraph = graph,
        selectedNodeId = currentSceneState().selectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    val nextVisibleGraph = when (analysisDisplayMode) {
        AnalysisDisplayMode.FLOWCHART -> nextViewDocuments.flowchartView.visibleGraph
        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> nextViewDocuments.resourceRelationView.visibleGraph
        AnalysisDisplayMode.FACT_GRAPH -> nextViewDocuments.factGraphView.visibleGraph
        AnalysisDisplayMode.ARCHITECTURE_GRAPH -> architectureGraphView.visibleGraph
        AnalysisDisplayMode.CLASS_DIAGRAM -> classDiagramView.visibleGraph
        AnalysisDisplayMode.REVIEW_GRAPH -> reviewGraphView.visibleGraph
    }
    val sceneState = currentSceneState()
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = nextVisibleGraph,
        selectedNodeId = sceneState.selectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    val nextLayoutState = mergeLayoutState(
        graph = nextVisibleGraph,
        preferred = sceneState.layoutState,
        fallback = extractLayoutState(nextVisibleGraph),
    )
    val nextSceneState = sceneState.copy(
        selectedNodeId = nextSelectedNodeId,
        anchorNodeId = nextSelectedNodeId ?: nextVisibleGraph.nodes.firstOrNull()?.id,
        layoutState = nextLayoutState,
    )
    return resetDerivedGraphState(
        preserveDrafts = true,
        preserveWorkbenchPlan = true,
    ).copy(
        workspaceGraph = graph,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(
            graph,
            semanticFactGraph,
            architectureGraphView.visibleGraph,
            architectureGraphView.fullGraph,
            classDiagramView.visibleGraph,
            classDiagramView.fullGraph,
            reviewGraphView.visibleGraph,
            reviewGraphView.fullGraph,
        ),
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        draftPatchUndoState = if (preserveDraftPatchUndo) draftPatchUndoState else null,
        workingGraphDirty = workingGraphDirtyOverride,
        lastGraphEditTransaction = graphEditTransaction,
        lastGraphEditRejection = null,
        sceneStates = sceneStates.withSceneState(currentSceneId, nextSceneState),
        semanticRevision = semanticRevision + 1,
        workspaceRevision = workspaceRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedMethodSignature = effectiveSignature,
        lastMessageType = "workspaceGraphChanged",
    )
}

internal fun GraphEditorStateSnapshot.withGraphEditRejected(
    rejection: GraphEditRejected,
): GraphEditorStateSnapshot {
    val firstIssue = rejection.issues.firstOrNull()
    val message = firstIssue?.let { issue ->
        "图编辑失败：${issue.code.name} - ${issue.message}"
    } ?: "图编辑失败。"
    return copy(
        operationFeedback = OperationFeedback(
            level = OperationFeedbackLevel.ERROR,
            message = message,
        ),
        lastGraphEditRejection = rejection,
        lastGraphEditTransaction = null,
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "graphEditRejected",
    )
}

internal fun GraphEditorStateSnapshot.withLoadedArchitectureGraphView(
    view: ArchitectureGraphViewDocument,
    requestState: AsyncRequestState,
    statusMessage: String,
): GraphEditorStateSnapshot {
    if (!shouldApplyIndexedGraphRequestState(IndexedGraphView.ARCHITECTURE, requestState)) {
        return this
    }
    val nextScene = AnalysisDisplayMode.ARCHITECTURE_GRAPH.toWorkspaceSceneId()
    val currentSceneState = sceneState(nextScene)
    val authoritativeAnchorNodeId = view.anchorNodeId
        ?.takeIf { anchorNodeId -> view.visibleGraph.nodes.any { it.id == anchorNodeId } }
    val selectedNodeId = resolveSelectedNodeId(
        graph = view.visibleGraph,
        selectedNodeId = authoritativeAnchorNodeId ?: currentSceneState.selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextSceneState = currentSceneState.copy(
        selectedNodeId = selectedNodeId,
        anchorNodeId = authoritativeAnchorNodeId ?: selectedNodeId ?: view.visibleGraph.nodes.firstOrNull()?.id,
        layoutState = extractLayoutState(view.visibleGraph),
    )
    return copy(
        architectureGraphView = view,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(
            workspaceGraph,
            semanticFactGraph,
            view.visibleGraph,
            view.fullGraph,
            classDiagramView.visibleGraph,
            classDiagramView.fullGraph,
            reviewGraphView.visibleGraph,
            reviewGraphView.fullGraph,
        ),
        analysisDisplayMode = AnalysisDisplayMode.ARCHITECTURE_GRAPH,
        indexedGraphRequestStates = indexedGraphRequestStates + (IndexedGraphView.ARCHITECTURE to requestState),
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(nextScene, nextSceneState),
        operationFeedback = OperationFeedback(
            level = requestState.toOperationFeedbackLevel(),
            message = statusMessage,
        ),
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "loadArchitectureGraph",
    )
}

internal fun GraphEditorStateSnapshot.withLoadedClassDiagramView(
    view: ClassDiagramViewDocument,
    requestState: AsyncRequestState,
    statusMessage: String,
): GraphEditorStateSnapshot {
    if (!shouldApplyIndexedGraphRequestState(IndexedGraphView.CLASS_DIAGRAM, requestState)) {
        return this
    }
    val nextScene = AnalysisDisplayMode.CLASS_DIAGRAM.toWorkspaceSceneId()
    val currentSceneState = sceneState(nextScene)
    val authoritativeAnchorNodeId = view.anchorNodeId
        ?.takeIf { anchorNodeId -> view.visibleGraph.nodes.any { it.id == anchorNodeId } }
    val selectedNodeId = resolveSelectedNodeId(
        graph = view.visibleGraph,
        selectedNodeId = authoritativeAnchorNodeId ?: currentSceneState.selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextSceneState = currentSceneState.copy(
        selectedNodeId = selectedNodeId,
        anchorNodeId = authoritativeAnchorNodeId ?: selectedNodeId ?: view.visibleGraph.nodes.firstOrNull()?.id,
        layoutState = extractLayoutState(view.visibleGraph),
    )
    return copy(
        classDiagramView = view,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(
            workspaceGraph,
            semanticFactGraph,
            architectureGraphView.visibleGraph,
            architectureGraphView.fullGraph,
            view.visibleGraph,
            view.fullGraph,
            reviewGraphView.visibleGraph,
            reviewGraphView.fullGraph,
        ),
        analysisDisplayMode = AnalysisDisplayMode.CLASS_DIAGRAM,
        indexedGraphRequestStates = indexedGraphRequestStates + (IndexedGraphView.CLASS_DIAGRAM to requestState),
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(nextScene, nextSceneState),
        operationFeedback = OperationFeedback(
            level = requestState.toOperationFeedbackLevel(),
            message = statusMessage,
        ),
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "loadClassDiagram",
    )
}

internal fun GraphEditorStateSnapshot.withLoadedReviewGraphView(
    view: ReviewGraphViewDocument,
    requestState: AsyncRequestState,
    statusMessage: String,
): GraphEditorStateSnapshot {
    if (!shouldApplyIndexedGraphRequestState(IndexedGraphView.REVIEW, requestState)) {
        return this
    }
    val nextScene = AnalysisDisplayMode.REVIEW_GRAPH.toWorkspaceSceneId()
    val currentSceneState = sceneState(nextScene)
    val authoritativeAnchorNodeId = view.anchorNodeId
        ?.takeIf { anchorNodeId -> view.visibleGraph.nodes.any { it.id == anchorNodeId } }
    val selectedNodeId = resolveSelectedNodeId(
        graph = view.visibleGraph,
        selectedNodeId = authoritativeAnchorNodeId ?: currentSceneState.selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextSceneState = currentSceneState.copy(
        selectedNodeId = selectedNodeId,
        anchorNodeId = authoritativeAnchorNodeId ?: selectedNodeId ?: view.visibleGraph.nodes.firstOrNull()?.id,
        layoutState = extractLayoutState(view.visibleGraph),
    )
    return copy(
        reviewGraphView = view,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(
            workspaceGraph,
            semanticFactGraph,
            architectureGraphView.visibleGraph,
            architectureGraphView.fullGraph,
            classDiagramView.visibleGraph,
            classDiagramView.fullGraph,
            view.visibleGraph,
            view.fullGraph,
        ),
        analysisDisplayMode = AnalysisDisplayMode.REVIEW_GRAPH,
        indexedGraphRequestStates = indexedGraphRequestStates + (IndexedGraphView.REVIEW to requestState),
        currentSceneId = nextScene,
        previousWorkspaceSceneId = nextScene,
        sceneStates = sceneStates.withSceneState(nextScene, nextSceneState),
        operationFeedback = OperationFeedback(
            level = requestState.toOperationFeedbackLevel(),
            message = statusMessage,
        ),
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "loadReviewGraph",
    )
}

private fun GraphEditorStateSnapshot.shouldApplyIndexedGraphRequestState(
    view: IndexedGraphView,
    requestState: AsyncRequestState,
): Boolean {
    val incomingRequestId = requestState.requestId ?: return true
    val currentRequestId = indexedGraphRequestStates[view]?.requestId ?: return true
    return incomingRequestId == currentRequestId
}

private fun IndexedGraphView.toAnalysisDisplayMode(): AnalysisDisplayMode =
    when (this) {
        IndexedGraphView.ARCHITECTURE -> AnalysisDisplayMode.ARCHITECTURE_GRAPH
        IndexedGraphView.CLASS_DIAGRAM -> AnalysisDisplayMode.CLASS_DIAGRAM
        IndexedGraphView.REVIEW -> AnalysisDisplayMode.REVIEW_GRAPH
    }

private fun GraphEditorStateSnapshot.clearIndexedGraphView(view: IndexedGraphView): GraphEditorStateSnapshot =
    when (view) {
        IndexedGraphView.ARCHITECTURE -> copy(architectureGraphView = ArchitectureGraphViewDocument())
        IndexedGraphView.CLASS_DIAGRAM -> copy(classDiagramView = ClassDiagramViewDocument())
        IndexedGraphView.REVIEW -> copy(reviewGraphView = ReviewGraphViewDocument())
    }

private fun AsyncRequestState.toOperationFeedbackLevel(): OperationFeedbackLevel =
    when (phase) {
        com.charmnight.linkgraph.application.model.AsyncRequestPhase.SUCCEEDED -> OperationFeedbackLevel.SUCCESS
        com.charmnight.linkgraph.application.model.AsyncRequestPhase.FAILED,
        com.charmnight.linkgraph.application.model.AsyncRequestPhase.TIMED_OUT,
        -> OperationFeedbackLevel.ERROR
        else -> OperationFeedbackLevel.INFO
    }

private fun GraphEditorStateSnapshot.resetDerivedGraphState(
    preserveDrafts: Boolean,
    preserveWorkbenchPlan: Boolean,
): GraphEditorStateSnapshot {
    return copy(
        draftWorkbenchState = if (preserveDrafts) draftWorkbenchState else DraftWorkbenchState(),
        draftPatchPreview = null,
        draftPatchUndoState = null,
        lastDraftPatchApplyResult = null,
        qaResult = null,
        qaRequestState = AsyncRequestState(),
        qaRequestRecoveryState = QaRequestRecoveryState(),
        diffReviewResult = null,
        diffReviewRequestState = AsyncRequestState(),
        graphBeautificationResult = null,
        graphBeautificationRequestState = AsyncRequestState(),
        diff = null,
        diffGraph = null,
        importedMermaid = null,
        exportedMermaid = null,
        mermaidIssues = emptyList(),
        syncPreviewItems = emptyList(),
        syncPreviewRequested = false,
        lastGraphEditTransaction = null,
        lastGraphEditRejection = null,
        draftVersion = if (preserveDrafts) draftVersion else 0,
        generationPlan = if (preserveWorkbenchPlan) generationPlan else null,
        generationPlanDraftVersion = if (preserveWorkbenchPlan) generationPlanDraftVersion else null,
        generationPlanRequestState = AsyncRequestState(),
        draftValidationState = if (preserveWorkbenchPlan) draftValidationState else null,
        generationPlanDiscussionSession = if (preserveWorkbenchPlan) generationPlanDiscussionSession else null,
        generationPlanDiscussionRequestState = if (preserveWorkbenchPlan) generationPlanDiscussionRequestState else AsyncRequestState(),
        generatedCodeDrafts = if (preserveWorkbenchPlan) generatedCodeDrafts else emptyList(),
        generatedCodeDraftVersion = if (preserveWorkbenchPlan) generatedCodeDraftVersion else null,
        generatedCodeDraftWarnings = if (preserveWorkbenchPlan) generatedCodeDraftWarnings else emptyList(),
        generatedCodeDraftSource = if (preserveWorkbenchPlan) generatedCodeDraftSource else null,
        generatedCodeDraftPromptPreview = if (preserveWorkbenchPlan) generatedCodeDraftPromptPreview else null,
        generatedCodeDraftWriteReport = if (preserveWorkbenchPlan) generatedCodeDraftWriteReport else null,
        codeDraftRequestState = AsyncRequestState(),
        codeEligibilityDecision = null,
        sourceNavigationState = SourceNavigationState(),
    )
}

internal fun Map<GraphSceneId, GraphSceneState>.withSceneState(
    sceneId: GraphSceneId,
    state: GraphSceneState,
): Map<GraphSceneId, GraphSceneState> {
    return LinkedHashMap(this).apply {
        put(sceneId, state)
    }
}
