package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.DiffReviewCompletedPresentation
import com.charmnight.linkgraph.application.port.DiffReviewFailedPresentation
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.ReviewRequestScene
import com.charmnight.linkgraph.application.port.ReviewRequestStartedPresentation
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.llm.GraphDiffContext
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

internal class DiffReviewWorkflow(
    private val project: Project,
    private val snapshotProvider: EditorSnapshotProvider,
    private val eventSink: GraphEditorApplicationEventSink,
    private val graphDiffPatchService: GraphDiffPatchService,
    private val graphDiffer: GraphDiffer,
    private val settingsProvider: () -> LinkGraphSettingsState,
    private val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    private val logger: Logger,
) {
    fun requestDiffReviewAsync(
        question: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ) {
        val requestId = asyncRequestLifecycle.beginDiffReviewRequest()
        val context = buildDiffReviewContext(selectedDiffItemIds) ?: return
        val settings = settingsProvider()
        val presentation = asyncRequestLifecycle.buildAsyncRequestPresentation(
            requestId = requestId,
            sceneLabel = "差异分析",
            settings = settings,
        )
        val previewUpdater = if (presentation.requestState.streaming) {
            asyncRequestLifecycle.createStreamingPreviewUpdater(
                requestId,
                { _, previewText, finalizing ->
                    emitReviewStreamingPreview(
                        scene = ReviewRequestScene.DIFF_REVIEW,
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
                scene = ReviewRequestScene.DIFF_REVIEW,
                requestState = presentation.requestState,
                feedbackMessage = if (presentation.remoteRequested) {
                    if (presentation.streamingSupported) {
                        "已发起远程 LLM 差异分析请求，当前采用流式输出。"
                    } else {
                        "已发起远程 LLM 差异分析请求，当前采用完整返回。"
                    }
                } else {
                    if (selectedDiffItemIds.isEmpty()) {
                        "正在分析差异，请稍候。"
                    } else {
                        "正在分析焦点差异，请稍候。"
                    }
                },
            ),
        )
        asyncRequestLifecycle.logAsyncRequestEvent(logger, "started", presentation.requestState)
        asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = asyncRequestLifecycle::completeDiffReviewRequest,
            onTimeout = {
                val timedOutState = asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                asyncRequestLifecycle.logAsyncRequestEvent(logger, "timedOut", timedOutState)
                emitDiffReviewFailed(
                    DiffReviewFailedPresentation(
                        message = timedOutState.errorMessage ?: "差异分析超时",
                        requestState = timedOutState,
                    ),
                )
            },
        )
        asyncRequestLifecycle.runBackgroundTask(
            work = {
                graphDiffPatchService.review(
                    context = context,
                    question = question,
                    settings = settings,
                    onPreview = previewUpdater,
                )
            },
            onCompleted = { result ->
                if (project.isDisposed || !asyncRequestLifecycle.completeDiffReviewRequest(requestId)) {
                    return@runBackgroundTask
                }
                result.fold(
                    onSuccess = { diffReviewResult ->
                        val requestState = asyncRequestLifecycle.buildSucceededRequestState(
                            presentation = presentation,
                            successMessage = if (diffReviewResult.patch != null) {
                                "差异分析完成，已生成可预览的修订草稿。"
                            } else {
                                "差异分析完成。"
                            },
                            completedRemotely = diffReviewResult.source == LlmResultSource.REMOTE,
                            warnings = diffReviewResult.warnings,
                        )
                        asyncRequestLifecycle.logAsyncRequestEvent(logger, "succeeded", requestState)
                        val feedbackLevel = if (requestState.fallbackUsed) {
                            ApplicationFeedbackLevel.WARNING
                        } else {
                            ApplicationFeedbackLevel.SUCCESS
                        }
                        emitDiffReviewCompleted(
                            DiffReviewCompletedPresentation(
                                result = diffReviewResult,
                                requestState = requestState,
                                feedbackLevel = feedbackLevel,
                                feedbackMessage = requestState.statusMessage
                                    ?: if (diffReviewResult.patch != null) {
                                        "差异分析完成，已生成可预览的修订草稿。"
                                    } else {
                                        "差异分析完成。"
                                    },
                            ),
                        )
                    },
                    onFailure = { throwable ->
                        logger.warn("异步差异分析失败", throwable)
                        val message = "差异分析失败：${throwable.message ?: throwable.javaClass.simpleName}"
                        val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                        asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                        emitDiffReviewFailed(
                            DiffReviewFailedPresentation(
                                message = message,
                                requestState = requestState,
                            ),
                        )
                    },
                )
            },
        )
    }

    private fun buildDiffReviewContext(selectedDiffItemIds: List<String>): GraphDiffContext? {
        val snapshot = snapshotProvider.snapshot()
        val factGraph = snapshot.semanticFactGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() }
        val designBaseline = snapshot.designBaselineGraph
        if (factGraph == null || designBaseline == null) {
            emitDiffReviewFailed(
                DiffReviewFailedPresentation(
                    message = "请先准备代码事实图和设计基线，再发起差异问答。",
                    requestState = AsyncRequestState.failed(
                        message = "请先准备代码事实图和设计基线，再发起差异问答。",
                        scene = "差异分析",
                    ),
                    feedbackLevel = ApplicationFeedbackLevel.WARNING,
                ),
            )
            return null
        }
        val diff = snapshot.diff ?: graphDiffer.diff(factGraph, designBaseline).diff
        return GraphDiffContext(
            factGraph = factGraph,
            designBaseline = designBaseline,
            diff = diff,
            selectedDiffItemIds = selectedDiffItemIds,
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

    private fun emitDiffReviewCompleted(presentation: DiffReviewCompletedPresentation) =
        emit(GraphEditorApplicationEvent.DiffReviewCompleted(presentation))

    private fun emitDiffReviewFailed(presentation: DiffReviewFailedPresentation) =
        emit(GraphEditorApplicationEvent.DiffReviewFailed(presentation))
}
