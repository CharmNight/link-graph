package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.result.DiffReviewCompletedResult
import com.charmnight.linkgraph.application.result.DiffReviewFailedResult
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.result.ReviewRequestScene
import com.charmnight.linkgraph.application.result.ReviewRequestStartedResult
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.llm.GraphDiffContext
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.review.ReviewEvidenceBundle
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil

internal class DiffReviewWorkflow(
    private val project: Project,
    private val snapshotProvider: EditorSnapshotProvider,
    private val eventSink: GraphEditorApplicationEventSink,
    private val graphDiffPatchService: GraphDiffPatchService,
    private val graphDiffer: GraphDiffer,
    private val settingsProvider: () -> LinkGraphSettingsState,
    private val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    private val logger: Logger,
    private val reviewEvidenceSupport: ReviewEvidenceWorkflowSupport = ReviewEvidenceWorkflowSupport(project),
) {
    fun requestDiffReviewAsync(
        question: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ) {
        val requestId = asyncRequestLifecycle.beginDiffReviewRequest()
        val settings = settingsProvider()
        val presentation = asyncRequestLifecycle.buildAsyncRequestLifecycleResult(
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
            ReviewRequestStartedResult(
                scene = ReviewRequestScene.DIFF_REVIEW,
                requestState = presentation.requestState,
                statusMessage = if (presentation.remoteRequested) {
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
                    DiffReviewFailedResult(
                        message = timedOutState.errorMessage ?: "差异分析超时",
                        requestState = timedOutState,
                    ),
                )
            },
        )
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val contextResult = runCatching {
                if (project.isDisposed) {
                    return@runCatching DiffReviewContextBuildResult.Cancelled
                }
                buildDiffReviewContext(selectedDiffItemIds)
            }.getOrElse { error ->
                logger.warn("构建差异分析上下文失败", error)
                DiffReviewContextBuildResult.MissingInputs("差异分析上下文构建失败：${error.message ?: error.javaClass.simpleName}")
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) {
                    return@invokeLater
                }
                when (contextResult) {
                    DiffReviewContextBuildResult.Cancelled -> Unit
                    is DiffReviewContextBuildResult.MissingInputs -> {
                        if (!asyncRequestLifecycle.completeDiffReviewRequest(requestId)) {
                            return@invokeLater
                        }
                        emitDiffReviewFailed(
                            DiffReviewFailedResult(
                                message = contextResult.message,
                                requestState = AsyncRequestState.failed(
                                    requestId = requestId,
                                    message = contextResult.message,
                                    scene = "差异分析",
                                ),
                                feedbackLevel = ApplicationFeedbackLevel.WARNING,
                            ),
                        )
                    }
                    is DiffReviewContextBuildResult.Ready -> {
                        runDiffReviewBackground(
                            requestId = requestId,
                            presentation = presentation,
                            contextResult = contextResult,
                            question = question,
                            settings = settings,
                            previewUpdater = previewUpdater,
                        )
                    }
                }
            }, ModalityState.defaultModalityState())
        }
    }

    private fun runDiffReviewBackground(
        requestId: Long,
        presentation: com.charmnight.linkgraph.application.request.AsyncRequestLifecycleResult,
        contextResult: DiffReviewContextBuildResult.Ready,
        question: String,
        settings: LinkGraphSettingsState,
        previewUpdater: ((String, Boolean) -> Unit)?,
    ) {
        asyncRequestLifecycle.runBackgroundTask(
            work = {
                graphDiffPatchService.review(
                    context = contextResult.context,
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
                        val resultWithContextWarnings = diffReviewResult.copy(
                            warnings = (contextResult.warnings + diffReviewResult.warnings).distinct(),
                        )
                        val requestState = asyncRequestLifecycle.buildSucceededRequestState(
                            presentation = presentation,
                            successMessage = if (resultWithContextWarnings.patch != null) {
                                "差异分析完成，已生成可预览的修订草稿。"
                            } else {
                                "差异分析完成。"
                            },
                            completedRemotely = resultWithContextWarnings.source == LlmResultSource.REMOTE,
                            warnings = resultWithContextWarnings.warnings,
                        )
                        asyncRequestLifecycle.logAsyncRequestEvent(logger, "succeeded", requestState)
                        val feedbackLevel = if (requestState.fallbackUsed) {
                            ApplicationFeedbackLevel.WARNING
                        } else {
                            ApplicationFeedbackLevel.SUCCESS
                        }
                        emitDiffReviewCompleted(
                            DiffReviewCompletedResult(
                                result = resultWithContextWarnings,
                                requestState = requestState,
                                feedbackLevel = feedbackLevel,
                                statusMessage = requestState.statusMessage
                                    ?: if (resultWithContextWarnings.patch != null) {
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
                            DiffReviewFailedResult(
                                message = message,
                                requestState = requestState,
                            ),
                        )
                    },
                )
            },
        )
    }

    private fun buildDiffReviewContext(selectedDiffItemIds: List<String>): DiffReviewContextBuildResult {
        val snapshot = snapshotProvider.snapshot()
        val factGraph = snapshot.semanticFactGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() }
        val designBaseline = snapshot.designBaselineGraph
        if (factGraph == null || designBaseline == null) {
            return DiffReviewContextBuildResult.MissingInputs("请先准备代码事实图和设计基线，再发起差异问答。")
        }
        val diff = snapshot.diff ?: graphDiffer.diff(factGraph, designBaseline).diff
        val evidenceResult = runCatching {
            val index = project.architectureIndexRuntime().index()
            val reviewService = reviewEvidenceSupport.reviewService(index)
            reviewEvidenceSupport.buildEvidence(diff, selectedDiffItemIds, reviewService)
        }
        val warning = evidenceResult.exceptionOrNull()?.let { error ->
            logger.warn("差异分析证据包构建失败，已降级为无架构证据。", error)
            "架构索引证据包构建失败，差异分析已降级为仅使用图差异上下文。"
        }
        val evidenceBuild = evidenceResult.getOrNull()
        return DiffReviewContextBuildResult.Ready(
            context = GraphDiffContext(
                factGraph = factGraph,
                designBaseline = designBaseline,
                diff = diff,
                selectedDiffItemIds = selectedDiffItemIds,
                reviewEvidenceBundle = evidenceBuild?.bundle?.let(::summarizeReviewEvidenceBundle).orEmpty(),
            ),
            warnings = evidenceBuild?.warnings.orEmpty() + listOfNotNull(warning),
        )
    }

    private fun summarizeReviewEvidenceBundle(bundle: ReviewEvidenceBundle): String {
        val changed = bundle.changedSymbols.joinToString("\n") { symbol ->
            "- changed ${symbol.qualifiedName} | file=${symbol.filePath ?: "unknown"} | lines=${symbol.startLine ?: "?"}-${symbol.endLine ?: "?"} | hunk=${symbol.hunk?.header ?: "file"} | baselineOnly=${symbol.baselineOnly} | unavailable=${symbol.unavailableReason ?: "none"}"
        }.ifBlank { "- 无" }
        val affected = buildString {
            append("Packages: ")
            append(bundle.blastRadius.affectedPackages.take(20).joinToString(", ").ifBlank { "无" })
            append("\nModules: ")
            append(bundle.blastRadius.affectedModules.take(20).joinToString(", ").ifBlank { "无" })
        }
        val upstream = bundle.blastRadius.upstream.take(20).joinToString("\n") { symbol ->
            "- upstream ${symbol.qualifiedName} | origin=${symbol.origin.name}"
        }.ifBlank { "- 无" }
        val downstream = bundle.blastRadius.downstream.take(20).joinToString("\n") { symbol ->
            "- downstream ${symbol.qualifiedName} | origin=${symbol.origin.name}"
        }.ifBlank { "- 无" }
        val evidence = bundle.evidenceRefs.take(20).joinToString("\n") { ref ->
            buildString {
                append("- evidence ")
                append(ref["qualifiedName"] ?: ref["kind"] ?: "ref")
                append(" | file=")
                append(ref["filePath"] ?: "unknown")
                append(" | decompiled=")
                append(ref["decompiled"])
                val snippet = ref["snippet"]?.toString()?.takeIf(String::isNotBlank)
                if (snippet != null) {
                    append("\n  snippet:\n")
                    append(snippet.lineSequence().take(12).joinToString("\n") { line -> "  $line" })
                }
            }
        }.ifBlank { "- 无" }
        val dynamic = (bundle.blastRadius.serviceLoaderLoads + bundle.blastRadius.proxyTargets).take(20).joinToString("\n") { relation ->
            "- ${relation.kind.name} | confidence=${relation.confidence.name} | relation=${relation.id}"
        }.ifBlank { "- 无" }
        return """
            Changed symbols:
            $changed
            Affected scope:
            $affected
            Upstream callers:
            $upstream
            Downstream callees:
            $downstream
            Dynamic relations:
            $dynamic
            Evidence refs:
            $evidence
        """.trimIndent()
    }

    private fun emit(event: GraphEditorApplicationEvent) = eventSink.emit(event)

    private fun emitReviewRequestStarted(presentation: ReviewRequestStartedResult) =
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

    private fun emitDiffReviewCompleted(presentation: DiffReviewCompletedResult) =
        emit(GraphEditorApplicationEvent.DiffReviewCompleted(presentation))

    private fun emitDiffReviewFailed(presentation: DiffReviewFailedResult) =
        emit(GraphEditorApplicationEvent.DiffReviewFailed(presentation))
}

private sealed interface DiffReviewContextBuildResult {
    data object Cancelled : DiffReviewContextBuildResult
    data class MissingInputs(val message: String) : DiffReviewContextBuildResult
    data class Ready(
        val context: GraphDiffContext,
        val warnings: List<String> = emptyList(),
    ) : DiffReviewContextBuildResult
}
