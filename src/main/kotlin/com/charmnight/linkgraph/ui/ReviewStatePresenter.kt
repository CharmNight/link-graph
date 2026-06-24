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
import com.charmnight.linkgraph.workbench.AssistantIntent

/**
 * 审阅状态展示器。
 *
 * 负责把应用层各类审阅结果（质量检查、Diff 审阅、图谱美化等）转换并写入图谱编辑器的 UI 状态服务，
 * 同时通过浏览器同步回调通知前端刷新展示。
 */
class ReviewStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    /**
     * 处理质量检查（QA）完成结果：刷新运行时产物摘要、QA 请求状态、草稿校验状态、代码可处理性判定，
     * 并在存在反馈级别时附带操作反馈，最后触发浏览器同步。
     */
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

    /**
     * 处理审阅请求开始事件：根据场景（QA、Diff 审阅、图谱美化）调用对应异步请求起始方法，
     * 可选地清空指定场景下的运行时产物摘要，并写入 INFO 级别的状态消息后触发浏览器同步。
     */
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
            ReviewRequestScene.BEAUTIFICATION -> stateService.asyncRequests.beginGraphBeautificationRequest(
                presentation.requestState,
                assistantIntent = presentation.assistantIntent ?: AssistantIntent.EXPLAIN_CODE,
                assistantActionId = presentation.assistantActionId,
            )
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

    /**
     * 处理流式预览片段：依据场景把预览文本与"是否在定稿结构化结果"标记写入对应请求的实时预览状态，
     * 然后触发浏览器同步以便前端持续刷新预览。
     */
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

    /**
     * 处理质量检查失败结果：刷新运行时产物摘要、把对应请求标记为失败、写入操作反馈，并触发浏览器同步。
     */
    fun presentQaFailed(presentation: QaFailedResult) {
        stateService.workbench.markRuntimeArtifactSummaries("qa", presentation.runtimeArtifacts.toUiRuntimeArtifacts())
        stateService.asyncRequests.markQaRequestFailed(
            presentation.message,
            presentation.requestState,
            failedRequest = presentation.failedRequest,
        )
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel,
            presentation.message,
            preservePreviousStatusKind = presentation.preservePreviousStatusKind,
        )
        requestBrowserSync()
    }

    /**
     * 处理 Diff 审阅完成结果：更新 Diff 审阅请求状态，并在存在补丁时把补丁写入草稿预览，
     * 同时尝试附带可选反馈并触发浏览器同步。
     */
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

    /**
     * 处理 Diff 审阅失败结果：把对应请求标记为失败并写入操作反馈（可选择保留之前的状态类型），最后触发浏览器同步。
     */
    fun presentDiffReviewFailed(presentation: DiffReviewFailedResult) {
        stateService.asyncRequests.markDiffReviewRequestFailed(
            presentation.message,
            presentation.requestState,
            selectedDiffItemIds = presentation.selectedDiffItemIds,
        )
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel,
            presentation.message,
            preservePreviousStatusKind = presentation.preservePreviousStatusKind,
        )
        requestBrowserSync()
    }

    /**
     * 处理图谱美化完成结果：更新美化请求状态、携带助手意图与动作 ID，并附带可选反馈后触发浏览器同步。
     */
    fun presentBeautificationCompleted(presentation: BeautificationCompletedResult) {
        stateService.asyncRequests.markGraphBeautificationResult(
            presentation.result,
            presentation.requestState,
            assistantIntent = presentation.assistantIntent,
            assistantActionId = presentation.assistantActionId,
        )
        markOptionalFeedback(presentation.feedbackLevel, presentation.statusMessage)
        requestBrowserSync()
    }

    /**
     * 处理图谱美化失败结果：把对应美化请求标记为失败并写入操作反馈（可选择保留之前的状态类型），最后触发浏览器同步。
     */
    fun presentBeautificationFailed(presentation: BeautificationFailedResult) {
        stateService.asyncRequests.markGraphBeautificationRequestFailed(
            presentation.message,
            presentation.requestState,
            assistantIntent = presentation.assistantIntent,
            assistantActionId = presentation.assistantActionId,
        )
        stateService.workbench.markOperationFeedback(
            presentation.feedbackLevel,
            presentation.message,
            preservePreviousStatusKind = presentation.preservePreviousStatusKind,
        )
        requestBrowserSync()
    }

    /**
     * 当反馈级别与消息同时存在时，写入工作台的操作反馈，并保留之前的状态类型。
     * 用于那些"可选"反馈场景（结果中可能未携带反馈信息）。
     */
    private fun markOptionalFeedback(
        level: ApplicationFeedbackLevel?,
        message: String?,
    ) {
        if (level != null && message != null) {
            stateService.workbench.markOperationFeedback(level, message, preservePreviousStatusKind = true)
        }
    }
}

/**
 * 把应用层运行时产物摘要列表映射为 UI 层使用的运行时产物摘要列表，
 * 仅保留 ID、类型、标题、描述等展示所需字段。
 */
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
