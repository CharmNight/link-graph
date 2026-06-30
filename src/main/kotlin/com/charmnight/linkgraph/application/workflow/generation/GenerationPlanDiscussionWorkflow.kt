package com.charmnight.linkgraph.application.workflow.generation

import com.charmnight.linkgraph.application.model.PlanningInput
import com.charmnight.linkgraph.application.model.toRiskResolutionSnapshot
import com.charmnight.linkgraph.agent.model.GenerationContext
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.result.GenerationDiscussionResult
import com.charmnight.linkgraph.application.result.GenerationRequestFailureResult
import com.charmnight.linkgraph.application.result.GenerationRequestScene
import com.charmnight.linkgraph.application.result.GenerationRequestStartedResult
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult

/**
 * 实现建议追问工作流：在用户已经拿到一份实现建议后，针对其中的内容做进一步追问，
 * 通过远程 LLM（或本地回退）给出回答，并把结果以事件形式推送给 UI。
 *
 * 该工作流负责校验输入、刷新草稿与代码状态、构建提示词上下文、调度异步请求与超时、
 * 在成功/失败/超时等情况下分别派发对应事件。
 */
internal class GenerationPlanDiscussionWorkflow(
    private val dependencies: GenerationWorkflowDependencies,
) {
    /**
     * 异步发起一次实现建议追问。
     *
     * 先校验问题与当前是否存在实现建议快照；通过后启动后台任务、构建提示词上下文，
     * 并按是否流式输出实时回传预览文本，最终把成功/失败结果作为事件派发。
     *
     * @param question 用户输入的追问问题，去空白后不能为空。
     * @param focusItemId 当前聚焦的实现建议条目；为空表示针对整份建议提问。
     */
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
        val presentation = dependencies.asyncRequestLifecycle.buildAsyncRequestLifecycleResult(
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
                GenerationRequestStartedResult(
                    scene = GenerationRequestScene.PLAN_DISCUSSION,
                    requestState = presentation.requestState,
                    statusMessage = if (presentation.remoteRequested) {
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
                        GenerationRequestFailureResult(
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
                                GenerationDiscussionResult(
                                    result = discussion,
                                    requestState = completedRequestState,
                                    feedbackLevel = feedbackLevel,
                                    statusMessage = completedRequestState.statusMessage ?: "实现建议追问已更新。",
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
                                GenerationRequestFailureResult(
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

    /**
     * 同步执行一次实现建议追问，并把结果以事件形式派发。
     *
     * 与异步版本共用输入校验和上下文构建逻辑，但直接调用服务等待结果返回，
     * 适用于需要在调用方拿到返回值的场景；输入非法或缺少建议快照时返回 null。
     */
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
                GenerationDiscussionResult(
                    result = result,
                    requestState = requestState,
                    feedbackLevel = if (result.warnings.isNotEmpty()) ApplicationFeedbackLevel.WARNING else ApplicationFeedbackLevel.SUCCESS,
                    statusMessage = if (result.warnings.isNotEmpty()) {
                        result.warnings.first()
                    } else {
                        "实现建议追问已更新。"
                    },
                ),
            ),
        )
        return result
    }

    /**
     * 派发一次"追问被拒绝"事件，把 [message] 与 [detailMessage] 通过失败结果回传给 UI。
     * 通常用于输入校验不通过或当前缺少可追问的建议快照。
     */
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
                GenerationRequestFailureResult(
                    scene = "实现建议追问",
                    message = message,
                    requestState = requestState,
                    feedbackLevel = ApplicationFeedbackLevel.WARNING,
                ),
            ),
        )
    }

    /**
     * 把规划阶段组装的输入载荷映射为生成阶段使用的上下文对象，
     * 字段一一对应，方便下游服务统一消费。
     */
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
