package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.result.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.application.result.CodeDraftWriteResult
import com.charmnight.linkgraph.application.result.GeneratedCodeDraftsResult
import com.charmnight.linkgraph.application.result.GenerationDiscussionResult
import com.charmnight.linkgraph.application.result.GenerationPlanResult
import com.charmnight.linkgraph.application.result.GenerationRequestFailureResult
import com.charmnight.linkgraph.application.result.GenerationRequestScene
import com.charmnight.linkgraph.application.result.GenerationRequestStartedResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport

class GenerationStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    fun presentGenerationPlan(presentation: GenerationPlanResult) {
        stateService.workbench.markRuntimeArtifactSummaries("plan", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markGenerationPlan(presentation.plan, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel,
            presentation.statusMessage,
            preservePreviousStatusKind = true,
        )
        requestBrowserSync()
    }

    fun presentDraftAndCodeEligibility(
        draftValidationState: com.charmnight.linkgraph.workbench.DraftValidationState?,
        codeEligibilityDecision: com.charmnight.linkgraph.workbench.StageEligibilityDecision?,
    ) {
        stateService.workbench.markDraftValidationState(draftValidationState)
        stateService.workbench.markCodeEligibilityDecision(codeEligibilityDecision)
        requestBrowserSync()
    }

    fun presentRequestStarted(presentation: GenerationRequestStartedResult) {
        when (presentation.scene) {
            GenerationRequestScene.PLAN -> stateService.asyncRequests.beginGenerationPlanRequest(presentation.requestState)
            GenerationRequestScene.PLAN_DISCUSSION -> {
                stateService.asyncRequests.beginGenerationPlanDiscussionRequest(presentation.requestState)
            }
            GenerationRequestScene.CODE_DRAFT -> stateService.asyncRequests.beginCodeDraftRequest(presentation.requestState)
        }
        presentation.clearRuntimeArtifactScene?.let { scene ->
            stateService.workbench.markRuntimeArtifactSummaries(scene, emptyList())
        }
        stateService.workbench.markOperationFeedback(
            ApplicationFeedbackLevel.INFO,
            presentation.statusMessage,
        )
        requestBrowserSync()
    }

    fun presentStreamingPreview(
        scene: GenerationRequestScene,
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean,
    ) {
        when (scene) {
            GenerationRequestScene.PLAN -> {
                stateService.asyncRequests.updateGenerationPlanRequestPreview(
                    requestId,
                    previewText,
                    finalizingStructuredResult,
                )
            }
            GenerationRequestScene.PLAN_DISCUSSION -> {
                stateService.asyncRequests.updateGenerationPlanDiscussionRequestPreview(
                    requestId,
                    previewText,
                    finalizingStructuredResult,
                )
            }
            GenerationRequestScene.CODE_DRAFT -> {
                stateService.asyncRequests.updateCodeDraftRequestPreview(
                    requestId,
                    previewText,
                    finalizingStructuredResult,
                )
            }
        }
        requestBrowserSync()
    }

    fun presentGenerationPlanRequestFailure(presentation: GenerationRequestFailureResult) {
        stateService.workbench.markRuntimeArtifactSummaries("plan", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markGenerationPlanRequestFailed(presentation.message, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel,
            presentation.message,
            preservePreviousStatusKind = presentation.preservePreviousStatusKind,
        )
        requestBrowserSync()
    }

    fun presentGenerationPlanDiscussion(presentation: GenerationDiscussionResult) {
        stateService.asyncRequests.markGenerationPlanDiscussion(presentation.result, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel,
            presentation.statusMessage,
            preservePreviousStatusKind = true,
        )
        requestBrowserSync()
    }

    fun presentGenerationPlanDiscussionFailure(presentation: GenerationRequestFailureResult) {
        stateService.asyncRequests.markGenerationPlanDiscussionRequestFailed(presentation.message, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel,
            presentation.message,
            preservePreviousStatusKind = presentation.preservePreviousStatusKind,
        )
        requestBrowserSync()
    }

    fun presentCodeDraftWriteReport(presentation: CodeDraftWriteResult) {
        stateService.workbench.markGeneratedCodeDraftWriteReport(presentation.report)
        val level = presentation.feedbackLevel
        val message = presentation.statusMessage
        if (level != null && message != null) {
            stateService.workbench.markOperationFeedback(level, message)
        }
        requestBrowserSync()
    }

    fun presentFeedback(
        level: ApplicationFeedbackLevel,
        message: String,
        preservePreviousStatusKind: Boolean,
    ) {
        stateService.workbench.markOperationFeedback(
            level,
            message,
            preservePreviousStatusKind = preservePreviousStatusKind,
        )
        requestBrowserSync()
    }

    fun presentMergeWriteReport(
        report: GeneratedCodeDraftWriteReport,
        level: ApplicationFeedbackLevel,
        message: String,
    ) {
        stateService.workbench.markGeneratedCodeDraftWriteReport(report)
        stateService.workbench.markOperationFeedback(
            level,
            message,
            preservePreviousStatusKind = true,
        )
        requestBrowserSync()
    }

    fun presentGeneratedCodeDrafts(presentation: GeneratedCodeDraftsResult) {
        stateService.workbench.markRuntimeArtifactSummaries("codegen", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markGeneratedCodeDrafts(
            drafts = presentation.drafts,
            warnings = presentation.warnings,
            source = presentation.source,
            promptPreview = presentation.promptPreview,
            requestState = presentation.requestState,
        )
        val level = presentation.feedbackLevel
        val message = presentation.statusMessage
        if (level != null && message != null) {
            stateService.workbench.markOperationFeedback(level, message, preservePreviousStatusKind = true)
        }
        requestBrowserSync()
    }

    fun presentCodeDraftRequestFailure(presentation: GenerationRequestFailureResult) {
        stateService.workbench.markRuntimeArtifactSummaries("codegen", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markCodeDraftRequestFailed(presentation.message, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel,
            presentation.message,
            preservePreviousStatusKind = presentation.preservePreviousStatusKind,
        )
        requestBrowserSync()
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
