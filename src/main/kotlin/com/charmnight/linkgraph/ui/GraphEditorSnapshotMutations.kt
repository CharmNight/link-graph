package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import com.charmnight.linkgraph.workbench.QaRequestRecoveryState

internal fun GraphEditorStateSnapshot.withLoadedGraph(
    graph: GraphDocument,
    source: String,
): GraphEditorStateSnapshot {
    val nextLayoutState = extractLayoutState(graph)
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = graph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextViewDocuments = buildViewDocuments(
        visibleGraph = graph,
        factFullGraph = graph,
        selectedNodeId = nextSelectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    return copy(
        visibleGraph = graph,
        workingGraph = graph,
        referenceWorkingGraph = graph,
        referenceFactGraph = graph,
        designBaselineGraph = null,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(graph),
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        diff = null,
        diffMode = false,
        draftWorkbenchState = DraftWorkbenchState(),
        draftPatchPreview = null,
        draftPatchUndoState = null,
        lastDraftPatchApplyResult = null,
        auditResult = null,
        auditRequestState = AsyncRequestState(),
        qaRequestRecoveryState = QaRequestRecoveryState(),
        diffReviewResult = null,
        diffReviewRequestState = AsyncRequestState(),
        graphBeautificationResult = null,
        graphBeautificationRequestState = AsyncRequestState(),
        importedMermaid = null,
        exportedMermaid = null,
        mermaidIssues = emptyList(),
        syncPreviewItems = emptyList(),
        syncPreviewRequested = false,
        draftVersion = 0,
        generationPlan = null,
        generationPlanDraftVersion = null,
        generationPlanRequestState = AsyncRequestState(),
        draftValidationState = null,
        generationPlanDiscussionSession = null,
        generationPlanDiscussionRequestState = AsyncRequestState(),
        generatedCodeDrafts = emptyList(),
        generatedCodeDraftVersion = null,
        generatedCodeDraftWarnings = emptyList(),
        generatedCodeDraftSource = null,
        generatedCodeDraftPromptPreview = null,
        generatedCodeDraftWriteReport = null,
        codeDraftRequestState = AsyncRequestState(),
        codeEligibilityDecision = null,
        workingGraphDirty = false,
        analysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
        layoutState = nextLayoutState,
        semanticRevision = semanticRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedNodeId = nextSelectedNodeId,
        lastGraphSource = source,
        lastMessageType = "loadGraph",
    )
}

internal fun GraphEditorStateSnapshot.withLoadedGraphProjection(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
    source: String,
    selectedMethodSignatureOverride: String? = null,
): GraphEditorStateSnapshot {
    val effectiveSignature = selectedMethodSignatureOverride ?: selectedMethodSignature
    val nextLayoutState = extractLayoutState(visibleGraph)
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = visibleGraph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    val nextViewDocuments = buildViewDocuments(
        visibleGraph = visibleGraph,
        factFullGraph = fullGraph,
        selectedNodeId = nextSelectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    return copy(
        visibleGraph = visibleGraph,
        workingGraph = fullGraph,
        referenceWorkingGraph = fullGraph,
        referenceFactGraph = fullGraph,
        designBaselineGraph = null,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(fullGraph, visibleGraph),
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        diff = null,
        diffMode = false,
        draftWorkbenchState = DraftWorkbenchState(),
        draftPatchPreview = null,
        draftPatchUndoState = null,
        lastDraftPatchApplyResult = null,
        auditResult = null,
        auditRequestState = AsyncRequestState(),
        qaRequestRecoveryState = QaRequestRecoveryState(),
        diffReviewResult = null,
        diffReviewRequestState = AsyncRequestState(),
        graphBeautificationResult = null,
        graphBeautificationRequestState = AsyncRequestState(),
        importedMermaid = null,
        exportedMermaid = null,
        mermaidIssues = emptyList(),
        syncPreviewItems = emptyList(),
        syncPreviewRequested = false,
        draftVersion = 0,
        generationPlan = null,
        generationPlanDraftVersion = null,
        generationPlanRequestState = AsyncRequestState(),
        draftValidationState = null,
        generationPlanDiscussionSession = null,
        generationPlanDiscussionRequestState = AsyncRequestState(),
        generatedCodeDrafts = emptyList(),
        generatedCodeDraftVersion = null,
        generatedCodeDraftWarnings = emptyList(),
        generatedCodeDraftSource = null,
        generatedCodeDraftPromptPreview = null,
        generatedCodeDraftWriteReport = null,
        codeDraftRequestState = AsyncRequestState(),
        codeEligibilityDecision = null,
        workingGraphDirty = false,
        analysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
        layoutState = nextLayoutState,
        semanticRevision = semanticRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedMethodSignature = effectiveSignature,
        selectedNodeId = nextSelectedNodeId,
        lastGraphSource = source,
        lastMessageType = "loadGraph",
    )
}

internal fun GraphEditorStateSnapshot.withLoadedAnalysisOutcome(
    outcome: AnalysisOutcome,
    source: String,
    graphPatchApplyService: GraphPatchApplyService,
): GraphEditorStateSnapshot {
    val nextReferenceWorkingGraph = when (outcome.displayMode) {
        AnalysisDisplayMode.FACT_GRAPH -> outcome.factGraphView?.fullGraph ?: outcome.fullGraph
        AnalysisDisplayMode.FLOWCHART -> outcome.flowchartView?.fullGraph ?: outcome.fullGraph
        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> outcome.resourceRelationView?.fullGraph ?: outcome.fullGraph
    }
    val nextReferenceFactGraph = outcome.factGraphView?.fullGraph ?: outcome.fullGraph
    val preservedDraftState = preservedConfirmedDraftState(this, outcome.selectedMethodSignature)
    val hasPreservedDrafts = preservedDraftState.draftChanges.isNotEmpty()
    val nextWorkingGraph = if (hasPreservedDrafts) {
        reapplyConfirmedDraftGraph(nextReferenceWorkingGraph, preservedDraftState, graphPatchApplyService)
    } else {
        nextReferenceWorkingGraph
    }
    val nextViewDocuments = if (hasPreservedDrafts) {
        buildOutcomeViewDocuments(
            outcome = outcome,
            workingGraph = nextWorkingGraph,
            referenceFactGraph = nextReferenceFactGraph,
        )
    } else {
        GraphEditorViewDocuments(
            factGraphView = outcome.factGraphView ?: buildViewDocuments(
                visibleGraph = outcome.visibleGraph,
                factFullGraph = nextReferenceFactGraph,
                selectedNodeId = outcome.anchorNodeId,
                selectedMethodSignature = outcome.selectedMethodSignature,
            ).factGraphView,
            flowchartView = outcome.flowchartView ?: buildViewDocuments(
                visibleGraph = outcome.visibleGraph,
                factFullGraph = nextReferenceFactGraph,
                selectedNodeId = outcome.anchorNodeId,
                selectedMethodSignature = outcome.selectedMethodSignature,
            ).flowchartView,
            resourceRelationView = outcome.resourceRelationView ?: buildViewDocuments(
                visibleGraph = outcome.visibleGraph,
                factFullGraph = nextReferenceFactGraph,
                selectedNodeId = outcome.anchorNodeId,
                selectedMethodSignature = outcome.selectedMethodSignature,
            ).resourceRelationView,
        )
    }
    val nextVisibleGraph = if (hasPreservedDrafts) {
        when (outcome.displayMode) {
            AnalysisDisplayMode.FACT_GRAPH -> nextViewDocuments.factGraphView.visibleGraph
            AnalysisDisplayMode.FLOWCHART -> nextViewDocuments.flowchartView.visibleGraph
            AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> nextViewDocuments.resourceRelationView.visibleGraph
        }
    } else {
        when (outcome.displayMode) {
            AnalysisDisplayMode.FACT_GRAPH -> outcome.factGraphView?.visibleGraph ?: outcome.visibleGraph
            AnalysisDisplayMode.FLOWCHART -> outcome.flowchartView?.visibleGraph ?: outcome.visibleGraph
            AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> outcome.resourceRelationView?.visibleGraph ?: outcome.visibleGraph
        }
    }
    val nextLayoutState = extractLayoutState(nextVisibleGraph)
    return copy(
        visibleGraph = nextVisibleGraph,
        workingGraph = nextWorkingGraph,
        referenceWorkingGraph = nextReferenceWorkingGraph,
        referenceFactGraph = nextReferenceFactGraph,
        designBaselineGraph = null,
        trustedNavigationNodes = buildTrustedNavigationNodeIndex(
            nextReferenceWorkingGraph,
            nextReferenceFactGraph,
            outcome.flowchartView?.fullGraph,
            outcome.resourceRelationView?.fullGraph,
        ),
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        draftWorkbenchState = preservedDraftState,
        draftPatchPreview = null,
        draftPatchUndoState = null,
        lastDraftPatchApplyResult = null,
        auditResult = null,
        auditRequestState = AsyncRequestState(),
        qaRequestRecoveryState = QaRequestRecoveryState(),
        diffReviewResult = null,
        diffReviewRequestState = AsyncRequestState(),
        graphBeautificationResult = null,
        graphBeautificationRequestState = AsyncRequestState(),
        diff = null,
        diffMode = false,
        importedMermaid = null,
        exportedMermaid = null,
        mermaidIssues = emptyList(),
        syncPreviewItems = emptyList(),
        draftVersion = if (hasPreservedDrafts) draftVersion else 0,
        generationPlan = null,
        generationPlanDraftVersion = null,
        generationPlanRequestState = AsyncRequestState(),
        draftValidationState = null,
        generationPlanDiscussionSession = null,
        generationPlanDiscussionRequestState = AsyncRequestState(),
        generatedCodeDrafts = emptyList(),
        generatedCodeDraftVersion = null,
        generatedCodeDraftWarnings = emptyList(),
        generatedCodeDraftSource = null,
        generatedCodeDraftPromptPreview = null,
        generatedCodeDraftWriteReport = null,
        codeDraftRequestState = AsyncRequestState(),
        codeEligibilityDecision = null,
        sourceNavigationState = SourceNavigationState(),
        syncPreviewRequested = false,
        toolWindowOpenRequested = toolWindowOpenRequested,
        workingGraphDirty = hasPreservedDrafts,
        analysisDisplayMode = outcome.displayMode,
        layoutState = nextLayoutState,
        semanticRevision = semanticRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedMethodSignature = outcome.selectedMethodSignature,
        selectedNodeId = outcome.anchorNodeId ?: resolveSelectedNodeId(
            graph = nextVisibleGraph,
            selectedNodeId = null,
            selectedMethodSignature = outcome.selectedMethodSignature,
        ),
        lastGraphSource = source,
        operationFeedback = OperationFeedback(
            level = outcome.feedbackLevel,
            message = outcome.feedbackMessage,
        ),
        lastMessageType = "loadAnalysisOutcome",
    )
}

internal fun GraphEditorStateSnapshot.withSwitchedAnalysisDisplayMode(
    displayMode: AnalysisDisplayMode,
): GraphEditorStateSnapshot {
    val nextVisibleGraph = resolveVisibleGraphForDisplayMode(this, displayMode)
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = nextVisibleGraph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    return copy(
        analysisDisplayMode = displayMode,
        visibleGraph = nextVisibleGraph,
        workingGraph = workingGraph ?: nextVisibleGraph,
        referenceWorkingGraph = referenceWorkingGraph ?: workingGraph ?: nextVisibleGraph,
        layoutState = extractLayoutState(nextVisibleGraph),
        snapshotRevision = snapshotRevision + 1,
        selectedNodeId = nextSelectedNodeId,
        lastMessageType = "displayModeSwitch",
    )
}

internal fun GraphEditorStateSnapshot.withWorkingGraphChanged(
    graph: GraphDocument,
    selectedMethodSignatureOverride: String? = null,
    preserveDraftPatchUndo: Boolean = false,
    workingGraphDirtyOverride: Boolean = true,
): GraphEditorStateSnapshot {
    val effectiveSignature = selectedMethodSignatureOverride ?: selectedMethodSignature
    val nextViewDocuments = syncWorkingGraphViewDocuments(
        currentState = this,
        graph = graph,
        effectiveSignature = effectiveSignature,
    )
    val nextVisibleGraph = when (analysisDisplayMode) {
        AnalysisDisplayMode.FLOWCHART -> nextViewDocuments.flowchartView.visibleGraph
        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> nextViewDocuments.resourceRelationView.visibleGraph
        AnalysisDisplayMode.FACT_GRAPH -> nextViewDocuments.factGraphView.visibleGraph
    }
    val nextLayoutState = mergeLayoutState(
        graph = nextVisibleGraph,
        preferred = layoutState,
        fallback = extractLayoutState(nextVisibleGraph),
    )
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = nextVisibleGraph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    return copy(
        visibleGraph = nextVisibleGraph,
        workingGraph = graph,
        referenceWorkingGraph = referenceWorkingGraph ?: graph,
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        draftWorkbenchState = draftWorkbenchState,
        draftPatchPreview = null,
        draftPatchUndoState = if (preserveDraftPatchUndo) draftPatchUndoState else null,
        lastDraftPatchApplyResult = null,
        auditResult = null,
        auditRequestState = AsyncRequestState(),
        qaRequestRecoveryState = QaRequestRecoveryState(),
        diffReviewResult = null,
        diffReviewRequestState = AsyncRequestState(),
        syncPreviewItems = emptyList(),
        syncPreviewRequested = false,
        generationPlan = generationPlan,
        generationPlanDraftVersion = generationPlanDraftVersion,
        generationPlanRequestState = AsyncRequestState(),
        draftValidationState = draftValidationState,
        generationPlanDiscussionSession = generationPlanDiscussionSession,
        generationPlanDiscussionRequestState = generationPlanDiscussionRequestState,
        graphBeautificationResult = null,
        graphBeautificationRequestState = AsyncRequestState(),
        generatedCodeDrafts = generatedCodeDrafts,
        generatedCodeDraftVersion = generatedCodeDraftVersion,
        generatedCodeDraftWarnings = generatedCodeDraftWarnings,
        generatedCodeDraftSource = generatedCodeDraftSource,
        generatedCodeDraftPromptPreview = generatedCodeDraftPromptPreview,
        generatedCodeDraftWriteReport = generatedCodeDraftWriteReport,
        codeDraftRequestState = AsyncRequestState(),
        codeEligibilityDecision = null,
        workingGraphDirty = workingGraphDirtyOverride,
        layoutState = nextLayoutState,
        semanticRevision = semanticRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedMethodSignature = effectiveSignature,
        selectedNodeId = nextSelectedNodeId,
        lastMessageType = "graphChanged",
    )
}

internal fun GraphEditorStateSnapshot.withViewGraphChanged(
    graph: GraphDocument,
    displayMode: AnalysisDisplayMode,
    selectedMethodSignatureOverride: String? = null,
    preserveDraftPatchUndo: Boolean = false,
    workingGraphDirtyOverride: Boolean = true,
): GraphEditorStateSnapshot {
    val effectiveSignature = selectedMethodSignatureOverride ?: selectedMethodSignature
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = graph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    val nextViewDocuments = syncDisplayModeGraphViewDocuments(
        currentState = this,
        graph = graph,
        effectiveSignature = effectiveSignature,
        nextSelectedNodeId = nextSelectedNodeId,
        displayMode = displayMode,
    )
    val nextVisibleGraph = when (analysisDisplayMode) {
        AnalysisDisplayMode.FLOWCHART -> nextViewDocuments.flowchartView.visibleGraph
        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> nextViewDocuments.resourceRelationView.visibleGraph
        AnalysisDisplayMode.FACT_GRAPH -> nextViewDocuments.factGraphView.visibleGraph
    }
    val nextLayoutState = mergeLayoutState(
        graph = nextVisibleGraph,
        preferred = layoutState,
        fallback = extractLayoutState(nextVisibleGraph),
    )
    val nextVisibleSelectedNodeId = resolveSelectedNodeId(
        graph = nextVisibleGraph,
        selectedNodeId = nextSelectedNodeId,
        selectedMethodSignature = effectiveSignature,
    )
    return copy(
        visibleGraph = nextVisibleGraph,
        workingGraph = graph,
        referenceWorkingGraph = referenceWorkingGraph ?: graph,
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        draftWorkbenchState = draftWorkbenchState,
        draftPatchPreview = null,
        draftPatchUndoState = if (preserveDraftPatchUndo) draftPatchUndoState else null,
        lastDraftPatchApplyResult = null,
        auditResult = null,
        auditRequestState = AsyncRequestState(),
        qaRequestRecoveryState = QaRequestRecoveryState(),
        diffReviewResult = null,
        diffReviewRequestState = AsyncRequestState(),
        syncPreviewItems = emptyList(),
        syncPreviewRequested = false,
        generationPlan = null,
        generationPlanDraftVersion = null,
        generationPlanRequestState = AsyncRequestState(),
        draftValidationState = draftValidationState,
        generationPlanDiscussionSession = null,
        generationPlanDiscussionRequestState = AsyncRequestState(),
        graphBeautificationResult = null,
        graphBeautificationRequestState = AsyncRequestState(),
        generatedCodeDrafts = emptyList(),
        generatedCodeDraftWarnings = emptyList(),
        generatedCodeDraftSource = null,
        generatedCodeDraftPromptPreview = null,
        generatedCodeDraftWriteReport = null,
        codeDraftRequestState = AsyncRequestState(),
        codeEligibilityDecision = null,
        workingGraphDirty = workingGraphDirtyOverride,
        layoutState = nextLayoutState,
        semanticRevision = semanticRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedMethodSignature = effectiveSignature,
        selectedNodeId = nextVisibleSelectedNodeId,
        lastMessageType = "graphChanged",
    )
}
