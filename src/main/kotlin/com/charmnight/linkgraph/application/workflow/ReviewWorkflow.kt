package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.model.toRiskResolutionSnapshot
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.ToolGraphSnapshotProvider
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.investigation.adapter.InvestigationGraphPatchAdapter
import com.charmnight.linkgraph.investigation.application.InvestigationPipeline
import com.charmnight.linkgraph.investigation.domain.InvestigationRequest
import com.charmnight.linkgraph.investigation.domain.InvestigationTargetHint
import com.charmnight.linkgraph.application.workflow.review.DiffReviewWorkflow
import com.charmnight.linkgraph.application.workflow.review.GraphBeautificationReviewWorkflow
import com.charmnight.linkgraph.llm.GraphAuditContext
import com.charmnight.linkgraph.llm.GraphAuditPatchService
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphBeautificationService
import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.artifact.AgentArtifactStoreService
import com.charmnight.linkgraph.llm.artifact.ArtifactStore
import com.charmnight.linkgraph.llm.capability.QaCapability
import com.charmnight.linkgraph.llm.capability.QaCapabilityInput
import com.charmnight.linkgraph.llm.remoteConnectionOrNull
import com.charmnight.linkgraph.llm.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.llm.runtime.AgentRunResult
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.llm.usesRemoteProvider
import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.application.workflow.AuditResultNormalizer
import com.charmnight.linkgraph.application.diagnostics.GenerationDiagnostics
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.model.currentVisibleGraph
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.application.port.AuditCompletedPresentation
import com.charmnight.linkgraph.application.port.AuditFailedPresentation
import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.ReviewRequestScene
import com.charmnight.linkgraph.application.port.ReviewRequestStartedPresentation
import com.charmnight.linkgraph.application.usecase.ReviewUseCase
import com.charmnight.linkgraph.application.usecase.ReviewUseCaseResult
import com.charmnight.linkgraph.workbench.QaConversationService
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.QaModeClassifier
import com.charmnight.linkgraph.workbench.QaModeContext
import com.charmnight.linkgraph.workbench.QaRequestLifecycleService
import com.charmnight.linkgraph.workbench.ReplayableQaRequest
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.charmnight.linkgraph.workbench.RiskResolutionStatus
import com.charmnight.linkgraph.workbench.StepGranularity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 统一处理问答、差异分析和链路讲解流程。
 */
