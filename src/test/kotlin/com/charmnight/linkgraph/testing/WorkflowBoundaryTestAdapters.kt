package com.charmnight.linkgraph.testing

import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.ToolGraphSnapshotProvider
import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.ui.ConfirmedDraftStatePresenter
import com.charmnight.linkgraph.ui.DraftPatchStatePresenter
import com.charmnight.linkgraph.ui.GenerationStatePresenter
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.ReviewStatePresenter
import com.charmnight.linkgraph.ui.SourceNavigationStatePresenter
import com.charmnight.linkgraph.ui.SubjectGraphStatePresenter
import com.charmnight.linkgraph.ui.WorkspaceStatePresenter
import com.charmnight.linkgraph.ui.toWorkflowEditorSnapshot
import com.charmnight.linkgraph.ui.withWorkspaceGraphChanged
import com.charmnight.linkgraph.workbench.RiskResolutionService

internal fun GraphEditorStateService.editorSnapshotProvider(): EditorSnapshotProvider =
    EditorSnapshotProvider { snapshot().toWorkflowEditorSnapshot() }

internal fun GraphEditorStateService.toolGraphSnapshotProvider(): ToolGraphSnapshotProvider =
    ToolGraphSnapshotProvider { snapshot().toToolGraphSnapshot() }

internal fun GraphEditorStateService.workspaceGraphCommitter(
    requestBrowserSync: () -> Unit = {},
): WorkspaceGraphCommitter {
    return object : WorkspaceGraphCommitter {
        override fun commitWorkspaceGraph(
            expectedSnapshotRevision: Long?,
            graph: GraphDocument,
            selectedMethodSignature: String?,
            preserveDraftPatchUndo: Boolean,
            workingGraphDirty: Boolean,
            syncBrowser: Boolean,
        ): Boolean {
            val revision = expectedSnapshotRevision ?: snapshot().snapshotRevision
            val commitResult = tryCommit(revision) { current ->
                current.withWorkspaceGraphChanged(
                    graph = graph,
                    selectedMethodSignatureOverride = selectedMethodSignature,
                    preserveDraftPatchUndo = preserveDraftPatchUndo,
                    workingGraphDirtyOverride = workingGraphDirty,
                )
            }
            if (syncBrowser) {
                requestBrowserSync()
            }
            return commitResult.committed
        }
    }
}

internal fun GraphEditorStateService.workspaceStatePresenterProvider(
    requestBrowserSync: () -> Unit = {},
): () -> WorkspaceStatePresenter = {
    WorkspaceStatePresenter(this, requestBrowserSync)
}

internal fun GraphEditorStateService.reviewStatePresenterProvider(
    requestBrowserSync: () -> Unit = {},
): () -> ReviewStatePresenter = {
    ReviewStatePresenter(this, requestBrowserSync)
}

internal fun GraphEditorStateService.generationStatePresenterProvider(
    requestBrowserSync: () -> Unit = {},
): () -> GenerationStatePresenter = {
    GenerationStatePresenter(this, requestBrowserSync)
}

