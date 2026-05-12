package com.charmnight.linkgraph.application.port

import com.charmnight.linkgraph.application.model.ApplicationSnapshot
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.model.GraphLayoutPosition
import com.charmnight.linkgraph.application.usecase.ApplyDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.ClearDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.ConfirmDraftChangeUseCaseResult
import com.charmnight.linkgraph.application.usecase.PreviewDraftPatchUseCaseResult
import com.charmnight.linkgraph.application.usecase.RestoreDraftPatchPreviewUseCaseResult
import com.charmnight.linkgraph.application.usecase.UndoDraftPatchApplyUseCaseResult
import com.charmnight.linkgraph.application.usecase.UnconfirmDraftChangeUseCaseResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.runtime.AgentRunArtifactSummary
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult
import com.charmnight.linkgraph.workbench.ReplayableQaRequest
import com.charmnight.linkgraph.workbench.StageEligibilityDecision

enum class ApplicationFeedbackLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

data class ApplicationRuntimeArtifactSummary(
    val artifactId: String,
    val artifactType: String,
    val title: String,
    val description: String? = null,
) {
    companion object {
        fun from(summary: AgentRunArtifactSummary): ApplicationRuntimeArtifactSummary {
            return ApplicationRuntimeArtifactSummary(
                artifactId = summary.artifactId,
                artifactType = summary.artifactType,
                title = summary.title,
                description = summary.description,
            )
        }
    }
}

data class GenerationPlanPresentation(
    val plan: GenerationPlan,
    val requestState: AsyncRequestState,
    val runtimeArtifacts: List<ApplicationRuntimeArtifactSummary>,
    val feedbackLevel: ApplicationFeedbackLevel,
    val feedbackMessage: String,
)

data class CodeDraftWritePresentation(
    val report: GeneratedCodeDraftWriteReport,
    val feedbackLevel: ApplicationFeedbackLevel? = null,
    val feedbackMessage: String? = null,
)

data class GeneratedCodeDraftsPresentation(
    val drafts: List<GeneratedCodeDraft>,
    val warnings: List<String>,
    val source: LlmResultSource,
    val promptPreview: String?,
    val requestState: AsyncRequestState,
    val runtimeArtifacts: List<ApplicationRuntimeArtifactSummary>,
    val feedbackLevel: ApplicationFeedbackLevel? = null,
    val feedbackMessage: String? = null,
)

data class GenerationRequestFailurePresentation(
    val scene: String,
    val message: String,
    val requestState: AsyncRequestState,
    val runtimeArtifacts: List<ApplicationRuntimeArtifactSummary> = emptyList(),
    val feedbackLevel: ApplicationFeedbackLevel = ApplicationFeedbackLevel.ERROR,
    val preserveLastMessageType: Boolean = true,
)

data class GenerationRequestStartedPresentation(
    val scene: GenerationRequestScene,
    val requestState: AsyncRequestState,
    val feedbackMessage: String,
    val clearRuntimeArtifactScene: String? = null,
)

data class GenerationDiscussionPresentation(
    val result: GenerationPlanDiscussionResult,
    val requestState: AsyncRequestState,
    val feedbackLevel: ApplicationFeedbackLevel,
    val feedbackMessage: String,
)

enum class GenerationRequestScene {
    PLAN,
    PLAN_DISCUSSION,
    CODE_DRAFT,
}

data class QaCompletedPresentation(
    val result: GraphPatchResult,
    val requestState: AsyncRequestState,
    val completedRequest: ReplayableQaRequest? = null,
    val draftValidationState: DraftValidationState?,
    val codeEligibilityDecision: StageEligibilityDecision?,
    val runtimeArtifacts: List<ApplicationRuntimeArtifactSummary>,
    val feedbackLevel: ApplicationFeedbackLevel? = null,
    val feedbackMessage: String? = null,
)

data class QaFailedPresentation(
    val message: String,
    val requestState: AsyncRequestState,
    val failedRequest: ReplayableQaRequest? = null,
    val runtimeArtifacts: List<ApplicationRuntimeArtifactSummary> = emptyList(),
    val feedbackLevel: ApplicationFeedbackLevel = ApplicationFeedbackLevel.ERROR,
    val preserveLastMessageType: Boolean = true,
)

data class DiffReviewCompletedPresentation(
    val result: GraphPatchResult,
    val requestState: AsyncRequestState,
    val feedbackLevel: ApplicationFeedbackLevel? = null,
    val feedbackMessage: String? = null,
)

data class DiffReviewFailedPresentation(
    val message: String,
    val requestState: AsyncRequestState,
    val feedbackLevel: ApplicationFeedbackLevel = ApplicationFeedbackLevel.ERROR,
    val preserveLastMessageType: Boolean = true,
)

data class BeautificationCompletedPresentation(
    val result: GraphBeautificationResult,
    val requestState: AsyncRequestState,
    val feedbackLevel: ApplicationFeedbackLevel? = null,
    val feedbackMessage: String? = null,
)