internal class ReviewWorkflow(
    /** 当前项目。 */
    private val project: Project,
    private val snapshotProvider: EditorSnapshotProvider,
    private val toolGraphSnapshotProvider: ToolGraphSnapshotProvider,
    private val eventSink: GraphEditorApplicationEventSink,
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
    /** 运行时链路追踪是否开启。 */
    private val runtimeQaTraceEnabled: Boolean =
        LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_TRACE"),
    /** 问答 capability 工厂。 */
    private val qaCapabilityFactory: (QaCapability.AuditExecutor) -> QaCapability = { auditExecutor ->
        QaCapability(auditExecutor = auditExecutor)
    },
    /** 可重放问答请求生命周期服务。 */
    private val qaRequestLifecycleService: QaRequestLifecycleService = QaRequestLifecycleService(),
    /** 问答会话归一化服务。 */
    private val auditConversationService: QaConversationService = QaConversationService(),
    /** 问答结果归一化器。 */
    private val auditResultNormalizer: AuditResultNormalizer = AuditResultNormalizer(auditConversationService),
    /** 风险决策与阶段准入服务。 */
    private val riskResolutionService: RiskResolutionService = RiskResolutionService(),
    /** 继续取证流水线工厂。 */
    private val investigationPipelineFactory: (Project) -> InvestigationPipeline = { targetProject ->
        InvestigationPipeline.default(targetProject)
    },
    /** 继续取证结果适配器。 */
    private val investigationGraphPatchAdapter: InvestigationGraphPatchAdapter = InvestigationGraphPatchAdapter(),
    /** AUTO 模式识别器。 */
    private val qaModeClassifier: QaModeClassifier = QaModeClassifier(),
) {
    private val reviewUseCase = ReviewUseCase(auditResultNormalizer::normalize)
    private val diffReviewWorkflow = DiffReviewWorkflow(
        project = project,
        snapshotProvider = snapshotProvider,
        eventSink = eventSink,
        graphDiffPatchService = graphDiffPatchService,
        graphDiffer = graphDiffer,
        settingsProvider = settingsProvider,
        asyncRequestLifecycle = asyncRequestLifecycle,
        logger = logger,
    )
    private val graphBeautificationWorkflow = GraphBeautificationReviewWorkflow(
        project = project,
        snapshotProvider = snapshotProvider,
        eventSink = eventSink,
        planningContextFactory = planningContextFactory,
        graphBeautificationService = graphBeautificationService,
        settingsProvider = settingsProvider,
        asyncRequestLifecycle = asyncRequestLifecycle,
        logger = logger,
    )

    private fun emit(event: GraphEditorApplicationEvent) = eventSink.emit(event)

    private fun emitAuditCompleted(presentation: AuditCompletedPresentation) =
        emit(GraphEditorApplicationEvent.AuditCompleted(presentation))

    private fun emitAuditFailed(presentation: AuditFailedPresentation) =
        emit(GraphEditorApplicationEvent.AuditFailed(presentation))

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

    /**
     * 基于当前工作图发起同步问答。
     */
    fun requestAudit(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
        sourceThreadId: String? = null,
        mode: QaMode = QaMode.AUTO,
    ): GraphPatchResult {
        val workflowSnapshot = snapshotProvider.snapshot()
        val request = qaRequestLifecycleService.buildReplayableRequest(
            auditResult = workflowSnapshot.auditResult,
            question = question,
            selectedNodeIds = selectedNodeIds,
            sourceThreadId = sourceThreadId,
            mode = mode,
        )
        val modeContext = modeContext(request)
        runInvestigationIfRequested(workflowSnapshot, modeContext)?.let { investigationResult ->
            return investigationResult
        }
        val result = executeQaRuntime(
            input = buildQaCapabilityInput(
                snapshot = workflowSnapshot,
                modeContext = modeContext,
            ),
        )
        val outputForState = result.output?.let { auditResultNormalizer.normalize(it, modeContext) }
        val requestState = asyncRequestLifecycle.withRuntimeMetadata(
            requestState = if (outputForState == null) {
                com.charmnight.linkgraph.application.model.AsyncRequestState.failed(
                    message = "问答失败：runtime 未返回结果。",
                    scene = "问答",
                    requestedMode = modeContext.requestedMode,
                    effectiveMode = modeContext.effectiveMode,
                )
            } else {
                com.charmnight.linkgraph.application.model.AsyncRequestState.succeeded(
                    scene = "问答",
                    statusMessage = if (outputForState.newCandidateChanges.isNotEmpty()) {
                        "问答完成，已生成待确认变更。"
                    } else {
                        "问答完成。"
                    },
                    requestedMode = modeContext.requestedMode,
                    effectiveMode = modeContext.effectiveMode,
                )
            },
            runtimeState = result.finalState,
        )
        val eligibility = outputForState?.let { output -> evaluateEligibility(workflowSnapshot.copy(auditResult = output)) }
        return when (val reviewResult = reviewUseCase.resolveAuditRuntimeResult(
            runtimeResult = result,
            modeContext = modeContext,
            requestState = requestState,
            runtimeArtifacts = toRuntimeArtifactSummaries(result),
            draftValidationState = eligibility?.first,
            codeEligibilityDecision = eligibility?.second,
        )) {
            is ReviewUseCaseResult.AuditCompleted -> {
                emitAuditCompleted(reviewResult.presentation)
                reviewResult.presentation.result
            }
            is ReviewUseCaseResult.AuditFailed -> {
                emitAuditFailed(reviewResult.presentation)
                reviewResult.fallbackResult
            }
        }
    }

    /**
     * 异步发起链路问答，并把结果和补丁预览回写到前端。
     */
    fun requestAuditAsync(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
        sourceThreadId: String? = null,
        mode: QaMode = QaMode.AUTO,
    ) {
        val workflowSnapshot = snapshotProvider.snapshot()
        val request = qaRequestLifecycleService.buildReplayableRequest(
            auditResult = workflowSnapshot.auditResult,
            question = question,
            selectedNodeIds = selectedNodeIds,
            sourceThreadId = sourceThreadId,
            mode = mode,
        )
        val modeContext = modeContext(request)
        if (modeContext.isDeterministicInvestigation) {
            val requestId = asyncRequestLifecycle.beginAuditRequest()
            val presentation = asyncRequestLifecycle.buildAsyncRequestPresentation(
                requestId = requestId,
                sceneLabel = "问答",
                settings = settingsProvider(),
                requestedMode = modeContext.requestedMode,
                effectiveMode = modeContext.effectiveMode,
            )
            emitReviewRequestStarted(
                ReviewRequestStartedPresentation(
                    scene = ReviewRequestScene.AUDIT,
                    requestState = presentation.requestState,
                    submittedRequest = modeContext.request,
                    feedbackMessage = "正在执行确定性继续取证，请稍候。",
                ),
            )
            asyncRequestLifecycle.runBackgroundTask(
                work = {
                    runInvestigationResult(workflowSnapshot, modeContext)
                },
                onCompleted = { result ->
                    if (project.isDisposed || !asyncRequestLifecycle.completeAuditRequest(requestId)) {
                        return@runBackgroundTask
                    }
                    result.fold(
                        onSuccess = { output ->
                            val requestState = asyncRequestLifecycle.buildSucceededRequestState(
                                presentation = presentation,
                                successMessage = "继续取证完成。",
                                completedRemotely = false,
                                warnings = output.warnings,
                            )
                            val (draftValidationState, codeDecision) = evaluateEligibility(workflowSnapshot.copy(auditResult = output))
                            emitAuditCompleted(
                                AuditCompletedPresentation(
                                    result = output,
                                    requestState = requestState.copy(
                                        requestedMode = modeContext.requestedMode,
                                        effectiveMode = modeContext.effectiveMode,
                                    ),
                                    completedRequest = modeContext.request,
                                    draftValidationState = draftValidationState,
                                    codeEligibilityDecision = codeDecision,
                                    runtimeArtifacts = emptyList(),
                                    feedbackLevel = ApplicationFeedbackLevel.SUCCESS,
                                    feedbackMessage = "继续取证完成。",
                                ),
                            )
                        },
                        onFailure = { throwable ->
                            val message = "继续取证失败：${throwable.message ?: throwable.javaClass.simpleName}"
                            val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                            emitAuditFailed(
                                AuditFailedPresentation(
                                    message = message,
                                    requestState = requestState,
                                    failedRequest = modeContext.request,
                                ),
                            )
                        },
                    )
                },
            )
            return
        }
        executeAuditAsync(
            snapshot = workflowSnapshot,
            modeContext = modeContext,
            feedbackMessage = buildAuditStartMessage(
                remoteRequested = effectiveRemoteRequested(),
                streamingSupported = effectiveStreamingSupported(),
                modeContext = modeContext,
            ),
        )
    }

    /**
     * 同步请求中如果携带风险线程标识，则优先执行确定性继续取证。
     */
    private fun runInvestigationIfRequested(
        snapshot: WorkflowEditorSnapshot,
        modeContext: QaModeContext,
    ): GraphPatchResult? {
        if (!modeContext.isDeterministicInvestigation) {
            return null
        }
        return runInvestigationResult(snapshot, modeContext)
    }

    /**
     * 执行继续取证流水线并转换为现有问答结果模型。
     */
    private fun runInvestigationResult(
        snapshot: WorkflowEditorSnapshot,
        modeContext: QaModeContext,
    ): GraphPatchResult {
        val request = modeContext.request
        val sourceThreadId = requireNotNull(modeContext.sourceThreadId)
        val sourceThread = request.baseSession?.investigationThreads
            ?.firstOrNull { thread -> thread.threadId == sourceThreadId }
        val investigationResult = investigationPipelineFactory(project).run(
            InvestigationRequest(
                threadId = sourceThreadId,
                question = request.question,
                title = sourceThread?.title.orEmpty(),
                summary = sourceThread?.summary.orEmpty(),
                evidenceGap = sourceThread?.evidenceGap.orEmpty(),
                recommendedQuestion = sourceThread?.recommendedQuestion.orEmpty(),
                targetNodeIds = request.selectedNodeIds.ifEmpty { sourceThread?.targetNodeIds.orEmpty() },
                targetHints = investigationTargetHints(snapshot, modeContext),
                evidenceClaims = sourceThread?.evidence.orEmpty().map(ResultEvidenceFinding::claim),
            ),
        )
        return auditResultNormalizer.normalize(
            result = investigationGraphPatchAdapter.toGraphPatchResult(
                request = request,
                turnResult = investigationResult,
            ),
            modeContext = modeContext,
        )
    }

    /**
     * 从当前图中提取风险线程关联节点的结构化符号提示。
     */
    private fun investigationTargetHints(
        snapshot: WorkflowEditorSnapshot,
        modeContext: QaModeContext,
    ): List<InvestigationTargetHint> {
        val request = modeContext.request
        val sourceThreadId = requireNotNull(modeContext.sourceThreadId)
        val sourceThread = request.baseSession?.investigationThreads
            ?.firstOrNull { thread -> thread.threadId == sourceThreadId }
        val targetNodeIds = (request.selectedNodeIds + sourceThread?.targetNodeIds.orEmpty()).distinct()
        if (targetNodeIds.isEmpty()) {
            return emptyList()
        }
        val nodesById = currentVisibleGraph(snapshot).nodes.associateBy { node -> node.id }
        return targetNodeIds.mapNotNull { nodeId ->
            val node = nodesById[nodeId] ?: return@mapNotNull null
            InvestigationTargetHint(
                nodeId = node.id,
                title = node.title,
                signature = node.signature,
            )
        }
    }

    fun retryLastAuditRequestAsync() {
        val snapshot = snapshotProvider.snapshot()
        val request = snapshot.qaRequestRecoveryState.lastFailedRequest
        if (request == null) {
            emitAuditFailed(
                AuditFailedPresentation(
                    message = "当前没有可直接重试的失败问答请求。",
                    requestState = com.charmnight.linkgraph.application.model.AsyncRequestState.failed(
                        message = "当前没有可直接重试的失败问答请求。",
                        scene = "问答",
                    ),
                    feedbackLevel = ApplicationFeedbackLevel.WARNING,
                ),
            )
            return
        }
        val modeContext = modeContext(request)
        executeAuditAsync(
            snapshot = snapshot,
            modeContext = modeContext,
            feedbackMessage = "正在重试上一次失败的问答请求，请稍候。",
        )
    }

    fun resolveInvestigationThread(
        threadId: String,
        status: RiskResolutionStatus,
        note: String = "",
    ) {
        val snapshot = snapshotProvider.snapshot()
        val auditResult = snapshot.auditResult ?: return
        val updatedResult = riskResolutionService.applyResolution(
            result = auditResult,
            threadId = threadId,
            status = status,
            note = note,
        ) ?: return
        val (draftValidationState, codeDecision) = evaluateEligibility(
            snapshot.copy(auditResult = updatedResult),
        )
        emitAuditCompleted(
            AuditCompletedPresentation(
                result = updatedResult,
                requestState = snapshot.auditRequestState,
                draftValidationState = draftValidationState,
                codeEligibilityDecision = codeDecision,
                runtimeArtifacts = emptyList(),
                feedbackLevel = ApplicationFeedbackLevel.SUCCESS,
                feedbackMessage = resolutionFeedbackMessage(status, codeDecision.allowed),
            ),
        )
    }

    /**
     * 统一执行 runtime 问答。
     * 这一层只做 runtime 装配，不承载问答业务判断，保证 ReviewWorkflow 仍然只是入口编排。
     */
    private fun executeQaRuntime(
        input: QaCapabilityInput,
    ): AgentRunResult<GraphPatchResult> {
        val capability = qaCapabilityFactory(
            QaCapability.AuditExecutor { qaInput, _, _ ->
                val overrideExecutor = auditExecutorOverrideProvider()
                if (overrideExecutor != null) {
                    overrideExecutor(qaInput.auditContext, qaInput.question)
                } else {
                    graphAuditPatchService.audit(
                        context = qaInput.auditContext,
                        question = qaInput.question,
                        settings = qaInput.settings,
                        session = qaInput.session,
                        sourceThreadId = qaInput.sourceThreadId,
                        requestedMode = qaInput.requestedMode,
                        effectiveMode = qaInput.effectiveMode,
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
                snapshotSupplier = toolGraphSnapshotProvider::snapshot,
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
    ): List<com.charmnight.linkgraph.application.port.ApplicationRuntimeArtifactSummary> {
        return result.artifactSummaries.map(com.charmnight.linkgraph.application.port.ApplicationRuntimeArtifactSummary::from)
    }

    private fun runtimeTrace(message: () -> String) {
        if (runtimeQaTraceEnabled) {
            logger.warn(message())
        }
    }

    /**
     * 基于当前快照构造问答 capability 输入。
     * 第一阶段仍复用 PlanningContextFactory 的问答图和源码证据构造，避免在 runtime 壳落地前提前拆散主链路。
     */
    private fun buildQaCapabilityInput(
        snapshot: WorkflowEditorSnapshot,
        modeContext: QaModeContext,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): QaCapabilityInput {
        val request = modeContext.request
        val auditGraphs = planningContextFactory.buildAuditGraphs(
            snapshot = snapshot,
            selectedNodeIds = modeContext.selectedNodeIds,
            collectSourceEvidence = false,
        )
        return QaCapabilityInput(
            question = modeContext.question,
            auditContext = GraphAuditContext(
                factGraph = auditGraphs.factGraph,
                editableGraph = auditGraphs.editableGraph,
                selectedNodeIds = modeContext.selectedNodeIds,
            ),
            settings = settingsProvider(),
            session = request.baseSession ?: snapshot.auditResult?.auditSession,
            sourceThreadId = modeContext.sourceThreadId,
            requestedMode = modeContext.requestedMode,
            effectiveMode = modeContext.effectiveMode,
            onPreview = onPreview,
        )
    }

    private fun executeAuditAsync(
        snapshot: WorkflowEditorSnapshot,
        modeContext: QaModeContext,
        feedbackMessage: String,
    ) {
        val request = modeContext.request
        val requestId = asyncRequestLifecycle.beginAuditRequest()
        val settings = settingsProvider()
        val presentation = asyncRequestLifecycle.buildAsyncRequestPresentation(
            requestId = requestId,
            sceneLabel = "问答",
            settings = settings,
            requestedMode = modeContext.requestedMode,
            effectiveMode = modeContext.effectiveMode,
        )
        val previewUpdater = if (presentation.requestState.streaming) {
            asyncRequestLifecycle.createStreamingPreviewUpdater(
                requestId,
                { _, previewText, finalizing ->
                    emitReviewStreamingPreview(
                        scene = ReviewRequestScene.AUDIT,
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
                scene = ReviewRequestScene.AUDIT,
                requestState = presentation.requestState,
                submittedRequest = request,
                clearRuntimeArtifactScene = "qa",
                feedbackMessage = feedbackMessage,
            ),
        )
        asyncRequestLifecycle.logAsyncRequestEvent(logger, "started", presentation.requestState)
        asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = asyncRequestLifecycle::completeAuditRequest,
            onTimeout = {
                val timedOutState = asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                asyncRequestLifecycle.logAsyncRequestEvent(logger, "timedOut", timedOutState)
                emitAuditFailed(
                    AuditFailedPresentation(
                        message = timedOutState.errorMessage ?: "问答超时",
                        requestState = timedOutState,
                        failedRequest = request,
                    ),
                )
            },
        )
        asyncRequestLifecycle.runBackgroundTask(
            work = {
                executeQaRuntime(
                    input = buildQaCapabilityInput(
                        snapshot = snapshot,
                        modeContext = modeContext,
                        onPreview = previewUpdater,
                    ),
                )
            },
            onCompleted = { result ->
                if (project.isDisposed || !asyncRequestLifecycle.completeAuditRequest(requestId)) {
                    return@runBackgroundTask
                }
                result.fold(
                    onSuccess = { runtimeResult ->
                        val normalizedAuditResult = runtimeResult.output?.let { output ->
                            auditResultNormalizer.normalize(output, modeContext)
                        }
                        val requestState = asyncRequestLifecycle.withRuntimeMetadata(
                            requestState = if (normalizedAuditResult == null) {
                                val failurePreview = reviewUseCase.resolveAuditRuntimeResult(
                                    runtimeResult = runtimeResult,
                                    modeContext = modeContext,
                                    requestState = asyncRequestLifecycle.buildFailedRequestState(
                                        presentation = presentation,
                                        message = "问答失败：runtime 未返回结果。",
                                    ),
                                    runtimeArtifacts = emptyList(),
                                    draftValidationState = null,
                                    codeEligibilityDecision = null,
                                ) as ReviewUseCaseResult.AuditFailed
                                asyncRequestLifecycle.buildFailedRequestState(
                                    presentation = presentation,
                                    message = failurePreview.presentation.message,
                                )
                            } else {
                                asyncRequestLifecycle.buildSucceededRequestState(
                                    presentation = presentation,
                                    successMessage = if (normalizedAuditResult.newCandidateChanges.isNotEmpty()) {
                                        "问答完成，已生成待确认变更。"
                                    } else {
                                        "问答完成。"
                                    },
                                    completedRemotely = normalizedAuditResult.source == LlmResultSource.REMOTE,
                                    warnings = normalizedAuditResult.warnings,
                                )
                            },
                            runtimeState = runtimeResult.finalState,
                        )
                        if (normalizedAuditResult != null) {
                            debugLazy(logger.isDebugEnabled, logger::debug) {
                                "问答 runtime 成功: runId=${runtimeResult.finalState.runId}, capabilityId=${runtimeResult.finalState.capabilityId}, " +
                                    "stepIndex=${runtimeResult.finalState.stepIndex}, artifactCount=${runtimeResult.artifactSummaries.size}, " +
                                    "filesRead=${runtimeResult.finalState.budget.filesRead}, stepsUsed=${runtimeResult.finalState.budget.usedSteps}, " +
                                    "failureReason=${runtimeResult.finalState.failureReason}"
                            }
                            runtimeTrace {
                                val candidates = normalizedAuditResult.newCandidateChanges.ifEmpty { normalizedAuditResult.candidateChanges }
                                val candidateSummary = candidates.take(3).joinToString(
                                    prefix = "[",
                                    postfix = if (candidates.size > 3) ", ...]" else "]",
                                ) { candidate ->
                                    GenerationDiagnostics.summarizeCandidateChange(candidate) +
                                        ", graphPatch=" + GenerationDiagnostics.summarizeGraphPatch(candidate.graphPatch)
                                }
                                "问答 runtime 结果: source=${normalizedAuditResult.source}, " +
                                    "candidateCount=${normalizedAuditResult.candidateChanges.size}, " +
                                    "newCandidateCount=${normalizedAuditResult.newCandidateChanges.size}, " +
                                    "candidates=$candidateSummary"
                            }
                        }
                        val eligibility = normalizedAuditResult?.let { output -> evaluateEligibility(snapshot.copy(auditResult = output)) }
                        val reviewResult = reviewUseCase.resolveAuditRuntimeResult(
                            runtimeResult = runtimeResult,
                            modeContext = modeContext,
                            requestState = requestState,
                            runtimeArtifacts = toRuntimeArtifactSummaries(runtimeResult),
                            draftValidationState = eligibility?.first,
                            codeEligibilityDecision = eligibility?.second,
                        )
                        val projectedRequestState = when (reviewResult) {
                            is ReviewUseCaseResult.AuditCompleted -> reviewResult.presentation.requestState
                            is ReviewUseCaseResult.AuditFailed -> reviewResult.presentation.requestState
                        }
                        asyncRequestLifecycle.logAsyncRequestEvent(
                            logger,
                            if (reviewResult is ReviewUseCaseResult.AuditCompleted) "succeeded" else "failed",
                            projectedRequestState,
                        )
                        when (reviewResult) {
                            is ReviewUseCaseResult.AuditCompleted -> emitAuditCompleted(
                                reviewResult.presentation.copy(
                                    feedbackLevel = if (requestState.fallbackUsed) {
                                        ApplicationFeedbackLevel.WARNING
                                    } else {
                                        ApplicationFeedbackLevel.SUCCESS
                                    },
                                    feedbackMessage = requestState.statusMessage
                                        ?: if (reviewResult.presentation.result.newCandidateChanges.isNotEmpty()) {
                                            "问答完成，已生成待确认变更。"
                                        } else {
                                            "问答完成。"
                                        },
                                ),
                            )
                            is ReviewUseCaseResult.AuditFailed -> emitAuditFailed(reviewResult.presentation)
                        }
                    },
                    onFailure = { throwable ->
                        logger.warn("异步问答失败", throwable)
                        val message = "问答失败：${throwable.message ?: throwable.javaClass.simpleName}"
                        val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                        asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                        emitAuditFailed(
                            AuditFailedPresentation(
                                message = message,
                                requestState = requestState,
                                failedRequest = request,
                                runtimeArtifacts = emptyList(),
                            ),
                        )
                    },
                )
            },
        )
    }

    private fun evaluateEligibility(
        snapshot: WorkflowEditorSnapshot,
    ): Pair<com.charmnight.linkgraph.workbench.DraftValidationState, com.charmnight.linkgraph.workbench.StageEligibilityDecision> {
        val riskSnapshot = snapshot.toApplicationSnapshot().toRiskResolutionSnapshot()
        return riskResolutionService.evaluateDraftValidation(riskSnapshot) to
            riskResolutionService.evaluateCodeEligibility(riskSnapshot)
    }

    private fun effectiveRemoteRequested(): Boolean = settingsProvider().usesRemoteProvider()

    private fun effectiveStreamingSupported(): Boolean {
        val settings = settingsProvider()
        return settings.remoteConnectionOrNull()?.preset?.capabilities?.supportsStreaming == true
    }

    private fun buildAuditStartMessage(
        remoteRequested: Boolean,
        streamingSupported: Boolean,
        modeContext: QaModeContext,
    ): String {
        val selectedNodeIds = modeContext.selectedNodeIds
        val modeSuffix = "实际模式：${modeContext.effectiveMode.name}。"
        if (remoteRequested) {
            return if (streamingSupported) {
                "已发起远程 LLM 问答请求，当前采用流式输出。$modeSuffix"
            } else {
                "已发起远程 LLM 问答请求，当前采用完整返回。$modeSuffix"
            }
        }
        return if (selectedNodeIds.isEmpty()) {
            "正在对整个链路执行问答，请稍候。$modeSuffix"
        } else {
            "正在对当前选中范围执行问答，请稍候。$modeSuffix"
        }
    }

    /**
     * 将可重放请求分类为本轮唯一的模式上下文。
     */
    private fun modeContext(request: ReplayableQaRequest): QaModeContext {
        return QaModeContext(
            request = request,
            effectiveMode = qaModeClassifier.classify(
                requestedMode = request.mode,
                question = request.question,
                sourceThreadId = request.sourceThreadId,
            ),
        )
    }

    private fun resolutionFeedbackMessage(
        status: RiskResolutionStatus,
        codeAllowed: Boolean,
    ): String {
        return when (status) {
            RiskResolutionStatus.DEFERRED -> "已暂挂该风险线程。现在可以继续生成实现计划，但代码阶段仍会保持拦截。"
            RiskResolutionStatus.ACCEPTED_RISK ->
                if (codeAllowed) {
                    "已接受该风险。当前已有确认草稿变更，计划与代码阶段都可以继续。"
                } else {
                    "已接受该风险。当前可以继续生成实现计划；代码阶段仍需至少一条已确认草稿变更。"
                }
            RiskResolutionStatus.EVIDENCE_EXHAUSTED -> "已标记该风险线程证据穷尽。现在可以继续生成实现计划，但代码阶段仍会保持拦截。"
            RiskResolutionStatus.DISMISSED -> "已排除该风险线程，后续阶段将按剩余风险与草稿状态重新判断。"
            RiskResolutionStatus.PROMOTED -> "该风险线程已提升为可执行变更，后续阶段将按草稿确认状态继续判断。"
            RiskResolutionStatus.UNRESOLVED -> "已恢复为未决风险线程，后续阶段将重新进入阻塞判断。"
        }
    }

    /**
     * 基于代码事实图和设计基线发起同步差异问答。
     */
    fun requestDiffReview(
        question: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ): GraphPatchResult? = diffReviewWorkflow.requestDiffReview(question, selectedDiffItemIds)

    /**
     * 异步发起差异问答，并把修订草稿回写到前端。
     */
    fun requestDiffReviewAsync(
        question: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ) = diffReviewWorkflow.requestDiffReviewAsync(question, selectedDiffItemIds)

    /**
     * 生成当前链路图的讲解与润色说明。
     */
    fun requestGraphBeautification(
        goal: String = "",
        preferredStyle: String? = null,
        explanationFocus: String? = null,
        followUp: GraphBeautificationFollowUpContext? = null,
        granularity: StepGranularity = StepGranularity.BUSINESS,
    ): GraphBeautificationResult = graphBeautificationWorkflow.requestGraphBeautification(
        goal = goal,
        preferredStyle = preferredStyle,
        explanationFocus = explanationFocus,
        followUp = followUp,
        granularity = granularity,
    )

    /**
     * 异步生成链路讲解，并把请求状态同步到前端。
     */
    fun requestGraphBeautificationAsync(
        goal: String = "",
        preferredStyle: String? = null,
        explanationFocus: String? = null,
        followUp: GraphBeautificationFollowUpContext? = null,
        granularity: StepGranularity = StepGranularity.BUSINESS,
    ) = graphBeautificationWorkflow.requestGraphBeautificationAsync(
        goal = goal,
        preferredStyle = preferredStyle,
        explanationFocus = explanationFocus,
        followUp = followUp,
        granularity = granularity,
    )
}