internal fun GraphEditorStateService.applicationEventSink(
    requestBrowserSync: () -> Unit = {},
): GraphEditorApplicationEventSink {
    val riskResolutionService = RiskResolutionService()
    return GraphEditorApplicationEventSink { event ->
        when (event) {
            is GraphEditorApplicationEvent.WorkspaceGraphLoaded ->
                WorkspaceStatePresenter(this, requestBrowserSync).presentGraphLoaded(event.graph, event.source)
            is GraphEditorApplicationEvent.WorkspaceGraphChanged ->
                WorkspaceStatePresenter(this, requestBrowserSync).presentGraphChanged(
                    graph = event.graph,
                    selectedMethodSignature = event.selectedMethodSignature,
                    preserveDraftPatchUndo = event.preserveDraftPatchUndo,
                    workingGraphDirty = event.workingGraphDirty,
                )
            is GraphEditorApplicationEvent.WorkspaceLayoutChanged ->
                WorkspaceStatePresenter(this, requestBrowserSync).presentLayoutChanged(event.positions)
            is GraphEditorApplicationEvent.MermaidImported ->
                WorkspaceStatePresenter(this, requestBrowserSync).presentMermaidImported(event.mermaid, event.graph, event.issues)
            is GraphEditorApplicationEvent.MermaidExported ->
                WorkspaceStatePresenter(this, requestBrowserSync).presentMermaidExported(event.exported, event.copiedToClipboard)
            is GraphEditorApplicationEvent.DiffModeShown ->
                WorkspaceStatePresenter(this, requestBrowserSync).presentDiffMode(event.graph, event.diff)
            is GraphEditorApplicationEvent.SyncPreviewReady ->
                WorkspaceStatePresenter(this, requestBrowserSync).presentSyncPreview(event.items)
            is GraphEditorApplicationEvent.WorkbenchSectionPreferencesChanged ->
                WorkspaceStatePresenter(this, requestBrowserSync).presentWorkbenchSectionPreferences(event.preferences)
            is GraphEditorApplicationEvent.Feedback ->
                SubjectGraphStatePresenter(this, requestBrowserSync).presentFeedback(event.level, event.message, event.preserveLastMessageType)
            is GraphEditorApplicationEvent.AnalysisDisplayModeChanged ->
                SubjectGraphStatePresenter(this, requestBrowserSync).presentAnalysisDisplayMode(event.displayMode)
            is GraphEditorApplicationEvent.SelectedMethodChanged ->
                SubjectGraphStatePresenter(this, requestBrowserSync).presentSelectedMethod(event.signature)
            is GraphEditorApplicationEvent.AnalysisOutcomeLoaded ->
                SubjectGraphStatePresenter(this, requestBrowserSync).presentAnalysisOutcome(event.outcome, event.source)
            is GraphEditorApplicationEvent.DebugGraphLoaded ->
                SubjectGraphStatePresenter(this, requestBrowserSync).presentDebugGraphLoaded(
                    graph = event.graph,
                    source = event.source,
                    selectedMethodSignature = event.selectedMethodSignature,
                    summary = event.summary,
                )
            is GraphEditorApplicationEvent.ResourceNodeAdded ->
                SubjectGraphStatePresenter(this, requestBrowserSync).presentResourceNodeAdded(event.selectedNodeId, event.feedbackMessage)
            is GraphEditorApplicationEvent.NavigationRequested ->
                SourceNavigationStatePresenter(this, requestBrowserSync).presentNavigationRequested(event.nodeId)
            is GraphEditorApplicationEvent.NavigationStarting ->
                SourceNavigationStatePresenter(this, requestBrowserSync).presentNavigationStarting(event.title)
            is GraphEditorApplicationEvent.NavigationOpened ->
                SourceNavigationStatePresenter(this, requestBrowserSync).presentNavigationOpened(
                    nodeId = event.nodeId,
                    targetPath = event.targetPath,
                    line = event.line,
                    column = event.column,
                    title = event.title,
                )
            is GraphEditorApplicationEvent.NavigationNotFound ->
                SourceNavigationStatePresenter(this, requestBrowserSync).presentNavigationNotFound(event.nodeId, event.label)
            is GraphEditorApplicationEvent.NavigationFailed ->
                SourceNavigationStatePresenter(this, requestBrowserSync).presentNavigationFailed(
                    nodeId = event.nodeId,
                    message = event.message,
                    feedbackMessage = event.feedbackMessage,
                    level = event.level,
                )
            GraphEditorApplicationEvent.SettingsOpened ->
                SourceNavigationStatePresenter(this, requestBrowserSync).presentSettingsOpened()
            is GraphEditorApplicationEvent.SettingsOpenFailed ->
                SourceNavigationStatePresenter(this, requestBrowserSync).presentSettingsOpenFailed(event.message)
            is GraphEditorApplicationEvent.DraftPatchPreviewReady ->
                DraftPatchStatePresenter(this, requestBrowserSync).presentPreview(event.result)
            is GraphEditorApplicationEvent.DraftPatchApplied ->
                DraftPatchStatePresenter(this, requestBrowserSync).presentApply(event.result)
            is GraphEditorApplicationEvent.DraftPatchCleared ->
                DraftPatchStatePresenter(this, requestBrowserSync).presentClear(event.result)
            is GraphEditorApplicationEvent.DraftPatchRestored ->
                DraftPatchStatePresenter(this, requestBrowserSync).presentRestore(event.result)
            is GraphEditorApplicationEvent.DraftPatchUndone ->
                DraftPatchStatePresenter(this, requestBrowserSync).presentUndo(event.result)
            is GraphEditorApplicationEvent.DraftChangeConfirmed ->
                ConfirmedDraftStatePresenter(
                    stateService = this,
                    draftValidationEvaluator = riskResolutionService::evaluateDraftValidation,
                    codeEligibilityEvaluator = riskResolutionService::evaluateCodeEligibility,
                    requestBrowserSync = requestBrowserSync,
                ).presentConfirmation(event.result, event.baseSnapshot)
            is GraphEditorApplicationEvent.DraftChangeUnconfirmed ->
                ConfirmedDraftStatePresenter(
                    stateService = this,
                    draftValidationEvaluator = riskResolutionService::evaluateDraftValidation,
                    codeEligibilityEvaluator = riskResolutionService::evaluateCodeEligibility,
                    requestBrowserSync = requestBrowserSync,
                ).presentUnconfirmation(event.result, event.baseSnapshot)
            is GraphEditorApplicationEvent.GenerationPlanReady ->
                GenerationStatePresenter(this, requestBrowserSync).presentGenerationPlan(event.presentation)
            is GraphEditorApplicationEvent.DraftAndCodeEligibilityUpdated ->
                GenerationStatePresenter(this, requestBrowserSync).presentDraftAndCodeEligibility(
                    event.draftValidationState,
                    event.codeEligibilityDecision,
                )
            is GraphEditorApplicationEvent.GenerationRequestStarted ->
                GenerationStatePresenter(this, requestBrowserSync).presentRequestStarted(event.presentation)
            is GraphEditorApplicationEvent.GenerationStreamingPreview ->
                GenerationStatePresenter(this, requestBrowserSync).presentStreamingPreview(
                    event.scene,
                    event.requestId,
                    event.previewText,
                    event.finalizingStructuredResult,
                )
            is GraphEditorApplicationEvent.GenerationPlanRequestFailed ->
                GenerationStatePresenter(this, requestBrowserSync).presentGenerationPlanRequestFailure(event.presentation)
            is GraphEditorApplicationEvent.GenerationDiscussionReady ->
                GenerationStatePresenter(this, requestBrowserSync).presentGenerationPlanDiscussion(event.presentation)
            is GraphEditorApplicationEvent.GenerationDiscussionFailed ->
                GenerationStatePresenter(this, requestBrowserSync).presentGenerationPlanDiscussionFailure(event.presentation)
            is GraphEditorApplicationEvent.CodeDraftWriteReported ->
                GenerationStatePresenter(this, requestBrowserSync).presentCodeDraftWriteReport(event.presentation)
            is GraphEditorApplicationEvent.GenerationFeedback ->
                GenerationStatePresenter(this, requestBrowserSync).presentFeedback(event.level, event.message, event.preserveLastMessageType)
            is GraphEditorApplicationEvent.MergeWriteReported ->
                GenerationStatePresenter(this, requestBrowserSync).presentMergeWriteReport(event.report, event.level, event.message)
            is GraphEditorApplicationEvent.GeneratedCodeDraftsReady ->
                GenerationStatePresenter(this, requestBrowserSync).presentGeneratedCodeDrafts(event.presentation)
            is GraphEditorApplicationEvent.CodeDraftRequestFailed ->
                GenerationStatePresenter(this, requestBrowserSync).presentCodeDraftRequestFailure(event.presentation)
            is GraphEditorApplicationEvent.QaCompleted ->
                ReviewStatePresenter(this, requestBrowserSync).presentQaCompleted(event.presentation)
            is GraphEditorApplicationEvent.ReviewRequestStarted ->
                ReviewStatePresenter(this, requestBrowserSync).presentRequestStarted(event.presentation)
            is GraphEditorApplicationEvent.ReviewStreamingPreview ->
                ReviewStatePresenter(this, requestBrowserSync).presentStreamingPreview(
                    event.scene,
                    event.requestId,
                    event.previewText,
                    event.finalizingStructuredResult,
                )
            is GraphEditorApplicationEvent.QaFailed ->
                ReviewStatePresenter(this, requestBrowserSync).presentQaFailed(event.presentation)
            is GraphEditorApplicationEvent.DiffReviewCompleted ->
                ReviewStatePresenter(this, requestBrowserSync).presentDiffReviewCompleted(event.presentation)
            is GraphEditorApplicationEvent.DiffReviewFailed ->
                ReviewStatePresenter(this, requestBrowserSync).presentDiffReviewFailed(event.presentation)
            is GraphEditorApplicationEvent.BeautificationCompleted ->
                ReviewStatePresenter(this, requestBrowserSync).presentBeautificationCompleted(event.presentation)
            is GraphEditorApplicationEvent.BeautificationFailed ->
                ReviewStatePresenter(this, requestBrowserSync).presentBeautificationFailed(event.presentation)
        }
    }
}