data class BeautificationFailedPresentation(
    val message: String,
    val requestState: AsyncRequestState,
    val feedbackLevel: ApplicationFeedbackLevel = ApplicationFeedbackLevel.ERROR,
    val preserveLastMessageType: Boolean = true,
)

enum class ReviewRequestScene {
    QA,
    DIFF_REVIEW,
    BEAUTIFICATION,
}

data class ReviewRequestStartedPresentation(
    val scene: ReviewRequestScene,
    val requestState: AsyncRequestState,
    val feedbackMessage: String,
    val submittedRequest: ReplayableQaRequest? = null,
    val clearRuntimeArtifactScene: String? = null,
)

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
        val preserveLastMessageType: Boolean = false,
    ) : GraphEditorApplicationEvent
    data class AnalysisDisplayModeChanged(val displayMode: AnalysisDisplayMode) : GraphEditorApplicationEvent
    data class SelectedMethodChanged(val signature: String) : GraphEditorApplicationEvent
    data class AnalysisOutcomeLoaded(val outcome: AnalysisOutcome, val source: String) : GraphEditorApplicationEvent
    data class DebugGraphLoaded(
        val graph: GraphDocument,
        val source: String,
        val selectedMethodSignature: String,
        val summary: String,
    ) : GraphEditorApplicationEvent
    data class ResourceNodeAdded(val selectedNodeId: String, val feedbackMessage: String) : GraphEditorApplicationEvent

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
        val feedbackMessage: String = "打开源码失败：$message",
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

    data class GenerationPlanReady(val presentation: GenerationPlanPresentation) : GraphEditorApplicationEvent
    data class DraftAndCodeEligibilityUpdated(
        val draftValidationState: DraftValidationState?,
        val codeEligibilityDecision: StageEligibilityDecision?,
    ) : GraphEditorApplicationEvent
    data class GenerationRequestStarted(val presentation: GenerationRequestStartedPresentation) : GraphEditorApplicationEvent
    data class GenerationStreamingPreview(
        val scene: GenerationRequestScene,
        val requestId: Long,
        val previewText: String,
        val finalizingStructuredResult: Boolean,
    ) : GraphEditorApplicationEvent
    data class GenerationPlanRequestFailed(val presentation: GenerationRequestFailurePresentation) : GraphEditorApplicationEvent
    data class GenerationDiscussionReady(val presentation: GenerationDiscussionPresentation) : GraphEditorApplicationEvent
    data class GenerationDiscussionFailed(val presentation: GenerationRequestFailurePresentation) : GraphEditorApplicationEvent
    data class CodeDraftWriteReported(val presentation: CodeDraftWritePresentation) : GraphEditorApplicationEvent
    data class GenerationFeedback(
        val level: ApplicationFeedbackLevel,
        val message: String,
        val preserveLastMessageType: Boolean = false,
    ) : GraphEditorApplicationEvent
    data class MergeWriteReported(
        val report: GeneratedCodeDraftWriteReport,
        val level: ApplicationFeedbackLevel,
        val message: String,
    ) : GraphEditorApplicationEvent
    data class GeneratedCodeDraftsReady(val presentation: GeneratedCodeDraftsPresentation) : GraphEditorApplicationEvent
    data class CodeDraftRequestFailed(val presentation: GenerationRequestFailurePresentation) : GraphEditorApplicationEvent

    data class QaCompleted(val presentation: QaCompletedPresentation) : GraphEditorApplicationEvent
    data class ReviewRequestStarted(val presentation: ReviewRequestStartedPresentation) : GraphEditorApplicationEvent
    data class ReviewStreamingPreview(
        val scene: ReviewRequestScene,
        val requestId: Long,
        val previewText: String,
        val finalizingStructuredResult: Boolean,
    ) : GraphEditorApplicationEvent
    data class QaFailed(val presentation: QaFailedPresentation) : GraphEditorApplicationEvent
    data class DiffReviewCompleted(val presentation: DiffReviewCompletedPresentation) : GraphEditorApplicationEvent
    data class DiffReviewFailed(val presentation: DiffReviewFailedPresentation) : GraphEditorApplicationEvent
    data class BeautificationCompleted(val presentation: BeautificationCompletedPresentation) : GraphEditorApplicationEvent
    data class BeautificationFailed(val presentation: BeautificationFailedPresentation) : GraphEditorApplicationEvent
}

fun interface GraphEditorApplicationEventSink {
    fun emit(event: GraphEditorApplicationEvent)
}

interface GraphEditorPresentationProvider {
    fun editorSnapshotProvider(): EditorSnapshotProvider
    fun applicationSnapshotProvider(): ApplicationSnapshotProvider
    fun toolGraphSnapshotProvider(): ToolGraphSnapshotProvider
    fun workspaceGraphCommitter(): WorkspaceGraphCommitter
    fun eventSink(): GraphEditorApplicationEventSink
}
