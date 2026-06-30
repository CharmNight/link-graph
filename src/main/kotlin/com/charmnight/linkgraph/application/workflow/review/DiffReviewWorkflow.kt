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
import com.charmnight.linkgraph.application.runtime.SameThreadTaskRunner
import com.charmnight.linkgraph.application.runtime.TaskRunner
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.agent.model.GraphDiffContext
import com.charmnight.linkgraph.application.port.GraphDiffPatchPort
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.review.ReviewChangedSymbolEvidenceRef
import com.charmnight.linkgraph.review.ReviewEvidenceBundle
import com.charmnight.linkgraph.review.ReviewRelationEvidenceRef
import com.charmnight.linkgraph.review.ReviewSymbolEvidenceRef
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 差异复核工作流：在"代码事实图"与"设计基线图"差异基础上，调用 LLM 生成可预览的修订草稿。
 *
 * 内部串联了上下文构建、证据包收集、流式预览、超时与失败反馈等完整生命周期，
 * 通过事件总线向 UI 同步状态。
 */
internal class DiffReviewWorkflow(
    /** 当前 IntelliJ 项目句柄，用于访问架构索引、判断生命周期。 */
    private val project: Project,
    /** 提供当前编辑器快照（事实图、设计基线、差异、选中项等）的端口。 */
    private val snapshotProvider: EditorSnapshotProvider,
    /** 应用事件出口，向 UI 广播差异分析过程中的状态变化。 */
    private val eventSink: GraphEditorApplicationEventSink,
    /** 调用 LLM 进行差异分析并返回草稿补丁的服务。 */
    private val graphDiffPatchService: GraphDiffPatchPort,
    /** 在缺失差异时计算事实图与设计基线之间的差异。 */
    private val graphDiffer: GraphDiffer,
    /** 延迟获取插件设置（超时、远程开关等），便于运行时读取最新值。 */
    private val settingsProvider: () -> LinkGraphSettingsState,
    /** 复用通用的异步请求生命周期管理（编号、超时、完成回调等）。 */
    private val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    /** 共享日志器，输出警告与错误用于排查差异分析问题。 */
    private val logger: Logger,
    /** 平台无关任务调度入口。 */
    private val taskRunner: TaskRunner = SameThreadTaskRunner(),
    /** 协助从架构索引中构建差异复核所需的证据包。 */
    private val reviewEvidenceSupport: ReviewEvidenceWorkflowSupport = ReviewEvidenceWorkflowSupport(project),
) {
    /** 异步发起差异分析：编排请求编号、状态展示、上下文构建、后台执行与回调分发。 */
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
                selectedDiffItemIds = selectedDiffItemIds,
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
                        selectedDiffItemIds = selectedDiffItemIds,
                    ),
                )
            },
        )
        taskRunner.background {
            val contextResult = runCatching {
                if (project.isDisposed) {
                    return@runCatching DiffReviewContextBuildResult.Cancelled
                }
                buildDiffReviewContext(selectedDiffItemIds)
            }.getOrElse { error ->
                logger.warn("构建差异分析上下文失败", error)
                DiffReviewContextBuildResult.MissingInputs("差异分析上下文构建失败：${error.message ?: error.javaClass.simpleName}")
            }
            taskRunner.ui(TaskRunner.UiPolicy.ANY) {
                if (project.isDisposed) {
                    return@ui
                }
                when (contextResult) {
                    DiffReviewContextBuildResult.Cancelled -> Unit
                    is DiffReviewContextBuildResult.MissingInputs -> {
                        if (!asyncRequestLifecycle.completeDiffReviewRequest(requestId)) {
                            return@ui
                        }
                        emitDiffReviewFailed(
                            DiffReviewFailedResult(
                                message = contextResult.message,
                                requestState = AsyncRequestState.failed(
                                    requestId = requestId,
                                    message = contextResult.message,
                                    scene = "差异分析",
                                ),
                                selectedDiffItemIds = selectedDiffItemIds,
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
                            selectedDiffItemIds = selectedDiffItemIds,
                        )
                    }
                }
            }
        }
    }

    /** 在后台线程上实际调用 LLM 进行差异分析，并在结果返回后转换为应用事件。 */
    private fun runDiffReviewBackground(
        requestId: Long,
        presentation: com.charmnight.linkgraph.application.request.AsyncRequestLifecycleResult,
        contextResult: DiffReviewContextBuildResult.Ready,
        question: String,
        settings: LinkGraphSettingsState,
        previewUpdater: ((String, Boolean) -> Unit)?,
        selectedDiffItemIds: List<String>,
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
                                selectedDiffItemIds = selectedDiffItemIds,
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
                                selectedDiffItemIds = selectedDiffItemIds,
                            ),
                        )
                    },
                )
            },
        )
    }

    /** 从当前快照中收集事实图、设计基线、差异以及证据包，构造提交给 LLM 的差异分析上下文。 */
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

    /** 将证据包压缩为 LLM 可读的多行文本摘要，包含变更符号、影响范围、上下游调用、动态关系等。 */
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
                when (ref) {
                    is ReviewChangedSymbolEvidenceRef -> {
                        append(ref.qualifiedName ?: "ref")
                        append(" | file=")
                        append(ref.filePath ?: "unknown")
                        append(" | changeKind=")
                        append(ref.changeKind ?: "unknown")
                    }
                    is ReviewRelationEvidenceRef -> {
                        append(ref.kind)
                        append(" | file=")
                        append(ref.filePath ?: "unknown")
                        append(" | decompiled=")
                        append(ref.decompiled ?: false)
                    }
                    is ReviewSymbolEvidenceRef -> {
                        append(ref.qualifiedName ?: "ref")
                        append(" | file=")
                        append(ref.filePath ?: "unknown")
                        append(" | decompiled=")
                        append(ref.decompiled)
                    }
                }
                val snippet = ref.snippet?.snippet?.takeIf(String::isNotBlank)
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

    /** 统一的事件发送出口，封装对事件总线的访问。 */
    private fun emit(event: GraphEditorApplicationEvent) = eventSink.emit(event)

    /** 广播差异分析请求已开始的事件，携带初始状态和提示文案。 */
    private fun emitReviewRequestStarted(presentation: ReviewRequestStartedResult) =
        emit(GraphEditorApplicationEvent.ReviewRequestStarted(presentation))

    /** 在流式输出过程中持续推送增量预览文本，UI 据此实时更新。 */
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

    /** 差异分析成功完成时广播结果，包含生成的修订草稿和成功/警告反馈。 */
    private fun emitDiffReviewCompleted(presentation: DiffReviewCompletedResult) =
        emit(GraphEditorApplicationEvent.DiffReviewCompleted(presentation))

    /** 差异分析失败或超时时广播错误信息，触发 UI 错误展示。 */
    private fun emitDiffReviewFailed(presentation: DiffReviewFailedResult) =
        emit(GraphEditorApplicationEvent.DiffReviewFailed(presentation))
}

/** 差异分析上下文构建过程中的中间状态：被取消、缺少前置输入、或成功就绪。 */
private sealed interface DiffReviewContextBuildResult {
    /** 项目已被销毁或请求被显式取消。 */
    data object Cancelled : DiffReviewContextBuildResult
    /** 缺少必要前置条件（如事实图、设计基线），向用户给出原因。 */
    data class MissingInputs(val message: String) : DiffReviewContextBuildResult
    /** 上下文构建成功，已包含可直接交给 LLM 的差异分析输入和附带告警。 */
    data class Ready(
        val context: GraphDiffContext,
        val warnings: List<String> = emptyList(),
    ) : DiffReviewContextBuildResult
}
