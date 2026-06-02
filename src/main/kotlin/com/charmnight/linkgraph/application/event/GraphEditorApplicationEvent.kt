package com.charmnight.linkgraph.application.event

import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.model.ApplicationSnapshot
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.model.GraphLayoutPosition
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.result.BeautificationCompletedResult
import com.charmnight.linkgraph.application.result.BeautificationFailedResult
import com.charmnight.linkgraph.application.result.CodeDraftWriteResult
import com.charmnight.linkgraph.application.result.DiffReviewCompletedResult
import com.charmnight.linkgraph.application.result.DiffReviewFailedResult
import com.charmnight.linkgraph.application.result.GeneratedCodeDraftsResult
import com.charmnight.linkgraph.application.result.GenerationDiscussionResult
import com.charmnight.linkgraph.application.result.GenerationPlanResult
import com.charmnight.linkgraph.application.result.GenerationRequestFailureResult
import com.charmnight.linkgraph.application.result.GenerationRequestScene
import com.charmnight.linkgraph.application.result.GenerationRequestStartedResult
import com.charmnight.linkgraph.application.result.QaCompletedResult
import com.charmnight.linkgraph.application.result.QaFailedResult
import com.charmnight.linkgraph.application.result.ReviewRequestScene
import com.charmnight.linkgraph.application.result.ReviewRequestStartedResult
import com.charmnight.linkgraph.application.usecase.ApplyDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.ClearDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.ConfirmDraftChangeUseCaseResult
import com.charmnight.linkgraph.application.usecase.PreviewDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.RestoreDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.UndoDraftPatchApplyUseCaseResult
import com.charmnight.linkgraph.application.usecase.UnconfirmDraftChangeUseCaseResult
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.review.ReviewGraphResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

sealed interface GraphEditorApplicationEvent {
    data class WorkspaceGraphLoaded(val graph: GraphDocument, val source: String) : GraphEditorApplicationEvent
    data class WorkspaceGraphChanged(
        val graph: GraphDocument,
        val selectedMethodSignature: String?,
        val preserveDraftPatchUndo: Boolean,
        val workingGraphDirty: Boolean,
    ) : GraphEditorApplicationEvent
    data class WorkspaceLayoutChanged(val positions: Map<String, GraphLayoutPosition>) : GraphEditorApplicationEvent
    data class MermaidImported(val mermaid: String, val graph: GraphDocument, val issues: List<MermaidIssue>) : GraphEditorApplicationEvent
    data class MermaidExported(val exported: String, val copiedToClipboard: Boolean) : GraphEditorApplicationEvent
    data class DiffModeShown(val graph: GraphDocument, val diff: GraphDiff) : GraphEditorApplicationEvent
    data class SyncPreviewReady(val items: List<SyncPreviewItem>) : GraphEditorApplicationEvent
    data class WorkbenchSectionPreferencesChanged(val preferences: Map<String, Boolean>) : GraphEditorApplicationEvent

    data class Feedback(
        val level: ApplicationFeedbackLevel,
        val message: String,
        val preservePreviousStatusKind: Boolean = false,
    ) : GraphEditorApplicationEvent
    data class AnalysisDisplayModeChanged(val displayMode: AnalysisDisplayMode) : GraphEditorApplicationEvent
    data class SelectedMethodChanged(val signature: String) : GraphEditorApplicationEvent
    data class AnalysisOutcomeLoaded(val outcome: AnalysisOutcome, val source: String) : GraphEditorApplicationEvent
    data class IndexedGraphRequestStarted(
        val view: IndexedGraphView,
        val requestState: AsyncRequestState,
        val statusMessage: String,
    ) : GraphEditorApplicationEvent
    data class IndexedGraphRequestFailed(
        val view: IndexedGraphView,
        val requestState: AsyncRequestState,
        val statusMessage: String,
    ) : GraphEditorApplicationEvent
    data class ArchitectureGraphLoaded(
        val view: ArchitectureGraphResult,
        val requestState: AsyncRequestState,
        val statusMessage: String,
    ) : GraphEditorApplicationEvent
    data class ClassDiagramLoaded(
        val view: ClassDiagramResult,
        val requestState: AsyncRequestState,
        val statusMessage: String,
    ) : GraphEditorApplicationEvent
    data class ReviewGraphLoaded(
        val view: ReviewGraphResult,
        val requestState: AsyncRequestState,
        val statusMessage: String,
    ) : GraphEditorApplicationEvent
    data class DebugGraphLoaded(
        val graph: GraphDocument,
        val source: String,
        val selectedMethodSignature: String,
        val summary: String,
    ) : GraphEditorApplicationEvent
    data class ResourceNodeAdded(val selectedNodeId: String, val statusMessage: String) : GraphEditorApplicationEvent

