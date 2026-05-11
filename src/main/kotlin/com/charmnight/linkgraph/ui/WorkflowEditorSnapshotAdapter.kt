package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.model.ApplicationSnapshot
import com.charmnight.linkgraph.application.model.ApplicationGraphView
import com.charmnight.linkgraph.application.model.DraftPatchUndo
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot

internal fun GraphEditorStateSnapshot.toWorkflowEditorSnapshot(): WorkflowEditorSnapshot {
    return WorkflowEditorSnapshot(
        semanticFactGraph = semanticFactGraph,
        workspaceBaseGraph = workspaceBaseGraph,
        workspaceGraph = workspaceGraph,
        designBaselineGraph = designBaselineGraph,
        trustedNavigationNodes = trustedNavigationNodes,
        factGraphView = factGraphView.toApplicationGraphView(),
        flowchartView = flowchartView.toApplicationGraphView(),
        resourceRelationView = resourceRelationView.toApplicationGraphView(),
        analysisDisplayMode = analysisDisplayMode,
        currentSceneId = currentSceneId,
        selectedNodeId = currentSceneState().selectedNodeId,
        selectedMethodSignature = selectedMethodSignature,
        workingGraphDirty = workingGraphDirty,
        lastGraphSource = lastGraphSource,
        workspaceRevision = workspaceRevision,
        snapshotRevision = snapshotRevision,
        syncPreviewItems = syncPreviewItems,
        mermaidIssues = mermaidIssues,
        diff = diff,
        diffGraph = diffGraph,
        qaResult = qaResult,
        diffReviewResult = diffReviewResult,
        qaRequestState = qaRequestState,
        qaRequestRecoveryState = qaRequestRecoveryState,
        draftWorkbenchState = draftWorkbenchState,
        draftPatchPreview = draftPatchPreview,
        draftPatchUndo = draftPatchUndoState?.let { undoState ->
            DraftPatchUndo(
                graphBeforeApply = undoState.graphBeforeApply,
                patchPreview = undoState.patchPreview,
            )
        },
        generationPlan = generationPlan,
        generationPlanDiscussionSession = generationPlanDiscussionSession,
        generatedCodeDrafts = generatedCodeDrafts,
        generatedCodeDraftWriteReport = generatedCodeDraftWriteReport,
    )
}

private fun com.charmnight.linkgraph.ui.view.FactGraphViewDocument.toApplicationGraphView(): ApplicationGraphView =
    ApplicationGraphView(
        visibleGraph = visibleGraph,
        fullGraph = fullGraph,
        projectionIndex = projectionIndex,
    )

private fun com.charmnight.linkgraph.ui.view.FlowchartViewDocument.toApplicationGraphView(): ApplicationGraphView =
    ApplicationGraphView(
        visibleGraph = visibleGraph,
        fullGraph = fullGraph,
        projectionIndex = projectionIndex,
    )

private fun com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument.toApplicationGraphView(): ApplicationGraphView =
    ApplicationGraphView(
        visibleGraph = visibleGraph,
        fullGraph = fullGraph,
        projectionIndex = projectionIndex,
    )

internal fun GraphEditorStateSnapshot.toApplicationSnapshot(): ApplicationSnapshot {
    return ApplicationSnapshot(
        workspaceBaseGraph = workspaceBaseGraph,
        workspaceGraph = workspaceGraph,
        selectedMethodSignature = selectedMethodSignature,
        draftWorkbenchState = draftWorkbenchState,
        draftPatchPreview = draftPatchPreview,
        draftPatchUndo = draftPatchUndoState?.let { undoState ->
            DraftPatchUndo(
                graphBeforeApply = undoState.graphBeforeApply,
                patchPreview = undoState.patchPreview,
            )
        },
        qaResult = qaResult,
        diffReviewResult = diffReviewResult,
    )
}
