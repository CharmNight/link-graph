package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.result.BeautificationCompletedResult
import com.charmnight.linkgraph.application.result.BeautificationFailedResult
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.result.ReviewRequestScene
import com.charmnight.linkgraph.application.result.ReviewRequestStartedResult
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.agent.model.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.application.port.GraphBeautificationPort
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.AssistantActionId
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.StepGranularity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 链路讲解（图美化）复核工作流。
 *
 * 负责协调一次链路讲解/美化请求的完整生命周期：构造提示词上下文、
 * 调度异步执行、流式推送预览、超时与失败处理，以及将各类结果通过事件总线派发给上层 UI。
 */
internal class GraphBeautificationReviewWorkflow(
    private val project: Project,
    private val snapshotProvider: EditorSnapshotProvider,
    private val eventSink: GraphEditorApplicationEventSink,
    private val planningContextFactory: PlanningContextFactory,
    private val graphBeautificationService: GraphBeautificationPort,
    private val settingsProvider: () -> LinkGraphSettingsState,
    private val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    private val logger: Logger,
) {
    /**
     * 异步发起一次链路讲解（图美化）请求。
     *
     * 流程：开启异步请求生命周期 -> 抓取当前编辑器快照 -> 构造提示词上下文 -> 启动流式预览
     * -> 派发"已开始"事件 -> 注册超时回调 -> 在后台执行实际的美化调用，并根据成功/失败结果
     * 分别派发对应事件。
     *
     * @param goal 用户输入的本次讲解目标描述，可空。
     * @param preferredStyle 用户偏好的讲解风格，可空表示使用默认。
     * @param explanationFocus 用户指定的讲解重点，可空。
     * @param focusNodeId 本次讲解聚焦的节点标识，可空表示不聚焦具体节点。
     * @param followUp 追问上下文，用于在已有讲解基础上继续展开，可空表示首次请求。
     * @param granularity 讲解步骤的粒度（业务/技术等）。
     * @param assistantIntent 助手意图（如讲解代码、解释流程等），用于事件分类。
     * @param assistantActionId 触发本次请求的助手动作标识，用于事件分类。
     */
    fun requestGraphBeautificationAsync(
        goal: String = "",
        preferredStyle: String? = null,
        explanationFocus: String? = null,
        focusNodeId: String? = null,
        followUp: GraphBeautificationFollowUpContext? = null,
        granularity: StepGranularity = StepGranularity.BUSINESS,
        assistantIntent: AssistantIntent = AssistantIntent.EXPLAIN_CODE,
        assistantActionId: AssistantActionId = AssistantActionId.EXPLAIN_FLOW,
    ) {
        val requestId = asyncRequestLifecycle.beginBeautificationRequest()
        val snapshot = snapshotProvider.snapshot()
        val settings = settingsProvider()
        val presentation = asyncRequestLifecycle.buildAsyncRequestLifecycleResult(
            requestId = requestId,
            sceneLabel = "链路讲解",
            settings = settings,
        )
        val previewUpdater = if (presentation.requestState.streaming) {
            asyncRequestLifecycle.createStreamingPreviewUpdater(
                requestId,
                { _, previewText, finalizing ->
                    emitReviewStreamingPreview(
                        scene = ReviewRequestScene.BEAUTIFICATION,
                        requestId = requestId,
                        previewText = previewText,
                        finalizingStructuredResult = finalizing,
                    )
                },
            )
        } else {
            null
        }
        emitReviewRequestStarted(
            ReviewRequestStartedResult(
                scene = ReviewRequestScene.BEAUTIFICATION,
                requestState = presentation.requestState,
                assistantIntent = assistantIntent,
                assistantActionId = assistantActionId,
                statusMessage = if (presentation.remoteRequested) {
                    if (presentation.streamingSupported) {
                        "已发起远程 LLM 链路讲解请求，当前采用流式输出。"
                    } else {
                        "已发起远程 LLM 链路讲解请求，当前采用完整返回。"
                    }
                } else {
                    "正在生成链路讲解，请稍候。"
                },
            ),
        )
        asyncRequestLifecycle.logAsyncRequestEvent(logger, "started", presentation.requestState)
        asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = asyncRequestLifecycle::completeBeautificationRequest,
            onTimeout = {
                val timedOutState = asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                asyncRequestLifecycle.logAsyncRequestEvent(logger, "timedOut", timedOutState)
                emitBeautificationFailed(
                    BeautificationFailedResult(
                        message = timedOutState.errorMessage ?: "链路讲解超时",
                        requestState = timedOutState,
                        assistantIntent = assistantIntent,
                        assistantActionId = assistantActionId,
                    ),
                )
            },
        )
        asyncRequestLifecycle.runBackgroundTask(
            work = {
                val context = planningContextFactory.buildGraphBeautificationContext(
                    snapshot = snapshot,
                    goal = goal,
                    preferredStyle = preferredStyle,
                    explanationFocus = explanationFocus,
                    focusNodeId = focusNodeId,
                    followUp = followUp,
                    granularity = granularity,
                    assistantActionId = assistantActionId,
                )
                if (LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_TRACE")) {
                    val requestedFocusNodeId = focusNodeId?.trim()?.takeIf(String::isNotBlank)
                    logger.warn(
                        "链路讲解请求上下文: focusNodeId=${requestedFocusNodeId ?: ""}, " +
                            "snapshotSelected=${snapshot.selectedNodeId ?: ""}, " +
                            "selectedMethodSignature=${snapshot.selectedMethodSignature ?: ""}, " +
                            "currentSceneId=${snapshot.currentSceneId}, " +
                            "anchorNodeId=${context.presentationContext.anchorNodeId ?: ""}, " +
                            "selectedNodeIds=${context.presentationContext.selectedNodeIds}, " +
                            "focusInProjection=${requestedFocusNodeId != null && context.presentationContext.graph.nodes.any { node -> node.id == requestedFocusNodeId }}, " +
                            "presentation=${LinkGraphRenderTrace.graphSummary(context.presentationContext.graph)}, " +
                            "full=${LinkGraphRenderTrace.graphSummary(context.presentationContext.fullGraph)}",
                    )
                }
                graphBeautificationService.beautify(
                    context = context,
                    settings = settings,
                    onPreview = previewUpdater,
                )
            },
            onCompleted = { result ->
                if (project.isDisposed || !asyncRequestLifecycle.completeBeautificationRequest(requestId)) {
                    return@runBackgroundTask
                }
                result.fold(
                    onSuccess = { beautification ->
                        val requestState = asyncRequestLifecycle.buildSucceededRequestState(
                            presentation = presentation,
                            successMessage = "链路讲解完成，已更新步骤列表",
                            completedRemotely = beautification.source == LlmResultSource.REMOTE,
                            warnings = beautification.warnings,
                        )
                        asyncRequestLifecycle.logAsyncRequestEvent(logger, "succeeded", requestState)
                        val feedbackLevel = if (requestState.fallbackUsed) {
                            ApplicationFeedbackLevel.WARNING
                        } else {
                            ApplicationFeedbackLevel.SUCCESS
                        }
                        emitBeautificationCompleted(
                            BeautificationCompletedResult(
                                result = beautification,
                                requestState = requestState,
                                assistantIntent = assistantIntent,
                                assistantActionId = assistantActionId,
                                feedbackLevel = feedbackLevel,
                                statusMessage = requestState.statusMessage ?: "链路讲解完成，已更新步骤列表",
                            ),
                        )
                    },
                    onFailure = { throwable ->
                        logger.warn("异步生成链路讲解失败", throwable)
                        val message = "生成链路讲解失败：${throwable.message ?: throwable.javaClass.simpleName}"
                        val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                        asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                        emitBeautificationFailed(
                            BeautificationFailedResult(
                                message = message,
                                requestState = requestState,
                                assistantIntent = assistantIntent,
                                assistantActionId = assistantActionId,
                            ),
                        )
                    },
                )
            },
        )
    }

    /** 统一通过事件总线派发一个应用层事件。 */
    private fun emit(event: GraphEditorApplicationEvent) = eventSink.emit(event)

    /** 派发"链路讲解请求已开始"事件，通知 UI 进入加载/进行中状态。 */
    private fun emitReviewRequestStarted(presentation: ReviewRequestStartedResult) =
        emit(GraphEditorApplicationEvent.ReviewRequestStarted(presentation))

    /**
     * 派发流式预览事件，将远端增量返回的讲解文本实时推送给 UI。
     *
     * @param scene 触发流式预览的场景（此处为图美化）。
     * @param requestId 关联的异步请求标识。
     * @param previewText 本轮流式返回的预览文本。
     * @param finalizingStructuredResult 是否正在将流式结果收敛为最终结构化输出。
     */
    private fun emitReviewStreamingPreview(
        scene: ReviewRequestScene,
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean,
    ) = emit(
        GraphEditorApplicationEvent.ReviewStreamingPreview(
            scene = scene,
            requestId = requestId,
            previewText = previewText,
            finalizingStructuredResult = finalizingStructuredResult,
        ),
    )

    /** 派发"链路讲解已完成"事件，将最终的讲解结果与步骤列表返回给 UI。 */
    private fun emitBeautificationCompleted(presentation: BeautificationCompletedResult) =
        emit(GraphEditorApplicationEvent.BeautificationCompleted(presentation))

    /** 派发"链路讲解失败"事件，将错误信息及请求状态返回给 UI。 */
    private fun emitBeautificationFailed(presentation: BeautificationFailedResult) =
        emit(GraphEditorApplicationEvent.BeautificationFailed(presentation))
}
