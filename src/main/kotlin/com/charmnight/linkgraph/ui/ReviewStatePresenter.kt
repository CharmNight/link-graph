package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.application.port.QaCompletedPresentation
import com.charmnight.linkgraph.application.port.QaFailedPresentation
import com.charmnight.linkgraph.application.port.BeautificationCompletedPresentation
import com.charmnight.linkgraph.application.port.BeautificationFailedPresentation
import com.charmnight.linkgraph.application.port.DiffReviewCompletedPresentation
import com.charmnight.linkgraph.application.port.DiffReviewFailedPresentation
import com.charmnight.linkgraph.application.port.ReviewRequestScene
import com.charmnight.linkgraph.application.port.ReviewRequestStartedPresentation

class ReviewStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    fun presentQaCompleted(presentation: QaCompletedPresentation) {
        stateService.workbench.markRuntimeArtifactSummaries("qa", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markQaResult(
            presentation.result,
            presentation.requestState,
            completedRequest = presentation.completedRequest,
        )
        stateService.workbench.markDraftValidationState(presentation.draftValidationState)
        stateService.workbench.markCodeEligibilityDecision(presentation.codeEligibilityDecision)
        markOptionalFeedback(presentation.feedbackLevel, presentation.feedbackMessage)
        requestBrowserSync()
    }

    fun presentRequestStarted(presentation: ReviewRequestStartedPresentation) {
        when (presentation.scene) {
            ReviewRequestScene.QA -> {
                stateService.asyncRequests.beginQaRequest(
                    presentation.requestState,
                    submittedRequest = presentation.submittedRequest,
                )
            }
            ReviewRequestScene.DIFF_REVIEW -> stateService.asyncRequests.beginDiffReviewRequest(presentation.requestState)
            ReviewRequestScene.BEAUTIFICATION -> stateService.asyncRequests.beginGraphBeautificationRequest(presentation.requestState)
        }
        presentation.clearRuntimeArtifactScene?.let { scene ->
            stateService.workbench.markRuntimeArtifactSummaries(scene, emptyList())
        }
        stateService.workbench.markOperationFeedback(
            OperationFeedbackLevel.INFO,
            presentation.feedbackMessage,
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

    fun presentQaFailed(presentation: QaFailedPresentation) {
        stateService.workbench.markRuntimeArtifactSummaries("qa", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markQaRequestFailed(
            presentation.message,
            presentation.requestState,
            failedRequest = presentation.failedRequest,
        )
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel.toOperationFeedbackLevel(),
            presentation.message,
            preserveLastMessageType = presentation.preserveLastMessageType,
        )
        requestBrowserSync()
    }

    fun presentDiffReviewCompleted(presentation: DiffReviewCompletedPresentation) {
        stateService.asyncRequests.markDiffReviewResult(presentation.result, presentation.requestState)
        presentation.result.patch?.let(stateService.workbench::markDraftPatchPreview)
        markOptionalFeedback(presentation.feedbackLevel, presentation.feedbackMessage)
        requestBrowserSync()
    }

    fun presentDiffReviewFailed(presentation: DiffReviewFailedPresentation) {
        stateService.asyncRequests.markDiffReviewRequestFailed(presentation.message, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel.toOperationFeedbackLevel(),
            presentation.message,
            preserveLastMessageType = presentation.preserveLastMessageType,
        )
        requestBrowserSync()
    }

    fun presentBeautificationCompleted(presentation: BeautificationCompletedPresentation) {
        stateService.asyncRequests.markGraphBeautificationResult(presentation.result, presentation.requestState)
        markOptionalFeedback(presentation.feedbackLevel, presentation.feedbackMessage)
        requestBrowserSync()
    }

    fun presentBeautificationFailed(presentation: BeautificationFailedPresentation) {
        stateService.asyncRequests.markGraphBeautificationRequestFailed(presentation.message, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel.toOperationFeedbackLevel(),
            presentation.message,
            preserveLastMessageType = presentation.preserveLastMessageType,
        )
        requestBrowserSync()
    }

    private fun markOptionalFeedback(
        level: ApplicationFeedbackLevel?,
        message: String?,
    ) {
        if (level != null && message != null) {
            stateService.workbench.markOperationFeedback(level.toOperationFeedbackLevel(), message, preserveLastMessageType = true)
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
