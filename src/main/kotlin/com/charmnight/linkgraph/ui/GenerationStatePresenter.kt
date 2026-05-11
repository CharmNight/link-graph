package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.application.port.CodeDraftWritePresentation
import com.charmnight.linkgraph.application.port.GeneratedCodeDraftsPresentation
import com.charmnight.linkgraph.application.port.GenerationDiscussionPresentation
import com.charmnight.linkgraph.application.port.GenerationPlanPresentation
import com.charmnight.linkgraph.application.port.GenerationRequestFailurePresentation
import com.charmnight.linkgraph.application.port.GenerationRequestScene
import com.charmnight.linkgraph.application.port.GenerationRequestStartedPresentation
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport

class GenerationStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    fun presentGenerationPlan(presentation: GenerationPlanPresentation) {
        stateService.workbench.markRuntimeArtifactSummaries("plan", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markGenerationPlan(presentation.plan, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel.toOperationFeedbackLevel(),
            presentation.feedbackMessage,
            preserveLastMessageType = true,
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

    fun presentRequestStarted(presentation: GenerationRequestStartedPresentation) {
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
            OperationFeedbackLevel.INFO,
            presentation.feedbackMessage,
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

    fun presentGenerationPlanRequestFailure(presentation: GenerationRequestFailurePresentation) {
        stateService.workbench.markRuntimeArtifactSummaries("plan", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markGenerationPlanRequestFailed(presentation.message, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel.toOperationFeedbackLevel(),
            presentation.message,
            preserveLastMessageType = presentation.preserveLastMessageType,
        )
        requestBrowserSync()
    }

    fun presentGenerationPlanDiscussion(presentation: GenerationDiscussionPresentation) {
        stateService.asyncRequests.markGenerationPlanDiscussion(presentation.result, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel.toOperationFeedbackLevel(),
            presentation.feedbackMessage,
            preserveLastMessageType = true,
        )
        requestBrowserSync()
    }

    fun presentGenerationPlanDiscussionFailure(presentation: GenerationRequestFailurePresentation) {
        stateService.asyncRequests.markGenerationPlanDiscussionRequestFailed(presentation.message, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel.toOperationFeedbackLevel(),
            presentation.message,
            preserveLastMessageType = presentation.preserveLastMessageType,
        )
        requestBrowserSync()
    }

    fun presentCodeDraftWriteReport(presentation: CodeDraftWritePresentation) {
        stateService.workbench.markGeneratedCodeDraftWriteReport(presentation.report)
        val level = presentation.feedbackLevel
        val message = presentation.feedbackMessage
        if (level != null && message != null) {
            stateService.workbench.markOperationFeedback(level.toOperationFeedbackLevel(), message)
        }
        requestBrowserSync()
    }

    fun presentFeedback(
        level: ApplicationFeedbackLevel,
        message: String,
        preserveLastMessageType: Boolean,
    ) {
        stateService.workbench.markOperationFeedback(
            level.toOperationFeedbackLevel(),
            message,
            preserveLastMessageType = preserveLastMessageType,
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
            level.toOperationFeedbackLevel(),
            message,
            preserveLastMessageType = true,
        )
        requestBrowserSync()
    }

    fun presentGeneratedCodeDrafts(presentation: GeneratedCodeDraftsPresentation) {
        stateService.workbench.markRuntimeArtifactSummaries("codegen", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markGeneratedCodeDrafts(
            drafts = presentation.drafts,
            warnings = presentation.warnings,
            source = presentation.source,
            promptPreview = presentation.promptPreview,
            requestState = presentation.requestState,
        )
        val level = presentation.feedbackLevel
        val message = presentation.feedbackMessage
        if (level != null && message != null) {
            stateService.workbench.markOperationFeedback(level.toOperationFeedbackLevel(), message, preserveLastMessageType = true)
        }
        requestBrowserSync()
    }

    fun presentCodeDraftRequestFailure(presentation: GenerationRequestFailurePresentation) {
        stateService.workbench.markRuntimeArtifactSummaries("codegen", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markCodeDraftRequestFailed(presentation.message, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel.toOperationFeedbackLevel(),
            presentation.message,
            preserveLastMessageType = presentation.preserveLastMessageType,
        )
        requestBrowserSync()
    }
}

internal fun ApplicationFeedbackLevel.toOperationFeedbackLevel(): OperationFeedbackLevel {
    return when (this) {
        ApplicationFeedbackLevel.INFO -> OperationFeedbackLevel.INFO
        ApplicationFeedbackLevel.SUCCESS -> OperationFeedbackLevel.SUCCESS
        ApplicationFeedbackLevel.WARNING -> OperationFeedbackLevel.WARNING
        ApplicationFeedbackLevel.ERROR -> OperationFeedbackLevel.ERROR
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
