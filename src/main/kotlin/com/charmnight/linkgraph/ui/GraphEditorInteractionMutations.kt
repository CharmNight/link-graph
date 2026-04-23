package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

internal fun GraphEditorStateSnapshot.withImportedMermaid(
    mermaid: String,
    graph: GraphDocument?,
    mermaidIssues: List<MermaidIssue>,
): GraphEditorStateSnapshot {
    val hasWorkingGraph = workingGraph != null || visibleGraph != null
    val effectiveDraftGraph = if (hasWorkingGraph) {
        workingGraph ?: visibleGraph
    } else {
        graph
    }
    val effectiveVisibleGraph = if (hasWorkingGraph) {
        visibleGraph ?: workingGraph
    } else {
        graph
    }
    val nextLayoutState = extractLayoutState(effectiveVisibleGraph ?: effectiveDraftGraph)
    val nextViewDocuments = buildViewDocuments(
        visibleGraph = effectiveVisibleGraph ?: effectiveDraftGraph,
        factFullGraph = referenceFactGraph ?: effectiveVisibleGraph ?: effectiveDraftGraph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    return copy(
        visibleGraph = effectiveVisibleGraph,
        workingGraph = effectiveDraftGraph,
        referenceWorkingGraph = referenceWorkingGraph ?: effectiveDraftGraph,
        designBaselineGraph = graph ?: designBaselineGraph,
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        diff = null,
        diffMode = false,
        draftWorkbenchState = com.charmnight.linkgraph.workbench.DraftWorkbenchState(),
        draftPatchPreview = null,
        draftPatchUndoState = null,
        lastDraftPatchApplyResult = null,
        auditResult = null,
        auditRequestState = AsyncRequestState(),
        qaRequestRecoveryState = com.charmnight.linkgraph.workbench.QaRequestRecoveryState(),
        diffReviewResult = null,
        diffReviewRequestState = AsyncRequestState(),
        graphBeautificationResult = null,
        graphBeautificationRequestState = AsyncRequestState(),
        importedMermaid = mermaid,
        mermaidIssues = mermaidIssues,
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
        workingGraphDirty = workingGraphDirty,
        layoutState = nextLayoutState,
        semanticRevision = semanticRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "importMermaid",
    )
}

internal fun GraphEditorStateSnapshot.withShownDiffMode(
    graph: GraphDocument,
    diff: GraphDiff,
): GraphEditorStateSnapshot {
    val nextLayoutState = extractLayoutState(graph)
    val nextSelectedNodeId = resolveSelectedNodeId(
        graph = graph,
        selectedNodeId = selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    val nextViewDocuments = buildViewDocuments(
        visibleGraph = graph,
        factFullGraph = referenceFactGraph ?: graph,
        selectedNodeId = nextSelectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
    )
    return copy(
        visibleGraph = graph,
        referenceWorkingGraph = referenceWorkingGraph ?: workingGraph ?: graph,
        factGraphView = nextViewDocuments.factGraphView,
        flowchartView = nextViewDocuments.flowchartView,
        resourceRelationView = nextViewDocuments.resourceRelationView,
        diff = diff,
        diffMode = true,
        draftWorkbenchState = com.charmnight.linkgraph.workbench.DraftWorkbenchState(),
        draftPatchPreview = graph.patch,
        draftPatchUndoState = null,
        lastDraftPatchApplyResult = null,
        diffReviewResult = null,
        diffReviewRequestState = AsyncRequestState(),
        graphBeautificationResult = null,
        graphBeautificationRequestState = AsyncRequestState(),
        syncPreviewItems = emptyList(),
        syncPreviewRequested = false,
        generationPlan = generationPlan,
        generationPlanDraftVersion = generationPlanDraftVersion,
        generationPlanRequestState = AsyncRequestState(),
        generatedCodeDrafts = generatedCodeDrafts,
        generatedCodeDraftVersion = generatedCodeDraftVersion,
        generatedCodeDraftWarnings = generatedCodeDraftWarnings,
        generatedCodeDraftSource = generatedCodeDraftSource,
        generatedCodeDraftPromptPreview = generatedCodeDraftPromptPreview,
        generatedCodeDraftWriteReport = generatedCodeDraftWriteReport,
        codeDraftRequestState = AsyncRequestState(),
        workingGraphDirty = false,
        layoutState = nextLayoutState,
        semanticRevision = semanticRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        selectedNodeId = nextSelectedNodeId,
        lastMessageType = "showDiffMode",
    )
}

internal fun GraphEditorStateSnapshot.withSelectedMethod(
    signature: String,
): GraphEditorStateSnapshot {
    return copy(
        selectedMethodSignature = signature,
        selectedNodeId = findNodeIdBySignature(visibleGraph ?: workingGraph, signature) ?: selectedNodeId,
        lastMessageType = "selectedMethod",
    )
}

internal fun GraphEditorStateSnapshot.withSelectedNode(
    nodeId: String,
): GraphEditorStateSnapshot {
    return copy(
        selectedNodeId = nodeId,
        lastMessageType = "nodeSelected",
    )
}

internal fun GraphEditorStateSnapshot.withLayoutChanged(
    positions: Map<String, GraphLayoutPosition>,
): GraphEditorStateSnapshot {
    return copy(
        layoutState = layoutState.copy(
            positions = layoutState.positions + positions,
        ),
        layoutRevision = layoutRevision + 1,
        snapshotRevision = snapshotRevision + 1,
        lastMessageType = "layoutChanged",
    )
}

internal fun GraphEditorStateSnapshot.withRequestedSourceNavigation(
    nodeId: String,
): GraphEditorStateSnapshot {
    return copy(
        sourceNavigationState = SourceNavigationState(
            nodeId = nodeId,
            phase = SourceNavigationPhase.RUNNING,
        ),
        lastMessageType = "requestSourceNavigation",
    )
}

internal fun GraphEditorStateSnapshot.withOpenedSourceNavigation(
    nodeId: String,
    targetPath: String,
    line: Int?,
    column: Int?,
): GraphEditorStateSnapshot {
    return copy(
        sourceNavigationState = SourceNavigationState(
            nodeId = nodeId,
            phase = SourceNavigationPhase.SUCCEEDED,
            result = SourceNavigationResult.OPENED,
            targetPath = targetPath,
            line = line,
            column = column,
        ),
        lastMessageType = "sourceNavigationSucceeded",
    )
}

internal fun GraphEditorStateSnapshot.withMissingSourceNavigation(
    nodeId: String,
): GraphEditorStateSnapshot {
    return copy(
        sourceNavigationState = SourceNavigationState(
            nodeId = nodeId,
            phase = SourceNavigationPhase.NOT_FOUND,
        ),
        lastMessageType = "sourceNavigationNotFound",
    )
}

internal fun GraphEditorStateSnapshot.withFailedSourceNavigation(
    nodeId: String,
    errorMessage: String,
): GraphEditorStateSnapshot {
    return copy(
        sourceNavigationState = SourceNavigationState(
            nodeId = nodeId,
            phase = SourceNavigationPhase.FAILED,
            errorMessage = errorMessage,
        ),
        lastMessageType = "sourceNavigationFailed",
    )
}

internal fun GraphEditorStateSnapshot.withDraftPatchPreview(
    patch: GraphPatch,
): GraphEditorStateSnapshot {
    return copy(
        draftPatchPreview = patch,
        lastMessageType = "draftPatchPreview",
    )
}
