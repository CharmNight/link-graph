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

/**
 * 代码生成流程状态展示器。
 *
 * 把生成计划、计划讨论、代码草稿、流式预览等各类结果，统一写入工作台与异步请求状态，
 * 并触发前端浏览器同步刷新，使 UI 与底层生成流程保持一致。
 */
class GenerationStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    /** 把生成计划结果写入工作台，记录运行时产物、异步请求状态及操作反馈。 */
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

    /** 更新草稿校验状态与代码阶段准入决策，供 UI 决定后续流程是否可执行。 */
    fun presentDraftAndCodeEligibility(
        draftValidationState: com.charmnight.linkgraph.workbench.DraftValidationState?,
        codeEligibilityDecision: com.charmnight.linkgraph.workbench.StageEligibilityDecision?,
    ) {
        stateService.workbench.markDraftValidationState(draftValidationState)
        stateService.workbench.markCodeEligibilityDecision(codeEligibilityDecision)
        requestBrowserSync()
    }

    /** 标记一个生成请求开始执行：按场景启动对应异步请求并清空旧运行时产物。 */
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

    /** 把流式增量文本推送到对应场景的异步请求预览中，并在适当时机标记结构化结果正在收尾。 */
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

    /** 标记生成计划请求失败：写入失败请求状态、运行时产物以及操作反馈。 */
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

    /** 写入生成计划讨论结果，更新异步请求状态并展示对应的操作反馈。 */
    fun presentGenerationPlanDiscussion(presentation: GenerationDiscussionResult) {
        stateService.asyncRequests.markGenerationPlanDiscussion(presentation.result, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel,
            presentation.statusMessage,
            preservePreviousStatusKind = true,
        )
        requestBrowserSync()
    }

    /** 标记生成计划讨论请求失败：写入失败请求状态并展示操作反馈。 */
    fun presentGenerationPlanDiscussionFailure(presentation: GenerationRequestFailureResult) {
        stateService.asyncRequests.markGenerationPlanDiscussionRequestFailed(presentation.message, presentation.requestState)
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel,
            presentation.message,
            preservePreviousStatusKind = presentation.preservePreviousStatusKind,
        )
        requestBrowserSync()
    }

    /** 把已写入磁盘的代码草稿报告反映到工作台，并在存在反馈消息时一并进行提示。 */
    fun presentCodeDraftWriteReport(presentation: CodeDraftWriteResult) {
        stateService.workbench.markGeneratedCodeDraftWriteReport(presentation.report)
        val level = presentation.feedbackLevel
        val message = presentation.statusMessage
        if (level != null && message != null) {
            stateService.workbench.markOperationFeedback(level, message)
        }
        requestBrowserSync()
    }

    /** 写入一条生成流程反馈消息，可按需保留上一次的状态类型。 */
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

    /** 把合并写盘结果反映到工作台，并附上对应的反馈级别与消息。 */
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

    /** 把代码草稿生成结果写入工作台，包含草稿内容、警告、来源、预览信息以及运行时产物。 */
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

    /** 标记代码草稿请求失败：写入失败请求状态、运行时产物以及操作反馈。 */
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

/** 把应用层的运行时产物摘要转换为 UI 层使用的展示模型。 */
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