    data class NavigationRequested(val nodeId: String) : GraphEditorApplicationEvent
    data class NavigationStarting(val title: String) : GraphEditorApplicationEvent
    data class NavigationOpened(
        val nodeId: String,
        val targetPath: String,
        val line: Int?,
        val column: Int?,
        val title: String,
    ) : GraphEditorApplicationEvent
    data class NavigationNotFound(val nodeId: String, val label: String) : GraphEditorApplicationEvent
    data class NavigationFailed(
        val nodeId: String,
        val message: String,
        val statusMessage: String = "打开源码失败：$message",
        val level: ApplicationFeedbackLevel = ApplicationFeedbackLevel.ERROR,
    ) : GraphEditorApplicationEvent
    data object SettingsOpened : GraphEditorApplicationEvent
    data class SettingsOpenFailed(val message: String) : GraphEditorApplicationEvent

    data class DraftPatchPreviewReady(val result: PreviewDraftPatchUseCaseResult) : GraphEditorApplicationEvent
    data class DraftPatchApplied(val result: ApplyDraftPatchUseCaseResult) : GraphEditorApplicationEvent
    data class DraftPatchCleared(val result: ClearDraftPatchPreviewUseCaseResult) : GraphEditorApplicationEvent
    data class DraftPatchRestored(val result: RestoreDraftPatchPreviewUseCaseResult) : GraphEditorApplicationEvent
    data class DraftPatchUndone(val result: UndoDraftPatchApplyUseCaseResult) : GraphEditorApplicationEvent
    data class DraftChangeConfirmed(
        val result: ConfirmDraftChangeUseCaseResult,
        val baseSnapshot: ApplicationSnapshot,
    ) : GraphEditorApplicationEvent
    data class DraftChangeUnconfirmed(
        val result: UnconfirmDraftChangeUseCaseResult,
        val baseSnapshot: ApplicationSnapshot,
    ) : GraphEditorApplicationEvent

    data class GenerationPlanReady(val result: GenerationPlanResult) : GraphEditorApplicationEvent
    data class DraftAndCodeEligibilityUpdated(
        val draftValidationState: DraftValidationState?,
        val codeEligibilityDecision: StageEligibilityDecision?,
    ) : GraphEditorApplicationEvent
    data class GenerationRequestStarted(val result: GenerationRequestStartedResult) : GraphEditorApplicationEvent
    data class GenerationStreamingPreview(
        val scene: GenerationRequestScene,
        val requestId: Long,
        val previewText: String,
        val finalizingStructuredResult: Boolean,
    ) : GraphEditorApplicationEvent
    data class GenerationPlanRequestFailed(val result: GenerationRequestFailureResult) : GraphEditorApplicationEvent
    data class GenerationDiscussionReady(val result: GenerationDiscussionResult) : GraphEditorApplicationEvent
    data class GenerationDiscussionFailed(val result: GenerationRequestFailureResult) : GraphEditorApplicationEvent
    data class CodeDraftWriteReported(val result: CodeDraftWriteResult) : GraphEditorApplicationEvent
    data class GenerationFeedback(
        val level: ApplicationFeedbackLevel,
        val message: String,
        val preservePreviousStatusKind: Boolean = false,
    ) : GraphEditorApplicationEvent
    data class MergeWriteReported(
        val report: GeneratedCodeDraftWriteReport,
        val level: ApplicationFeedbackLevel,
        val message: String,
    ) : GraphEditorApplicationEvent
    data class GeneratedCodeDraftsReady(val result: GeneratedCodeDraftsResult) : GraphEditorApplicationEvent
    data class CodeDraftRequestFailed(val result: GenerationRequestFailureResult) : GraphEditorApplicationEvent

    data class QaCompleted(val result: QaCompletedResult) : GraphEditorApplicationEvent
    data class ReviewRequestStarted(val result: ReviewRequestStartedResult) : GraphEditorApplicationEvent
    data class ReviewStreamingPreview(
        val scene: ReviewRequestScene,
        val requestId: Long,
        val previewText: String,
        val finalizingStructuredResult: Boolean,
    ) : GraphEditorApplicationEvent
    data class QaFailed(val result: QaFailedResult) : GraphEditorApplicationEvent
    data class DiffReviewCompleted(val result: DiffReviewCompletedResult) : GraphEditorApplicationEvent
    data class DiffReviewFailed(val result: DiffReviewFailedResult) : GraphEditorApplicationEvent
    data class BeautificationCompleted(val result: BeautificationCompletedResult) : GraphEditorApplicationEvent
    data class BeautificationFailed(val result: BeautificationFailedResult) : GraphEditorApplicationEvent
}

fun interface GraphEditorApplicationEventSink {
    fun emit(event: GraphEditorApplicationEvent)
}
