package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.workbench.RiskResolutionService

internal class GraphEditorApplicationEventProjector(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit,
    private val riskResolutionService: RiskResolutionService = RiskResolutionService(),
) {
    fun eventSink(): GraphEditorApplicationEventSink =
        GraphEditorApplicationEventSink { event -> present(event) }

    private fun workspacePresenter(): WorkspaceStatePresenter =
        WorkspaceStatePresenter(stateService, requestBrowserSync)

    private fun subjectPresenter(): SubjectGraphStatePresenter =
        SubjectGraphStatePresenter(stateService, requestBrowserSync)

    private fun reviewPresenter(): ReviewStatePresenter =
        ReviewStatePresenter(stateService, requestBrowserSync)

    private fun generationPresenter(): GenerationStatePresenter =
        GenerationStatePresenter(stateService, requestBrowserSync)

    private fun sourceNavigationPresenter(): SourceNavigationStatePresenter =
        SourceNavigationStatePresenter(stateService, requestBrowserSync)

    private fun architecturePresenter(): ArchitectureGraphStatePresenter =
        ArchitectureGraphStatePresenter(stateService, requestBrowserSync)

    private fun draftPatchPresenter(): DraftPatchStatePresenter =
        DraftPatchStatePresenter(stateService, requestBrowserSync)

    private fun confirmedDraftPresenter(): ConfirmedDraftStatePresenter =
        ConfirmedDraftStatePresenter(
            stateService = stateService,
            draftValidationEvaluator = riskResolutionService::evaluateDraftValidation,
            codeEligibilityEvaluator = riskResolutionService::evaluateCodeEligibility,
            requestBrowserSync = requestBrowserSync,
        )

    private fun present(event: GraphEditorApplicationEvent) {
        when (event) {
            is GraphEditorApplicationEvent.WorkspaceGraphLoaded ->
                workspacePresenter().presentGraphLoaded(event.graph, event.source)
            is GraphEditorApplicationEvent.WorkspaceGraphChanged ->
                workspacePresenter().presentGraphChanged(
                    graph = event.graph,
                    selectedMethodSignature = event.selectedMethodSignature,
                    preserveDraftPatchUndo = event.preserveDraftPatchUndo,
                    workingGraphDirty = event.workingGraphDirty,
                )
            is GraphEditorApplicationEvent.WorkspaceLayoutChanged ->
                workspacePresenter().presentLayoutChanged(event.positions)
            is GraphEditorApplicationEvent.MermaidImported ->
                workspacePresenter().presentMermaidImported(event.mermaid, event.graph, event.issues)
            is GraphEditorApplicationEvent.MermaidExported ->
                workspacePresenter().presentMermaidExported(event.exported, event.copiedToClipboard)
            is GraphEditorApplicationEvent.DiffModeShown ->
                workspacePresenter().presentDiffMode(event.graph, event.diff)
            is GraphEditorApplicationEvent.SyncPreviewReady ->
                workspacePresenter().presentSyncPreview(event.items)
            is GraphEditorApplicationEvent.WorkbenchSectionPreferencesChanged ->
                workspacePresenter().presentWorkbenchSectionPreferences(event.preferences)
            is GraphEditorApplicationEvent.Feedback ->
                subjectPresenter().presentFeedback(event.level, event.message, event.preservePreviousStatusKind)
            is GraphEditorApplicationEvent.AnalysisDisplayModeChanged ->
                subjectPresenter().presentAnalysisDisplayMode(event.displayMode)
            is GraphEditorApplicationEvent.SelectedMethodChanged ->
                subjectPresenter().presentSelectedMethod(event.signature)
            is GraphEditorApplicationEvent.AnalysisOutcomeLoaded ->
                subjectPresenter().presentAnalysisOutcome(event.outcome, event.source)
            is GraphEditorApplicationEvent.IndexedGraphRequestStarted ->
                architecturePresenter().presentIndexedGraphRequestStarted(
                    event.view,
                    event.requestState,
                    event.statusMessage,
                )
            is GraphEditorApplicationEvent.IndexedGraphRequestFailed ->
                architecturePresenter().presentIndexedGraphRequestFailed(
                    event.view,
                    event.requestState,
                    event.statusMessage,
                )
            is GraphEditorApplicationEvent.ArchitectureGraphLoaded ->
                architecturePresenter().presentArchitectureGraph(event.view, event.requestState, event.statusMessage)
            is GraphEditorApplicationEvent.ClassDiagramLoaded ->
                architecturePresenter().presentClassDiagram(event.view, event.requestState, event.statusMessage)
            is GraphEditorApplicationEvent.ReviewGraphLoaded ->
                architecturePresenter().presentReviewGraph(event.view, event.requestState, event.statusMessage)
            is GraphEditorApplicationEvent.DebugGraphLoaded ->
                subjectPresenter().presentDebugGraphLoaded(
                    graph = event.graph,
                    source = event.source,
                    selectedMethodSignature = event.selectedMethodSignature,
                    summary = event.summary,
                )
            is GraphEditorApplicationEvent.ResourceNodeAdded ->
                subjectPresenter().presentResourceNodeAdded(event.selectedNodeId, event.statusMessage)
            is GraphEditorApplicationEvent.NavigationRequested ->
                sourceNavigationPresenter().presentNavigationRequested(event.nodeId)
            is GraphEditorApplicationEvent.NavigationStarting ->
                sourceNavigationPresenter().presentNavigationStarting(event.title)
            is GraphEditorApplicationEvent.NavigationOpened ->
                sourceNavigationPresenter().presentNavigationOpened(
                    nodeId = event.nodeId,
                    targetPath = event.targetPath,
                    line = event.line,
                    column = event.column,
                    title = event.title,
                )
            is GraphEditorApplicationEvent.NavigationNotFound ->
                sourceNavigationPresenter().presentNavigationNotFound(event.nodeId, event.label)
            is GraphEditorApplicationEvent.NavigationFailed ->
                sourceNavigationPresenter().presentNavigationFailed(
                    nodeId = event.nodeId,
                    message = event.message,
                    statusMessage = event.statusMessage,
                    level = event.level,
                )
            GraphEditorApplicationEvent.SettingsOpened ->
                sourceNavigationPresenter().presentSettingsOpened()
            is GraphEditorApplicationEvent.SettingsOpenFailed ->
                sourceNavigationPresenter().presentSettingsOpenFailed(event.message)
            is GraphEditorApplicationEvent.DraftPatchPreviewReady ->
                draftPatchPresenter().presentPreview(event.result)
            is GraphEditorApplicationEvent.DraftPatchApplied ->
                draftPatchPresenter().presentApply(event.result)
            is GraphEditorApplicationEvent.DraftPatchCleared ->
                draftPatchPresenter().presentClear(event.result)
            is GraphEditorApplicationEvent.DraftPatchRestored ->
                draftPatchPresenter().presentRestore(event.result)
            is GraphEditorApplicationEvent.DraftPatchUndone ->
                draftPatchPresenter().presentUndo(event.result)
            is GraphEditorApplicationEvent.DraftChangeConfirmed ->
                confirmedDraftPresenter().presentConfirmation(event.result, event.baseSnapshot)
            is GraphEditorApplicationEvent.DraftChangeUnconfirmed ->
                confirmedDraftPresenter().presentUnconfirmation(event.result, event.baseSnapshot)
            is GraphEditorApplicationEvent.GenerationPlanReady ->
                generationPresenter().presentGenerationPlan(event.result)
            is GraphEditorApplicationEvent.DraftAndCodeEligibilityUpdated ->
                generationPresenter().presentDraftAndCodeEligibility(
                    event.draftValidationState,
                    event.codeEligibilityDecision,
                )
            is GraphEditorApplicationEvent.GenerationRequestStarted ->
                generationPresenter().presentRequestStarted(event.result)
            is GraphEditorApplicationEvent.GenerationStreamingPreview ->
                generationPresenter().presentStreamingPreview(
                    event.scene,
                    event.requestId,
                    event.previewText,
                    event.finalizingStructuredResult,
                )
            is GraphEditorApplicationEvent.GenerationPlanRequestFailed ->
                generationPresenter().presentGenerationPlanRequestFailure(event.result)
            is GraphEditorApplicationEvent.GenerationDiscussionReady ->
                generationPresenter().presentGenerationPlanDiscussion(event.result)
            is GraphEditorApplicationEvent.GenerationDiscussionFailed ->
                generationPresenter().presentGenerationPlanDiscussionFailure(event.result)
            is GraphEditorApplicationEvent.CodeDraftWriteReported ->
                generationPresenter().presentCodeDraftWriteReport(event.result)
            is GraphEditorApplicationEvent.GenerationFeedback ->
                generationPresenter().presentFeedback(event.level, event.message, event.preservePreviousStatusKind)
            is GraphEditorApplicationEvent.MergeWriteReported ->
                generationPresenter().presentMergeWriteReport(event.report, event.level, event.message)
            is GraphEditorApplicationEvent.GeneratedCodeDraftsReady ->
                generationPresenter().presentGeneratedCodeDrafts(event.result)
            is GraphEditorApplicationEvent.CodeDraftRequestFailed ->
                generationPresenter().presentCodeDraftRequestFailure(event.result)
            is GraphEditorApplicationEvent.QaCompleted ->
                reviewPresenter().presentQaCompleted(event.result)
            is GraphEditorApplicationEvent.ReviewRequestStarted ->
                reviewPresenter().presentRequestStarted(event.result)
            is GraphEditorApplicationEvent.ReviewStreamingPreview ->
                reviewPresenter().presentStreamingPreview(
                    event.scene,
                    event.requestId,
                    event.previewText,
                    event.finalizingStructuredResult,
                )
            is GraphEditorApplicationEvent.QaFailed ->
                reviewPresenter().presentQaFailed(event.result)
            is GraphEditorApplicationEvent.DiffReviewCompleted ->
                reviewPresenter().presentDiffReviewCompleted(event.result)
            is GraphEditorApplicationEvent.DiffReviewFailed ->
                reviewPresenter().presentDiffReviewFailed(event.result)
            is GraphEditorApplicationEvent.BeautificationCompleted ->
                reviewPresenter().presentBeautificationCompleted(event.result)
            is GraphEditorApplicationEvent.BeautificationFailed ->
                reviewPresenter().presentBeautificationFailed(event.result)
        }
    }
}
