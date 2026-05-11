package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.BeautificationCompletedPresentation
import com.charmnight.linkgraph.application.port.BeautificationFailedPresentation
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.ReviewRequestScene
import com.charmnight.linkgraph.application.port.ReviewRequestStartedPresentation
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphBeautificationService
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.StepGranularity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

internal class GraphBeautificationReviewWorkflow(
    private val project: Project,
    private val snapshotProvider: EditorSnapshotProvider,
    private val eventSink: GraphEditorApplicationEventSink,
    private val planningContextFactory: PlanningContextFactory,
    private val graphBeautificationService: GraphBeautificationService,
    private val settingsProvider: () -> LinkGraphSettingsState,
    private val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    private val logger: Logger,
) {
    fun requestGraphBeautification(
        goal: String = "",
        preferredStyle: String? = null,
        explanationFocus: String? = null,
        followUp: GraphBeautificationFollowUpContext? = null,
        granularity: StepGranularity = StepGranularity.BUSINESS,
    ): GraphBeautificationResult {
        val snapshot = snapshotProvider.snapshot()
        val result = graphBeautificationService.beautify(
            context = planningContextFactory.buildGraphBeautificationContext(
                snapshot = snapshot,
                goal = goal,
                preferredStyle = preferredStyle,
                explanationFocus = explanationFocus,
                followUp = followUp,
                granularity = granularity,
            ),
            settings = settingsProvider(),
        )
        emitBeautificationCompleted(
            BeautificationCompletedPresentation(
                result = result,
                requestState = AsyncRequestState.succeeded(),
            ),
        )
        return result
    }

    fun requestGraphBeautificationAsync(
        goal: String = "",
        preferredStyle: String? = null,
        explanationFocus: String? = null,
        followUp: GraphBeautificationFollowUpContext? = null,
        granularity: StepGranularity = StepGranularity.BUSINESS,
    ) {
        val requestId = asyncRequestLifecycle.beginBeautificationRequest()
        val snapshot = snapshotProvider.snapshot()
        val settings = settingsProvider()
        val presentation = asyncRequestLifecycle.buildAsyncRequestPresentation(
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
            ReviewRequestStartedPresentation(
                scene = ReviewRequestScene.BEAUTIFICATION,
                requestState = presentation.requestState,
                feedbackMessage = if (presentation.remoteRequested) {
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
                    BeautificationFailedPresentation(
                        message = timedOutState.errorMessage ?: "链路讲解超时",
                        requestState = timedOutState,
                    ),
                )
            },
        )
        asyncRequestLifecycle.runBackgroundTask(
            work = {
                graphBeautificationService.beautify(
                    context = planningContextFactory.buildGraphBeautificationContext(
                        snapshot = snapshot,
                        goal = goal,
                        preferredStyle = preferredStyle,
                        explanationFocus = explanationFocus,
                        followUp = followUp,
                        granularity = granularity,
                    ),
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
                            BeautificationCompletedPresentation(
                                result = beautification,
                                requestState = requestState,
                                feedbackLevel = feedbackLevel,
                                feedbackMessage = requestState.statusMessage ?: "链路讲解完成，已更新步骤列表",
                            ),
                        )
                    },
                    onFailure = { throwable ->
                        logger.warn("异步生成链路讲解失败", throwable)
                        val message = "生成链路讲解失败：${throwable.message ?: throwable.javaClass.simpleName}"
                        val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                        asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                        emitBeautificationFailed(
                            BeautificationFailedPresentation(
                                message = message,
                                requestState = requestState,
                            ),
                        )
                    },
                )
            },
        )
    }

    private fun emit(event: GraphEditorApplicationEvent) = eventSink.emit(event)

    private fun emitReviewRequestStarted(presentation: ReviewRequestStartedPresentation) =
        emit(GraphEditorApplicationEvent.ReviewRequestStarted(presentation))

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

    private fun emitBeautificationCompleted(presentation: BeautificationCompletedPresentation) =
        emit(GraphEditorApplicationEvent.BeautificationCompleted(presentation))

    private fun emitBeautificationFailed(presentation: BeautificationFailedPresentation) =
        emit(GraphEditorApplicationEvent.BeautificationFailed(presentation))
}
