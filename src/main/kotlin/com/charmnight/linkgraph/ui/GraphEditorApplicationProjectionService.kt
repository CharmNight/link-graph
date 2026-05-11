package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.port.ApplicationSnapshotProvider
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.GraphEditorPresentationProvider
import com.charmnight.linkgraph.application.port.ToolGraphSnapshotProvider
import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.ui.GraphEditorSyncNotifier
import com.charmnight.linkgraph.ui.toToolGraphSnapshot
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
internal class GraphEditorApplicationProjectionService(
    private val project: Project,
) : GraphEditorPresentationProvider {
    private val stateService: GraphEditorStateService
        get() = project.getService(GraphEditorStateService::class.java)

    private val syncNotifier: GraphEditorSyncNotifier
        get() = project.getService(GraphEditorSyncNotifier::class.java)

    private val riskResolutionService by lazy(LazyThreadSafetyMode.NONE) {
        RiskResolutionService()
    }

    override fun editorSnapshotProvider(): EditorSnapshotProvider =
        EditorSnapshotProvider { stateService.snapshot().toWorkflowEditorSnapshot() }

    override fun applicationSnapshotProvider(): ApplicationSnapshotProvider =
        ApplicationSnapshotProvider { stateService.snapshot().toApplicationSnapshot() }

    override fun toolGraphSnapshotProvider(): ToolGraphSnapshotProvider =
        ToolGraphSnapshotProvider { stateService.snapshot().toToolGraphSnapshot() }

    override fun workspaceGraphCommitter(): WorkspaceGraphCommitter {
        return object : WorkspaceGraphCommitter {
            override fun commitWorkspaceGraph(
                expectedSnapshotRevision: Long?,
                graph: GraphDocument,
                selectedMethodSignature: String?,
                preserveDraftPatchUndo: Boolean,
                workingGraphDirty: Boolean,
                syncBrowser: Boolean,
            ): Boolean {
                val currentStateService = stateService
                val revision = expectedSnapshotRevision ?: currentStateService.snapshot().snapshotRevision
                val commitResult = currentStateService.tryCommit(revision) { current ->
                    current.withWorkspaceGraphChanged(
                        graph = graph,
                        selectedMethodSignatureOverride = selectedMethodSignature,
                        preserveDraftPatchUndo = preserveDraftPatchUndo,
                        workingGraphDirtyOverride = workingGraphDirty,
                    )
                }
                if (syncBrowser) {
                    syncNotifier.requestSync()
                }
                return commitResult.committed
            }
        }
    }

    override fun eventSink(): GraphEditorApplicationEventSink =
        GraphEditorApplicationEventSink { event -> present(event) }

    private fun workspacePresenter(): WorkspaceStatePresenter =
        WorkspaceStatePresenter(stateService, syncNotifier::requestSync)

    private fun subjectPresenter(): SubjectGraphStatePresenter =
        SubjectGraphStatePresenter(stateService, syncNotifier::requestSync)

    private fun reviewPresenter(): ReviewStatePresenter =
        ReviewStatePresenter(stateService, syncNotifier::requestSync)

    private fun generationPresenter(): GenerationStatePresenter =
        GenerationStatePresenter(stateService, syncNotifier::requestSync)

    private fun sourceNavigationPresenter(): SourceNavigationStatePresenter =
        SourceNavigationStatePresenter(stateService, syncNotifier::requestSync)

    private fun draftPatchPresenter(): DraftPatchStatePresenter =
        DraftPatchStatePresenter(stateService, syncNotifier::requestSync)

    private fun confirmedDraftPresenter(): ConfirmedDraftStatePresenter =
        ConfirmedDraftStatePresenter(
            stateService = stateService,
            draftValidationEvaluator = riskResolutionService::evaluateDraftValidation,
            codeEligibilityEvaluator = riskResolutionService::evaluateCodeEligibility,
            requestBrowserSync = syncNotifier::requestSync,
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
                subjectPresenter().presentFeedback(event.level, event.message, event.preserveLastMessageType)
            is GraphEditorApplicationEvent.AnalysisDisplayModeChanged ->
                subjectPresenter().presentAnalysisDisplayMode(event.displayMode)
            is GraphEditorApplicationEvent.SelectedMethodChanged ->
                subjectPresenter().presentSelectedMethod(event.signature)
            is GraphEditorApplicationEvent.AnalysisOutcomeLoaded ->
                subjectPresenter().presentAnalysisOutcome(event.outcome, event.source)
            is GraphEditorApplicationEvent.DebugGraphLoaded ->
                subjectPresenter().presentDebugGraphLoaded(
                    graph = event.graph,
                    source = event.source,
                    selectedMethodSignature = event.selectedMethodSignature,
                    summary = event.summary,
                )
            is GraphEditorApplicationEvent.ResourceNodeAdded ->
                subjectPresenter().presentResourceNodeAdded(event.selectedNodeId, event.feedbackMessage)
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
                    feedbackMessage = event.feedbackMessage,
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
                generationPresenter().presentGenerationPlan(event.presentation)
            is GraphEditorApplicationEvent.DraftAndCodeEligibilityUpdated ->
                generationPresenter().presentDraftAndCodeEligibility(
                    event.draftValidationState,
                    event.codeEligibilityDecision,
                )
            is GraphEditorApplicationEvent.GenerationRequestStarted ->
                generationPresenter().presentRequestStarted(event.presentation)
            is GraphEditorApplicationEvent.GenerationStreamingPreview ->
                generationPresenter().presentStreamingPreview(
                    event.scene,
                    event.requestId,
                    event.previewText,
                    event.finalizingStructuredResult,
                )
            is GraphEditorApplicationEvent.GenerationPlanRequestFailed ->
                generationPresenter().presentGenerationPlanRequestFailure(event.presentation)
            is GraphEditorApplicationEvent.GenerationDiscussionReady ->
                generationPresenter().presentGenerationPlanDiscussion(event.presentation)
            is GraphEditorApplicationEvent.GenerationDiscussionFailed ->
                generationPresenter().presentGenerationPlanDiscussionFailure(event.presentation)
            is GraphEditorApplicationEvent.CodeDraftWriteReported ->
                generationPresenter().presentCodeDraftWriteReport(event.presentation)
            is GraphEditorApplicationEvent.GenerationFeedback ->
                generationPresenter().presentFeedback(event.level, event.message, event.preserveLastMessageType)
            is GraphEditorApplicationEvent.MergeWriteReported ->
                generationPresenter().presentMergeWriteReport(event.report, event.level, event.message)
            is GraphEditorApplicationEvent.GeneratedCodeDraftsReady ->
                generationPresenter().presentGeneratedCodeDrafts(event.presentation)
            is GraphEditorApplicationEvent.CodeDraftRequestFailed ->
                generationPresenter().presentCodeDraftRequestFailure(event.presentation)
            is GraphEditorApplicationEvent.QaCompleted ->
                reviewPresenter().presentQaCompleted(event.presentation)
            is GraphEditorApplicationEvent.ReviewRequestStarted ->
                reviewPresenter().presentRequestStarted(event.presentation)
            is GraphEditorApplicationEvent.ReviewStreamingPreview ->
                reviewPresenter().presentStreamingPreview(
                    event.scene,
                    event.requestId,
                    event.previewText,
                    event.finalizingStructuredResult,
                )
            is GraphEditorApplicationEvent.QaFailed ->
                reviewPresenter().presentQaFailed(event.presentation)
            is GraphEditorApplicationEvent.DiffReviewCompleted ->
                reviewPresenter().presentDiffReviewCompleted(event.presentation)
            is GraphEditorApplicationEvent.DiffReviewFailed ->
                reviewPresenter().presentDiffReviewFailed(event.presentation)
            is GraphEditorApplicationEvent.BeautificationCompleted ->
                reviewPresenter().presentBeautificationCompleted(event.presentation)
            is GraphEditorApplicationEvent.BeautificationFailed ->
                reviewPresenter().presentBeautificationFailed(event.presentation)
        }
    }
}
