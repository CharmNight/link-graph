package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.llm.GraphAuditContext
import com.charmnight.linkgraph.llm.GraphAuditPatchService
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphBeautificationService
import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.llm.GraphDiffContext
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.GraphEditorStateService.OperationFeedbackLevel
import com.charmnight.linkgraph.workbench.StepGranularity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 统一处理审计、差异分析和链路讲解流程。
 */
internal class ReviewWorkflow(
    /** 当前项目。 */
    private val project: Project,
    /** 项目级编辑器状态会话。 */
    private val session: ProjectEditorSession,
    /** 规划上下文工厂。 */
    private val planningContextFactory: PlanningContextFactory,
    /** 图审计服务。 */
    private val graphAuditPatchService: GraphAuditPatchService,
    /** 差异审核服务。 */
    private val graphDiffPatchService: GraphDiffPatchService,
    /** 链路讲解服务。 */
    private val graphBeautificationService: GraphBeautificationService,
    /** 图差异比较器。 */
    private val graphDiffer: GraphDiffer,
    /** 当前生效设置。 */
    private val settingsProvider: () -> LinkGraphSettingsState,
    /** 测试环境下的审计执行器覆盖。 */
    private val auditExecutorOverrideProvider: () -> ((GraphAuditContext, String) -> GraphPatchResult)?,
    /** 异步请求生命周期支持。 */
    private val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    /** 日志记录器。 */
    private val logger: Logger,
) {
    /**
     * 基于当前工作图发起同步审计。
     */
    fun requestAudit(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
        sourceLeadId: String? = null,
    ): GraphPatchResult {
        val snapshot = session.snapshot()
        val auditGraphs = planningContextFactory.buildAuditGraphs(snapshot, selectedNodeIds)
        val result = graphAuditPatchService.audit(
            context = GraphAuditContext(
                factGraph = auditGraphs.factGraph,
                draftGraph = auditGraphs.draftGraph,
                selectedNodeIds = selectedNodeIds,
                sourceContext = auditGraphs.sourceContext,
                evidenceTrace = auditGraphs.evidenceTrace,
            ),
            question = question,
            settings = settingsProvider(),
            session = snapshot.auditResult?.auditSession,
            sourceLeadId = sourceLeadId,
        )
        session.mutateBatch {
            apply {
                markAuditResult(result)
            }
        }
        return result
    }

    /**
     * 异步发起链路审计，并把结果和补丁预览回写到前端。
     */
    fun requestAuditAsync(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
        sourceLeadId: String? = null,
    ) {
        val requestId = asyncRequestLifecycle.beginAuditRequest()
        val snapshot = session.snapshot()
        val auditGraphs = planningContextFactory.buildAuditGraphs(snapshot, selectedNodeIds)
        val settings = settingsProvider()
        val presentation = asyncRequestLifecycle.buildAsyncRequestPresentation(
            requestId = requestId,
            sceneLabel = "审计",
            settings = settings,
        )
        val previewUpdater = if (presentation.requestState.streaming) {
            asyncRequestLifecycle.createStreamingPreviewUpdater(
                requestId,
                GraphEditorStateService::updateAuditRequestPreview,
            )
        } else {
            null
        }
        session.mutateBatch {
            apply {
                beginAuditRequest(presentation.requestState)
            }
            apply {
                markOperationFeedback(
                    OperationFeedbackLevel.INFO,
                    if (presentation.remoteRequested) {
                        if (presentation.streamingSupported) {
                            "已发起远程 LLM 审计请求，当前采用流式输出。"
                        } else {
                            "已发起远程 LLM 审计请求，当前采用完整返回。"
                        }
                    } else {
                        if (selectedNodeIds.isEmpty()) {
                            "正在审计整个链路，请稍候。"
                        } else {
                            "正在审计当前选中范围，请稍候。"
                        }
                    },
                )
            }
        }
        asyncRequestLifecycle.logAsyncRequestEvent(logger, "started", presentation.requestState)
        asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = asyncRequestLifecycle::completeAuditRequest,
            onTimeout = {
                val timedOutState = asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                asyncRequestLifecycle.logAsyncRequestEvent(logger, "timedOut", timedOutState)
                session.mutateBatch {
                    apply {
                        markAuditRequestFailed(
                            timedOutState.errorMessage ?: "审计超时",
                            timedOutState,
                        )
                    }
                    apply {
                        markOperationFeedback(
                            OperationFeedbackLevel.ERROR,
                            timedOutState.errorMessage ?: "审计超时",
                        )
                    }
                }
            },
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                (auditExecutorOverrideProvider() ?: { context: GraphAuditContext, prompt: String ->
                    graphAuditPatchService.audit(
                        context = context,
                        question = prompt,
                        settings = settings,
                        session = snapshot.auditResult?.auditSession,
                        sourceLeadId = sourceLeadId,
                        onPreview = previewUpdater,
                    )
                })(
                    GraphAuditContext(
                        factGraph = auditGraphs.factGraph,
                        draftGraph = auditGraphs.draftGraph,
                        selectedNodeIds = selectedNodeIds,
                        sourceContext = auditGraphs.sourceContext,
                        evidenceTrace = auditGraphs.evidenceTrace,
                    ),
                    question,
                )
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed || !asyncRequestLifecycle.completeAuditRequest(requestId)) {
                        return@invokeLater
                    }
                    result.fold(
                        onSuccess = { auditResult ->
                            val requestState = asyncRequestLifecycle.buildSucceededRequestState(
                                presentation = presentation,
                                successMessage = if (auditResult.newCandidateChanges.isNotEmpty()) {
                                    "审计完成，已生成待确认变更。"
                                } else {
                                    "审计完成。"
                                },
                                completedRemotely = auditResult.source == LlmResultSource.REMOTE,
                                warnings = auditResult.warnings,
                            )
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "succeeded", requestState)
                            val feedbackLevel = if (requestState.fallbackUsed) {
                                OperationFeedbackLevel.WARNING
                            } else {
                                OperationFeedbackLevel.SUCCESS
                            }
                            session.mutateBatch {
                                apply {
                                    markAuditResult(auditResult, requestState)
                                }
                                apply {
                                    markOperationFeedback(
                                        feedbackLevel,
                                        requestState.statusMessage
                                            ?: if (auditResult.newCandidateChanges.isNotEmpty()) {
                                                "审计完成，已生成待确认变更。"
                                            } else {
                                                "审计完成。"
                                            },
                                        preserveLastMessageType = true,
                                    )
                                }
                            }
                        },
                        onFailure = { throwable ->
                            logger.warn("异步审计失败", throwable)
                            val message = "审计失败：${throwable.message ?: throwable.javaClass.simpleName}"
                            val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                            session.mutateBatch {
                                apply {
                                    markAuditRequestFailed(message, requestState)
                                }
                                apply {
                                    markOperationFeedback(
                                        OperationFeedbackLevel.ERROR,
                                        message,
                                        preserveLastMessageType = true,
                                    )
                                }
                            }
                        },
                    )
                },
                ModalityState.defaultModalityState(),
            )
        }
    }

    /**
     * 基于代码事实图和设计基线发起同步差异问答。
     */
    fun requestDiffReview(
        question: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ): GraphPatchResult? {
        session.mutate(syncBrowser = false) {
            beginDiffReviewRequest()
        }
        val context = buildDiffReviewContext(selectedDiffItemIds) ?: return null
        val result = graphDiffPatchService.review(
            context = context,
            question = question,
            settings = settingsProvider(),
        )
        session.mutateBatch {
            apply {
                markDiffReviewResult(result)
            }
            result.patch?.let { patch ->
                apply {
                    markDraftPatchPreview(patch)
                }
            }
        }
        return result
    }

    /**
     * 异步发起差异问答，并把修订草稿回写到前端。
     */
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
                GraphEditorStateService::updateDiffReviewRequestPreview,
            )
        } else {
            null
        }
        session.mutateBatch {
            apply {
                beginDiffReviewRequest(presentation.requestState)
            }
            apply {
                markOperationFeedback(
                    OperationFeedbackLevel.INFO,
                    if (presentation.remoteRequested) {
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
                )
            }
        }
        asyncRequestLifecycle.logAsyncRequestEvent(logger, "started", presentation.requestState)
        asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = asyncRequestLifecycle::completeDiffReviewRequest,
            onTimeout = {
                val timedOutState = asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                asyncRequestLifecycle.logAsyncRequestEvent(logger, "timedOut", timedOutState)
                session.mutateBatch {
                    apply {
                        markDiffReviewRequestFailed(
                            timedOutState.errorMessage ?: "差异分析超时",
                            timedOutState,
                        )
                    }
                    apply {
                        markOperationFeedback(
                            OperationFeedbackLevel.ERROR,
                            timedOutState.errorMessage ?: "差异分析超时",
                        )
                    }
                }
            },
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                graphDiffPatchService.review(
                    context = context,
                    question = question,
                    settings = settings,
                    onPreview = previewUpdater,
                )
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed || !asyncRequestLifecycle.completeDiffReviewRequest(requestId)) {
                        return@invokeLater
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
                                OperationFeedbackLevel.WARNING
                            } else {
                                OperationFeedbackLevel.SUCCESS
                            }
                            session.mutateBatch {
                                apply {
                                    markDiffReviewResult(diffReviewResult, requestState)
                                }
                                diffReviewResult.patch?.let { patch ->
                                    apply {
                                        markDraftPatchPreview(patch)
                                    }
                                }
                                apply {
                                    markOperationFeedback(
                                        feedbackLevel,
                                        requestState.statusMessage
                                            ?: if (diffReviewResult.patch != null) {
                                                "差异分析完成，已生成可预览的修订草稿。"
                                            } else {
                                                "差异分析完成。"
                                            },
                                        preserveLastMessageType = true,
                                    )
                                }
                            }
                        },
                        onFailure = { throwable ->
                            logger.warn("异步差异分析失败", throwable)
                            val message = "差异分析失败：${throwable.message ?: throwable.javaClass.simpleName}"
                            val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                            session.mutateBatch {
                                apply {
                                    markDiffReviewRequestFailed(message, requestState)
                                }
                                apply {
                                    markOperationFeedback(
                                        OperationFeedbackLevel.ERROR,
                                        message,
                                        preserveLastMessageType = true,
                                    )
                                }
                            }
                        },
                    )
                },
                ModalityState.defaultModalityState(),
            )
        }
    }

    /**
     * 生成当前链路图的讲解与润色说明。
     */
    fun requestGraphBeautification(
        goal: String = "",
        preferredStyle: String? = null,
        explanationFocus: String? = null,
        followUp: GraphBeautificationFollowUpContext? = null,
        granularity: StepGranularity = StepGranularity.BUSINESS,
    ): GraphBeautificationResult {
        val snapshot = session.snapshot()
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
        session.mutate {
            markGraphBeautificationResult(result)
        }
        return result
    }

    /**
     * 异步生成链路讲解，并把请求状态同步到前端。
     */
    fun requestGraphBeautificationAsync(
        goal: String = "",
        preferredStyle: String? = null,
        explanationFocus: String? = null,
        followUp: GraphBeautificationFollowUpContext? = null,
        granularity: StepGranularity = StepGranularity.BUSINESS,
    ) {
        val requestId = asyncRequestLifecycle.beginBeautificationRequest()
        val snapshot = session.snapshot()
        val settings = settingsProvider()
        val presentation = asyncRequestLifecycle.buildAsyncRequestPresentation(
            requestId = requestId,
            sceneLabel = "链路讲解",
            settings = settings,
        )
        val previewUpdater = if (presentation.requestState.streaming) {
            asyncRequestLifecycle.createStreamingPreviewUpdater(
                requestId,
                GraphEditorStateService::updateGraphBeautificationRequestPreview,
            )
        } else {
            null
        }
        session.mutateBatch {
            apply {
                beginGraphBeautificationRequest(presentation.requestState)
            }
            apply {
                markOperationFeedback(
                    OperationFeedbackLevel.INFO,
                    if (presentation.remoteRequested) {
                        if (presentation.streamingSupported) {
                            "已发起远程 LLM 链路讲解请求，当前采用流式输出。"
                        } else {
                            "已发起远程 LLM 链路讲解请求，当前采用完整返回。"
                        }
                    } else {
                        "正在生成链路讲解，请稍候。"
                    },
                )
            }
        }
        asyncRequestLifecycle.logAsyncRequestEvent(logger, "started", presentation.requestState)
        asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = asyncRequestLifecycle::completeBeautificationRequest,
            onTimeout = {
                val timedOutState = asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                asyncRequestLifecycle.logAsyncRequestEvent(logger, "timedOut", timedOutState)
                session.mutateBatch {
                    apply {
                        markGraphBeautificationRequestFailed(
                            timedOutState.errorMessage ?: "链路讲解超时",
                            timedOutState,
                        )
                    }
                    apply {
                        markOperationFeedback(
                            OperationFeedbackLevel.ERROR,
                            timedOutState.errorMessage ?: "链路讲解超时",
                        )
                    }
                }
            },
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
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
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed || !asyncRequestLifecycle.completeBeautificationRequest(requestId)) {
                        return@invokeLater
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
                                OperationFeedbackLevel.WARNING
                            } else {
                                OperationFeedbackLevel.SUCCESS
                            }
                            session.mutateBatch {
                                apply {
                                    markGraphBeautificationResult(beautification, requestState)
                                }
                                apply {
                                    markOperationFeedback(
                                        feedbackLevel,
                                        requestState.statusMessage ?: "链路讲解完成，已更新步骤列表",
                                        preserveLastMessageType = true,
                                    )
                                }
                            }
                        },
                        onFailure = { throwable ->
                            logger.warn("异步生成链路讲解失败", throwable)
                            val message = "生成链路讲解失败：${throwable.message ?: throwable.javaClass.simpleName}"
                            val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                            session.mutateBatch {
                                apply {
                                    markGraphBeautificationRequestFailed(message, requestState)
                                }
                                apply {
                                    markOperationFeedback(
                                        OperationFeedbackLevel.ERROR,
                                        message,
                                        preserveLastMessageType = true,
                                    )
                                }
                            }
                        },
                    )
                },
                ModalityState.defaultModalityState(),
            )
        }
    }

    /**
     * 组装差异审核上下文；若前置条件不满足则直接回写用户提示。
     */
    private fun buildDiffReviewContext(selectedDiffItemIds: List<String>): GraphDiffContext? {
        val snapshot = session.snapshot()
        val factGraph = snapshot.referenceFactGraph
        val designBaseline = snapshot.designBaselineGraph
        if (factGraph == null || designBaseline == null) {
            session.mutateBatch {
                apply {
                    markDiffReviewRequestFailed("请先准备代码事实图和设计基线，再发起差异问答。")
                }
                apply {
                    markOperationFeedback(
                        OperationFeedbackLevel.WARNING,
                        "请先准备代码事实图和设计基线，再发起差异问答。",
                    )
                }
            }
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
}
