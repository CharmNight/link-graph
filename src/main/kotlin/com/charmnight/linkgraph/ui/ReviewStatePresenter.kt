package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.result.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.application.result.QaCompletedResult
import com.charmnight.linkgraph.application.result.QaFailedResult
import com.charmnight.linkgraph.application.result.BeautificationCompletedResult
import com.charmnight.linkgraph.application.result.BeautificationFailedResult
import com.charmnight.linkgraph.application.result.DiffReviewCompletedResult
import com.charmnight.linkgraph.application.result.DiffReviewFailedResult
import com.charmnight.linkgraph.application.result.ReviewRequestScene
import com.charmnight.linkgraph.application.result.ReviewRequestStartedResult

class ReviewStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    fun presentQaCompleted(presentation: QaCompletedResult) {
        stateService.workbench.markRuntimeArtifactSummaries("qa", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markQaResult(
            presentation.result,
            presentation.requestState,
            completedRequest = presentation.completedRequest,
        )
        stateService.workbench.markDraftValidationState(presentation.draftValidationState)
        stateService.workbench.markCodeEligibilityDecision(presentation.codeEligibilityDecision)
        markOptionalFeedback(presentation.feedbackLevel, presentation.statusMessage)
        requestBrowserSync()
    }

    fun presentRequestStarted(presentation: ReviewRequestStartedResult) {
        when (presentation.scene) {
            ReviewRequestScene.QA -> {
                stateService.asyncRequests.beginQaRequest(
                    presentation.requestState,
                    submittedRequest = presentation.submittedRequest,
                )
            }
            ReviewRequestScene.DIFF_REVIEW -> stateService.asyncRequests.beginDiffReviewRequest(
                presentation.requestState,
                selectedDiffItemIds = presentation.selectedDiffItemIds,
            )
            ReviewRequestScene.BEAUTIFICATION -> stateService.asyncRequests.beginGraphBeautificationRequest(presentation.requestState)
        }
        presentation.clearRuntimeArtifactScene?.let { scene ->
            stateService.workbench.markRuntimeArtifactSummaries(scene, emptyList())
        }
        stateService.workbench.markOperationFeedback(
            OperationFeedbackLevel.INFO,
            presentation.statusMessage,
        )
        requestBrowserSync()
    }

    fun presentStreamingPreview(
        scene: ReviewRequestScene,
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean,
    ) {
        when (scene) {
            ReviewRequestScene.QA -> {
                stateService.asyncRequests.updateQaRequestPreview(
                    requestId,
                    previewText,
                    finalizingStructuredResult,
                )
            }
            ReviewRequestScene.DIFF_REVIEW -> {
                stateService.asyncRequests.updateDiffReviewRequestPreview(
                    requestId,
                    previewText,
                    finalizingStructuredResult,
                )
            }
            ReviewRequestScene.BEAUTIFICATION -> {
                stateService.asyncRequests.updateGraphBeautificationRequestPreview(
                    requestId,
                    previewText,
                    finalizingStructuredResult,
                )
            }
        }
        requestBrowserSync()
    }

    fun presentQaFailed(presentation: QaFailedResult) {
        stateService.workbench.markRuntimeArtifactSummaries("qa", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markQaRequestFailed(
            presentation.message,
            presentation.requestState,
            failedRequest = presentation.failedRequest,
        )
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel.toOperationFeedbackLevel(),
            presentation.message,
            preservePreviousStatusKind = presentation.preservePreviousStatusKind,
        )
        requestBrowserSync()
    }

    fun presentDiffReviewCompleted(presentation: DiffReviewCompletedResult) {
        stateService.asyncRequests.markDiffReviewResult(
            presentation.result,
            presentation.requestState,
            selectedDiffItemIds = presentation.selectedDiffItemIds,
        )
        presentation.result.patch?.let(stateService.workbench::markDraftPatchPreview)
        markOptionalFeedback(presentation.feedbackLevel, presentation.statusMessage)
        requestBrowserSync()
    }

    fun presentDiffReviewFailed(presentation: DiffReviewFailedResult) {
        stateService.asyncRequests.markDiffReviewRequestFailed(
            presentation.message,
            presentation.requestState,
            selectedDiffItemIds = presentation.selectedDiffItemIds,
        )
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel.toOperationFeedbackLevel(),
            presentation.message,
            preservePreviousStatusKind = presentation.preservePreviousStatusKind,
        )
        requestBrowserSync()
    }

    fun presentBeautificationCompleted(presentation: BeautificationCompletedResult) {
        stateService.asyncRequests.markGraphBeautificationResult(presentation.result, presentation.requestState)
        markOptionalFeedback(presentation.feedbackLevel, presentation.statusMessage)
        requestBrowserSync()
    }

    fun presentBeautificationFailed(presentation: BeautificationFailedResult) {
        stateService.asyncRequests.markGraphBeautificationRequestFailed(presentation.message, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel.toOperationFeedbackLevel(),
            presentation.message,
            preservePreviousStatusKind = presentation.preservePreviousStatusKind,
        )
        requestBrowserSync()
    }

    private fun markOptionalFeedback(
        level: ApplicationFeedbackLevel?,
        message: String?,
    ) {
        if (level != null && message != null) {
            stateService.workbench.markOperationFeedback(level.toOperationFeedbackLevel(), message, preservePreviousStatusKind = true)
        }
    }
}

private fun List<ApplicationRuntimeArtifactSummary>.toUiRuntimeArtifacts(): List<RuntimeArtifactSummary> {
    return map { summary ->
        RuntimeArtifactSummary(
            artifactId = summary.artifactId,
            artifactType = summary.artifactType,
            title = summary.title,
            description = summary.description,
        )
    }
}
