package com.charmnight.linkgraph.application.workflow.generation

import com.charmnight.linkgraph.application.model.PlanningInput
import com.charmnight.linkgraph.application.model.toRiskResolutionSnapshot
import com.charmnight.linkgraph.llm.GenerationContext
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.GenerationDiscussionPresentation
import com.charmnight.linkgraph.application.port.GenerationRequestFailurePresentation
import com.charmnight.linkgraph.application.port.GenerationRequestScene
import com.charmnight.linkgraph.application.port.GenerationRequestStartedPresentation
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult

internal class GenerationPlanDiscussionWorkflow(
    private val dependencies: GenerationWorkflowDependencies,
) {
    fun requestGenerationPlanDiscussionAsync(
        question: String,
        focusItemId: String? = null,
    ) {
        val normalizedQuestion = question.trim()
        if (normalizedQuestion.isEmpty()) {
            rejectGenerationPlanDiscussion(
                message = "请先输入你对实现建议的追问。",
                detailMessage = "实现建议追问不能为空。",
            )
            return
        }
        val snapshot = dependencies.snapshotProvider.snapshot()
        dependencies.refreshDraftAndCodeState(snapshot.toApplicationSnapshot().toRiskResolutionSnapshot())
        val generationPlan = snapshot.generationPlan
        if (generationPlan == null) {
            rejectGenerationPlanDiscussion(
                message = "请先生成实现建议，再继续追问。",
                detailMessage = "实现建议追问依赖当前建议快照，当前还没有可讨论的实现建议。",
            )
            return
        }
        val requestId = dependencies.asyncRequestLifecycle.beginGenerationPlanDiscussionRequest()
        val settings = dependencies.settingsProvider()
        val presentation = dependencies.asyncRequestLifecycle.buildAsyncRequestPresentation(
            requestId = requestId,
            sceneLabel = "实现建议追问",
            settings = settings,
        )
        val previewUpdater = if (presentation.requestState.streaming) {
            dependencies.asyncRequestLifecycle.createStreamingPreviewUpdater(
                requestId,
                { _, previewText, finalizing ->
                    dependencies.emitGenerationStreamingPreview(
                        scene = GenerationRequestScene.PLAN_DISCUSSION,
                        requestId = requestId,
                        previewText = previewText,
                        finalizingStructuredResult = finalizing,
                    )
                },
            )
        } else {
            null
        }
        dependencies.emit(
            GraphEditorApplicationEvent.GenerationRequestStarted(
                GenerationRequestStartedPresentation(
                    scene = GenerationRequestScene.PLAN_DISCUSSION,
                    requestState = presentation.requestState,
                    feedbackMessage = if (presentation.remoteRequested) {
                        if (presentation.streamingSupported) {
                            "已发起远程 LLM 实现建议追问请求，当前采用流式输出。"
                        } else {
                            "已发起远程 LLM 实现建议追问请求，当前采用完整返回。"
                        }
                    } else {
                        "正在追问当前实现建议，请稍候。"
                    },
                ),
            ),
        )
        dependencies.asyncRequestLifecycle.logAsyncRequestEvent(dependencies.logger, "started", presentation.requestState)
        dependencies.asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = dependencies.asyncRequestLifecycle::completeGenerationPlanDiscussionRequest,
            onTimeout = {
                val timedOutState = dependencies.asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                dependencies.asyncRequestLifecycle.logAsyncRequestEvent(dependencies.logger, "timedOut", timedOutState)
                dependencies.emit(
                    GraphEditorApplicationEvent.GenerationDiscussionFailed(
                        GenerationRequestFailurePresentation(
                            scene = "实现建议追问",
                            message = timedOutState.errorMessage ?: "实现建议追问超时",
                            requestState = timedOutState,
                        ),
                    ),
                )
            },
        )
        dependencies.asyncRequestLifecycle.runBackgroundTask(
            work = {
                dependencies.generationPlanDiscussionService.discuss(
                    context = planningPayloadToGenerationContext(
                        dependencies.planningContextFactory.computePlanningPayload(
                            snapshot = snapshot,
                            generationPlanOverride = generationPlan,
                        ),
                    ),
                    plan = generationPlan,
                    question = normalizedQuestion,
                    settings = settings,
                    session = snapshot.generationPlanDiscussionSession,
                    focusItemId = focusItemId,
                    onPreview = previewUpdater,
                )
            },
            onCompleted = { result ->
                if (dependencies.project.isDisposed || !dependencies.asyncRequestLifecycle.completeGenerationPlanDiscussionRequest(requestId)) {
                    return@runBackgroundTask
                }
                result.fold(
                    onSuccess = { discussion ->
                        val requestState = dependencies.asyncRequestLifecycle.buildSucceededRequestState(
                            presentation = presentation,
                            successMessage = "实现建议追问已更新。",
                            completedRemotely = discussion.source == LlmResultSource.REMOTE,
                            warnings = discussion.warnings,
                        )
                        val completedRequestState = requestState.copy(
                            promptPreviewAvailable = discussion.promptPreview.isNotBlank(),
                        )
                        dependencies.asyncRequestLifecycle.logAsyncRequestEvent(dependencies.logger, "succeeded", completedRequestState)
                        val feedbackLevel = if (requestState.fallbackUsed) {
                            ApplicationFeedbackLevel.WARNING
                        } else {
                            ApplicationFeedbackLevel.SUCCESS
                        }
                        dependencies.emit(
                            GraphEditorApplicationEvent.GenerationDiscussionReady(
                                GenerationDiscussionPresentation(
                                    result = discussion,
                                    requestState = completedRequestState,
                                    feedbackLevel = feedbackLevel,
                                    feedbackMessage = completedRequestState.statusMessage ?: "实现建议追问已更新。",
                                ),
                            ),
                        )
                    },
                    onFailure = { throwable ->
                        dependencies.logger.warn("异步追问实现建议失败", throwable)
                        val message = "实现建议追问失败：${throwable.message ?: throwable.javaClass.simpleName}"
                        val requestState = dependencies.asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                        dependencies.asyncRequestLifecycle.logAsyncRequestEvent(dependencies.logger, "failed", requestState)
                        dependencies.emit(
                            GraphEditorApplicationEvent.GenerationDiscussionFailed(
                                GenerationRequestFailurePresentation(
                                    scene = "实现建议追问",
                                    message = message,
                                    requestState = requestState,
                                ),
                            ),
                        )
                    },
                )
            },
        )
    }

    private fun runGenerationPlanDiscussion(
        question: String,
        focusItemId: String?,
    ): GenerationPlanDiscussionResult? {
        val normalizedQuestion = question.trim()
        if (normalizedQuestion.isEmpty()) {
            rejectGenerationPlanDiscussion(
                message = "请先输入你对实现建议的追问。",
                detailMessage = "实现建议追问不能为空。",
            )
            return null
        }
        val snapshot = dependencies.snapshotProvider.snapshot()
        dependencies.refreshDraftAndCodeState(snapshot.toApplicationSnapshot().toRiskResolutionSnapshot())
        val generationPlan = snapshot.generationPlan
        if (generationPlan == null) {
            rejectGenerationPlanDiscussion(
                message = "请先生成实现建议，再继续追问。",
                detailMessage = "实现建议追问依赖当前建议快照，当前还没有可讨论的实现建议。",
            )
            return null
        }
        val result = dependencies.generationPlanDiscussionService.discuss(
            context = planningPayloadToGenerationContext(
                dependencies.planningContextFactory.computePlanningPayload(
                    snapshot = snapshot,
                    generationPlanOverride = generationPlan,
                ),
            ),
            plan = generationPlan,
            question = normalizedQuestion,
            settings = dependencies.settingsProvider(),
            session = snapshot.generationPlanDiscussionSession,
            focusItemId = focusItemId,
        )
        val requestState = AsyncRequestState.succeeded(
            scene = "实现建议追问",
            statusMessage = "实现建议追问已更新。",
            promptPreviewAvailable = result.promptPreview.isNotBlank(),
        )
        dependencies.emit(
            GraphEditorApplicationEvent.GenerationDiscussionReady(
                GenerationDiscussionPresentation(
                    result = result,
                    requestState = requestState,
                    feedbackLevel = if (result.warnings.isNotEmpty()) ApplicationFeedbackLevel.WARNING else ApplicationFeedbackLevel.SUCCESS,
                    feedbackMessage = if (result.warnings.isNotEmpty()) {
                        result.warnings.first()
                    } else {
                        "实现建议追问已更新。"
                    },
                ),
            ),
        )
        return result
    }

    private fun rejectGenerationPlanDiscussion(
        message: String,
        detailMessage: String?,
    ) {
        val requestState = AsyncRequestState.failed(
            message = message,
            scene = "实现建议追问",
            detailMessage = detailMessage,
        )
        dependencies.emit(
            GraphEditorApplicationEvent.GenerationDiscussionFailed(
                GenerationRequestFailurePresentation(
                    scene = "实现建议追问",
                    message = message,
                    requestState = requestState,
                    feedbackLevel = ApplicationFeedbackLevel.WARNING,
                ),
            ),
        )
    }

    private fun planningPayloadToGenerationContext(
        payload: PlanningInput,
    ) = GenerationContext(
        graph = payload.planningGraph,
        mermaidIssues = payload.mermaidIssues,
        diff = payload.diff,
        syncPreviewItems = payload.previewItems,
        confirmedChanges = payload.confirmedChanges,
        sourceContext = payload.sourceContext,
    )
}
