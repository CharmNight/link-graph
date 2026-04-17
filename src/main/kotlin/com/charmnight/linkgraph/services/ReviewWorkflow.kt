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
import com.charmnight.linkgraph.llm.artifact.AgentArtifactStoreService
import com.charmnight.linkgraph.llm.artifact.ArtifactStore
import com.charmnight.linkgraph.llm.capability.QaCapability
import com.charmnight.linkgraph.llm.capability.QaCapabilityInput
import com.charmnight.linkgraph.llm.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.llm.runtime.AgentRunResult
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.GraphEditorStateService.OperationFeedbackLevel
import com.charmnight.linkgraph.workbench.StepGranularity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 统一处理问答、差异分析和链路讲解流程。
 */
internal class ReviewWorkflow(
    /** 当前项目。 */
    private val project: Project,
    /** 项目级编辑器状态会话。 */
    private val session: ProjectEditorSession,
    /** 规划上下文工厂。 */
    private val planningContextFactory: PlanningContextFactory,
    /** 图问答服务。 */
    private val graphAuditPatchService: GraphAuditPatchService,
    /** 差异审核服务。 */
    private val graphDiffPatchService: GraphDiffPatchService,
    /** 链路讲解服务。 */
    private val graphBeautificationService: GraphBeautificationService,
    /** 图差异比较器。 */
    private val graphDiffer: GraphDiffer,
    /** 当前生效设置。 */
    private val settingsProvider: () -> LinkGraphSettingsState,
    /** 测试环境下的问答执行器覆盖。 */
    private val auditExecutorOverrideProvider: () -> ((GraphAuditContext, String) -> GraphPatchResult)?,
    /** 异步请求生命周期支持。 */
    private val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    /** 日志记录器。 */
    private val logger: Logger,
    /** 统一 runtime 协调器。 */
    private val agentRunCoordinator: AgentRunCoordinator = AgentRunCoordinator(),
    /** 跨 run 共享的 artifact store。 */
    private val artifactStoreProvider: () -> ArtifactStore = {
        project.getService(AgentArtifactStoreService::class.java).artifactStore
    },
    /** 问答 capability 工厂。 */
    private val qaCapabilityFactory: (QaCapability.LegacyAuditExecutor) -> QaCapability = { legacyExecutor ->
        QaCapability(legacyAuditExecutor = legacyExecutor)
    },
) {
    /**
     * 基于当前工作图发起同步问答。
     */
    fun requestAudit(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
        sourceLeadId: String? = null,
    ): GraphPatchResult {
        val snapshot = session.snapshot()
        val result = executeQaRuntime(
            input = buildQaCapabilityInput(
                snapshot = snapshot,
                question = question,
                selectedNodeIds = selectedNodeIds,
                sourceLeadId = sourceLeadId,
            ),
        )
        val requestState = asyncRequestLifecycle.withRuntimeMetadata(
            requestState = GraphEditorStateService.AsyncRequestState.succeeded(
                scene = "问答",
                statusMessage = if (result.output?.newCandidateChanges?.isNotEmpty() == true) {
                    "问答完成，已生成待确认变更。"
                } else {
                    "问答完成。"
                },
            ),
            runtimeState = result.finalState,
        )
        session.mutateBatch {
            apply {
                markRuntimeArtifactSummaries("qa", toRuntimeArtifactSummaries(result))
            }
            apply {
                markAuditResult(requireNotNull(result.output), requestState)
            }
        }
        return requireNotNull(result.output)
    }

    /**
     * 异步发起链路问答，并把结果和补丁预览回写到前端。
     */
    fun requestAuditAsync(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
        sourceLeadId: String? = null,
    ) {
        val requestId = asyncRequestLifecycle.beginAuditRequest()
        val snapshot = session.snapshot()
        val settings = settingsProvider()
        val presentation = asyncRequestLifecycle.buildAsyncRequestPresentation(
            requestId = requestId,
            sceneLabel = "问答",
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
                markRuntimeArtifactSummaries("qa", emptyList())
            }
            apply {
                markOperationFeedback(
                    OperationFeedbackLevel.INFO,
                    if (presentation.remoteRequested) {
                        if (presentation.streamingSupported) {
                            "已发起远程 LLM 问答请求，当前采用流式输出。"
                        } else {
                            "已发起远程 LLM 问答请求，当前采用完整返回。"
                        }
                    } else {
                        if (selectedNodeIds.isEmpty()) {
                            "正在对整个链路执行问答，请稍候。"
                        } else {
                            "正在对当前选中范围执行问答，请稍候。"
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
                            timedOutState.errorMessage ?: "问答超时",
                            timedOutState,
                        )
                    }
                    apply {
                        markOperationFeedback(
                            OperationFeedbackLevel.ERROR,
                            timedOutState.errorMessage ?: "问答超时",
                        )
                    }
                }
            },
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                executeQaRuntime(
                    input = buildQaCapabilityInput(
                        snapshot = snapshot,
                        question = question,
                        selectedNodeIds = selectedNodeIds,
                        sourceLeadId = sourceLeadId,
                        onPreview = previewUpdater,
                    ),
                )
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed || !asyncRequestLifecycle.completeAuditRequest(requestId)) {
                        return@invokeLater
                    }
                    result.fold(
                        onSuccess = { runtimeResult ->
                            val auditResult = runtimeResult.output
                            if (auditResult == null) {
                                val message = "问答失败：runtime 未返回结果。"
                                val requestState = asyncRequestLifecycle.withRuntimeMetadata(
                                    requestState = asyncRequestLifecycle.buildFailedRequestState(
                                        presentation = presentation,
                                        message = message,
                                    ),
                                    runtimeState = runtimeResult.finalState,
                                )
                                asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                                session.mutateBatch {
                                    apply {
                                        markRuntimeArtifactSummaries("qa", toRuntimeArtifactSummaries(runtimeResult))
                                    }
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
                                return@fold
                            }
                            val requestState = asyncRequestLifecycle.withRuntimeMetadata(
                                requestState = asyncRequestLifecycle.buildSucceededRequestState(
                                    presentation = presentation,
                                    successMessage = if (auditResult.newCandidateChanges.isNotEmpty()) {
                                        "问答完成，已生成待确认变更。"
                                    } else {
                                        "问答完成。"
                                    },
                                    completedRemotely = auditResult.source == LlmResultSource.REMOTE,
                                    warnings = auditResult.warnings,
                                ),
                                runtimeState = runtimeResult.finalState,
                            )
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "succeeded", requestState)
                            debugLazy(logger.isDebugEnabled, logger::debug) {
                                "问答 runtime 成功: runId=${runtimeResult.finalState.runId}, capabilityId=${runtimeResult.finalState.capabilityId}, " +
                                    "stepIndex=${runtimeResult.finalState.stepIndex}, artifactCount=${runtimeResult.artifactSummaries.size}, " +
                                    "filesRead=${runtimeResult.finalState.budget.filesRead}, stepsUsed=${runtimeResult.finalState.budget.usedSteps}, " +
                                    "failureReason=${runtimeResult.finalState.failureReason}"
                            }
                            val feedbackLevel = if (requestState.fallbackUsed) {
                                OperationFeedbackLevel.WARNING
                            } else {
                                OperationFeedbackLevel.SUCCESS
                            }
                            session.mutateBatch {
                                apply {
                                    markRuntimeArtifactSummaries("qa", toRuntimeArtifactSummaries(runtimeResult))
                                }
                                apply {
                                    markAuditResult(auditResult, requestState)
                                }
                                apply {
                                    markOperationFeedback(
                                        feedbackLevel,
                                        requestState.statusMessage
                                            ?: if (auditResult.newCandidateChanges.isNotEmpty()) {
                                                "问答完成，已生成待确认变更。"
                                            } else {
                                                "问答完成。"
                                            },
                                        preserveLastMessageType = true,
                                    )
                                }
                            }
                        },
                        onFailure = { throwable ->
                            logger.warn("异步问答失败", throwable)
                            val message = "问答失败：${throwable.message ?: throwable.javaClass.simpleName}"
                            val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                            session.mutateBatch {
                                apply {
                                    markRuntimeArtifactSummaries("qa", emptyList())
                                }
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
     * 统一执行 runtime 问答。
     * 这一层只做 runtime 装配，不承载问答业务判断，保证 ReviewWorkflow 仍然只是入口编排。
     */
    private fun executeQaRuntime(
        input: QaCapabilityInput,
    ): AgentRunResult<GraphPatchResult> {
        val capability = qaCapabilityFactory(
            QaCapability.LegacyAuditExecutor { qaInput, _, _ ->
                val overrideExecutor = auditExecutorOverrideProvider()
                if (overrideExecutor != null) {
                    overrideExecutor(qaInput.auditContext, qaInput.question)
                } else {
                    graphAuditPatchService.audit(
                        context = qaInput.auditContext,
                        question = qaInput.question,
                        settings = qaInput.settings,
                        session = qaInput.session,
                        sourceLeadId = qaInput.sourceLeadId,
                        onPreview = qaInput.onPreview,
                    )
                }
            },
        )
        val runtimeResult = agentRunCoordinator.run(
            capability = capability,
            input = input,
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = session::snapshot,
                artifactStore = artifactStoreProvider(),
            ),
        )
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "问答 runtime 执行结束: runId=${runtimeResult.finalState.runId}, capabilityId=${runtimeResult.finalState.capabilityId}, " +
                "phase=${runtimeResult.finalState.phase}, stepIndex=${runtimeResult.finalState.stepIndex}, " +
                "artifactCount=${runtimeResult.artifactSummaries.size}, filesRead=${runtimeResult.finalState.budget.filesRead}, " +
                "stepsUsed=${runtimeResult.finalState.budget.usedSteps}, failureReason=${runtimeResult.finalState.failureReason}"
        }
        asyncRequestLifecycle.logRuntimeTrace(logger, runtimeResult.finalState)
        return runtimeResult
    }

    /**
     * workflow 只接受 runtime 提供的最小摘要，不自己解析 artifact store。
     */
    private fun toRuntimeArtifactSummaries(
        result: AgentRunResult<*>,
    ): List<GraphEditorStateService.RuntimeArtifactSummary> {
        return result.artifactSummaries.map(GraphEditorStateService.RuntimeArtifactSummary::from)
    }

    /**
     * 基于当前快照构造问答 capability 输入。
     * 第一阶段仍复用 PlanningContextFactory 的问答图和源码证据构造，避免在 runtime 壳落地前提前拆散主链路。
     */
    private fun buildQaCapabilityInput(
        snapshot: GraphEditorStateService.Snapshot,
        question: String,
        selectedNodeIds: List<String>,
        sourceLeadId: String?,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): QaCapabilityInput {
        val auditGraphs = planningContextFactory.buildAuditGraphs(
            snapshot = snapshot,
            selectedNodeIds = selectedNodeIds,
            collectSourceEvidence = false,
        )
        return QaCapabilityInput(
            question = question,
            auditContext = GraphAuditContext(
                factGraph = auditGraphs.factGraph,
                draftGraph = auditGraphs.draftGraph,
                selectedNodeIds = selectedNodeIds,
            ),
            settings = settingsProvider(),
            session = snapshot.auditResult?.auditSession,
            sourceLeadId = sourceLeadId,
            onPreview = onPreview,
        )
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
