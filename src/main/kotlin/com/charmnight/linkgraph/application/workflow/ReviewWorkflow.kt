package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.model.toRiskResolutionSnapshot
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.GraphEditRequestExecutor
import com.charmnight.linkgraph.application.port.ToolGraphSnapshotProvider
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.investigation.application.InvestigationGraphPatchAdapter
import com.charmnight.linkgraph.investigation.application.InvestigationPipeline
import com.charmnight.linkgraph.investigation.application.InvestigationRequest
import com.charmnight.linkgraph.investigation.application.InvestigationTargetHint
import com.charmnight.linkgraph.application.workflow.review.DiffReviewWorkflow
import com.charmnight.linkgraph.application.workflow.review.GraphBeautificationReviewWorkflow
import com.charmnight.linkgraph.llm.GraphQaContext
import com.charmnight.linkgraph.llm.GraphQaPatchService
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphBeautificationService
import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.ResultEvidenceFinding
import com.charmnight.linkgraph.llm.markRuntimeEvidenceTrusted
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
import com.charmnight.linkgraph.application.workflow.QaResultNormalizer
import com.charmnight.linkgraph.application.diagnostics.GenerationDiagnostics
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.model.currentVisibleGraph
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.application.result.QaCompletedResult
import com.charmnight.linkgraph.application.result.QaFailedResult
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.result.ReviewRequestScene
import com.charmnight.linkgraph.application.result.ReviewRequestStartedResult
import com.charmnight.linkgraph.application.usecase.ReviewUseCase
import com.charmnight.linkgraph.application.usecase.ReviewUseCaseResult
import com.charmnight.linkgraph.workbench.QaConversationService
import com.charmnight.linkgraph.workbench.AssistantIntent
import com.charmnight.linkgraph.workbench.AssistantActionId
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
    private val graphQaPatchService: GraphQaPatchService,
    /** 差异审核服务。 */
    private val graphDiffPatchService: GraphDiffPatchService,
    /** 链路讲解服务。 */
    private val graphBeautificationService: GraphBeautificationService,
    /** 图差异比较器。 */
    private val graphDiffer: GraphDiffer,
    /** 当前生效设置。 */
    private val settingsProvider: () -> LinkGraphSettingsState,
    /** 测试环境下的问答执行器覆盖。 */
    private val qaExecutorOverrideProvider: () -> ((GraphQaContext, String) -> GraphPatchResult)?,
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
    /** 受控图编辑入口，供 runtime tool 调用。 */
    private val graphEditRequestExecutor: GraphEditRequestExecutor? = null,
    /** 运行时链路追踪是否开启。 */
    private val runtimeQaTraceEnabled: Boolean =
        LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_TRACE"),
    /** 问答 capability 工厂。 */
    private val qaCapabilityFactory: (QaCapability.QaExecutor) -> QaCapability = { qaExecutor ->
        QaCapability(qaExecutor = qaExecutor)
    },
    /** 可重放问答请求生命周期服务。 */
    private val qaRequestLifecycleService: QaRequestLifecycleService = QaRequestLifecycleService(),
    /** 问答会话归一化服务。 */
    private val qaConversationService: QaConversationService = QaConversationService(),
    /** 问答结果归一化器。 */
    private val qaResultNormalizer: QaResultNormalizer = QaResultNormalizer(qaConversationService),
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
    private val reviewUseCase = ReviewUseCase(qaResultNormalizer::normalize)
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

    private fun emitQaCompleted(presentation: QaCompletedResult) =
        com.charmnight.linkgraph.application.workflow.review.emitQaCompleted(eventSink, presentation)

    private fun emitQaFailed(presentation: QaFailedResult) =
        com.charmnight.linkgraph.application.workflow.review.emitQaFailed(eventSink, presentation)

    private fun emitReviewRequestStarted(presentation: ReviewRequestStartedResult) =
        com.charmnight.linkgraph.application.workflow.review.emitReviewRequestStarted(eventSink, presentation)

    private fun emitReviewStreamingPreview(
        scene: ReviewRequestScene,
        requestId: Long,
        previewText: String,
        finalizingStructuredResult: Boolean,
    ) = com.charmnight.linkgraph.application.workflow.review.emitReviewStreamingPreview(
        eventSink = eventSink,
        scene = scene,
        requestId = requestId,
        previewText = previewText,
        finalizingStructuredResult = finalizingStructuredResult,
    )

    /**
     * 异步发起链路问答，并把结果和补丁预览回写到前端。
     */
    fun requestQaAsync(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
        sourceThreadId: String? = null,
        mode: QaMode = QaMode.AUTO,
    ) {
        val workflowSnapshot = snapshotProvider.snapshot()
        val request = qaRequestLifecycleService.buildReplayableRequest(
            qaResult = workflowSnapshot.qaResult,
            question = question,
            selectedNodeIds = selectedNodeIds,
            sourceThreadId = sourceThreadId,
            mode = mode,
        )
        val modeContext = modeContext(request)
        if (modeContext.isDeterministicInvestigation) {
            val requestId = asyncRequestLifecycle.beginQaRequest()
            val presentation = asyncRequestLifecycle.buildAsyncRequestLifecycleResult(
                requestId = requestId,
                sceneLabel = "问答",
                settings = settingsProvider(),
                requestedMode = modeContext.requestedMode,
                effectiveMode = modeContext.effectiveMode,
            )
            emitReviewRequestStarted(
                ReviewRequestStartedResult(
                    scene = ReviewRequestScene.QA,
                    requestState = presentation.requestState,
                    submittedRequest = modeContext.request,
                    statusMessage = "正在执行确定性继续取证，请稍候。",
                ),
            )
            asyncRequestLifecycle.runBackgroundTask(
                work = {
                    runInvestigationResult(workflowSnapshot, modeContext)
                },
                onCompleted = { result ->
                    if (project.isDisposed || !asyncRequestLifecycle.completeQaRequest(requestId)) {
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
                            val (draftValidationState, codeDecision) = evaluateEligibility(workflowSnapshot.copy(qaResult = output))
                            emitQaCompleted(
                                QaCompletedResult(
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
                                    statusMessage = "继续取证完成。",
                                ),
                            )
                        },
                        onFailure = { throwable ->
                            val message = "继续取证失败：${throwable.message ?: throwable.javaClass.simpleName}"
                            val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                            emitQaFailed(
                                QaFailedResult(
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
        executeQaAsync(
            snapshot = workflowSnapshot,
            modeContext = modeContext,
            statusMessage = buildQaStartMessage(
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
        return qaResultNormalizer.normalize(
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
    ): List<InvestigationTargetHint> =
        com.charmnight.linkgraph.application.workflow.review.investigationTargetHints(snapshot, modeContext)

    fun retryLastQaRequestAsync() {
        val snapshot = snapshotProvider.snapshot()
        val request = snapshot.qaRequestRecoveryState.lastFailedRequest
        if (request == null) {
            emitQaFailed(
                QaFailedResult(
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
        executeQaAsync(
            snapshot = snapshot,
            modeContext = modeContext,
            statusMessage = "正在重试上一次失败的问答请求，请稍候。",
        )
    }

    fun resolveInvestigationThread(
        threadId: String,
        status: RiskResolutionStatus,
        note: String = "",
    ) {
        val snapshot = snapshotProvider.snapshot()
        val qaResult = snapshot.qaResult ?: return
        val updatedResult = riskResolutionService.applyResolution(
            result = qaResult,
            threadId = threadId,
            status = status,
            note = note,
        ) ?: return
        val (draftValidationState, codeDecision) = evaluateEligibility(
            snapshot.copy(qaResult = updatedResult),
        )
        emitQaCompleted(
            QaCompletedResult(
                result = updatedResult,
                requestState = snapshot.qaRequestState,
                draftValidationState = draftValidationState,
                codeEligibilityDecision = codeDecision,
                runtimeArtifacts = emptyList(),
                feedbackLevel = ApplicationFeedbackLevel.SUCCESS,
                statusMessage = resolutionFeedbackMessage(status, codeDecision.allowed),
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
            QaCapability.QaExecutor { qaInput, _, _ ->
                val overrideExecutor = qaExecutorOverrideProvider()
                if (overrideExecutor != null) {
                    overrideExecutor(qaInput.qaContext, qaInput.question)
                } else {
                    graphQaPatchService.answer(
                        context = qaInput.qaContext,
                        question = qaInput.question,
                        settings = qaInput.settings,
                        session = qaInput.session,
                        sourceThreadId = qaInput.sourceThreadId,
                        requestedMode = qaInput.requestedMode,
                        effectiveMode = qaInput.effectiveMode,
                        onPreview = qaInput.onPreview,
                        runtimeEvidenceTrusted = true,
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
                graphEditRequestExecutor = graphEditRequestExecutor,
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
    ): List<com.charmnight.linkgraph.application.result.ApplicationRuntimeArtifactSummary> =
        com.charmnight.linkgraph.application.workflow.review.toRuntimeArtifactSummaries(result)

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
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): QaCapabilityInput =
        com.charmnight.linkgraph.application.workflow.review.buildQaCapabilityInput(
            snapshot = snapshot,
            modeContext = modeContext,
            settings = settings,
            planningContextFactory = planningContextFactory,
            onPreview = onPreview,
        )

    private fun executeQaAsync(
        snapshot: WorkflowEditorSnapshot,
        modeContext: QaModeContext,
        statusMessage: String,
    ) {
        val request = modeContext.request
        val requestId = asyncRequestLifecycle.beginQaRequest()
        val settings = settingsProvider()
        val presentation = asyncRequestLifecycle.buildAsyncRequestLifecycleResult(
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
                        scene = ReviewRequestScene.QA,
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
                scene = ReviewRequestScene.QA,
                requestState = presentation.requestState,
                submittedRequest = request,
                clearRuntimeArtifactScene = "qa",
                statusMessage = statusMessage,
            ),
        )
        asyncRequestLifecycle.logAsyncRequestEvent(logger, "started", presentation.requestState)
        asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = asyncRequestLifecycle::completeQaRequest,
            onTimeout = {
                val timedOutState = asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                asyncRequestLifecycle.logAsyncRequestEvent(logger, "timedOut", timedOutState)
                emitQaFailed(
                    QaFailedResult(
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
                        settings = settings,
                        onPreview = previewUpdater,
                    ),
                )
            },
            onCompleted = { result ->
                if (project.isDisposed || !asyncRequestLifecycle.completeQaRequest(requestId)) {
                    return@runBackgroundTask
                }
                result.fold(
                    onSuccess = { runtimeResult ->
                        val normalizedQaResult = runtimeResult.output?.let { output ->
                            qaResultNormalizer.normalize(output.markRuntimeEvidenceTrusted(), modeContext)
                        }
                        val requestState = asyncRequestLifecycle.withRuntimeMetadata(
                            requestState = if (normalizedQaResult == null) {
                                logger.warn(
                                    "问答 runtime 未返回结果: runId=${runtimeResult.finalState.runId}, " +
                                        "capabilityId=${runtimeResult.finalState.capabilityId}, " +
                                        "phase=${runtimeResult.finalState.phase}, " +
                                        "stepIndex=${runtimeResult.finalState.stepIndex}, " +
                                        "failureReason=${runtimeResult.finalState.failureReason}, " +
                                        "lastModelOutput=${runtimeResult.finalState.lastModelOutput}",
                                )
                                val failurePreview = reviewUseCase.resolveQaRuntimeResult(
                                    runtimeResult = runtimeResult,
                                    modeContext = modeContext,
                                    requestState = asyncRequestLifecycle.buildFailedRequestState(
                                        presentation = presentation,
                                        message = "问答失败：runtime 未返回结果。",
                                    ),
                                    runtimeArtifacts = emptyList(),
                                    draftValidationState = null,
                                    codeEligibilityDecision = null,
                                ) as ReviewUseCaseResult.QaFailed
                                asyncRequestLifecycle.buildFailedRequestState(
                                    presentation = presentation,
                                    message = failurePreview.presentation.message,
                                )
                            } else {
                                asyncRequestLifecycle.buildSucceededRequestState(
                                    presentation = presentation,
                                    successMessage = if (normalizedQaResult.newCandidateChanges.isNotEmpty()) {
                                        "问答完成，已生成待确认变更。"
                                    } else {
                                        "问答完成。"
                                    },
                                    completedRemotely = normalizedQaResult.source == LlmResultSource.REMOTE,
                                    warnings = normalizedQaResult.warnings,
                                )
                            },
                            runtimeState = runtimeResult.finalState,
                        )
                        if (normalizedQaResult != null) {
                            debugLazy(logger.isDebugEnabled, logger::debug) {
                                "问答 runtime 成功: runId=${runtimeResult.finalState.runId}, capabilityId=${runtimeResult.finalState.capabilityId}, " +
                                    "stepIndex=${runtimeResult.finalState.stepIndex}, artifactCount=${runtimeResult.artifactSummaries.size}, " +
                                    "filesRead=${runtimeResult.finalState.budget.filesRead}, stepsUsed=${runtimeResult.finalState.budget.usedSteps}, " +
                                    "failureReason=${runtimeResult.finalState.failureReason}"
                            }
                            runtimeTrace {
                                val candidates = normalizedQaResult.newCandidateChanges.ifEmpty { normalizedQaResult.candidateChanges }
                                val candidateSummary = candidates.take(3).joinToString(
                                    prefix = "[",
                                    postfix = if (candidates.size > 3) ", ...]" else "]",
                                ) { candidate ->
                                    GenerationDiagnostics.summarizeCandidateChange(candidate) +
                                        ", graphPatch=" + GenerationDiagnostics.summarizeGraphPatch(candidate.graphPatch)
                                }
                                "问答 runtime 结果: source=${normalizedQaResult.source}, " +
                                    "candidateCount=${normalizedQaResult.candidateChanges.size}, " +
                                    "newCandidateCount=${normalizedQaResult.newCandidateChanges.size}, " +
                                    "candidates=$candidateSummary"
                            }
                        }
                        val eligibility = normalizedQaResult?.let { output -> evaluateEligibility(snapshot.copy(qaResult = output)) }
                        val reviewResult = reviewUseCase.resolveQaRuntimeResult(
                            runtimeResult = runtimeResult,
                            modeContext = modeContext,
                            requestState = requestState,
                            runtimeArtifacts = toRuntimeArtifactSummaries(runtimeResult),
                            draftValidationState = eligibility?.first,
                            codeEligibilityDecision = eligibility?.second,
                        )
                        val projectedRequestState = when (reviewResult) {
                            is ReviewUseCaseResult.QaCompleted -> reviewResult.presentation.requestState
                            is ReviewUseCaseResult.QaFailed -> reviewResult.presentation.requestState
                        }
                        asyncRequestLifecycle.logAsyncRequestEvent(
                            logger,
                            if (reviewResult is ReviewUseCaseResult.QaCompleted) "succeeded" else "failed",
                            projectedRequestState,
                        )
                        when (reviewResult) {
                            is ReviewUseCaseResult.QaCompleted -> emitQaCompleted(
                                reviewResult.presentation.copy(
                                    feedbackLevel = if (requestState.fallbackUsed) {
                                        ApplicationFeedbackLevel.WARNING
                                    } else {
                                        ApplicationFeedbackLevel.SUCCESS
                                    },
                                    statusMessage = requestState.statusMessage
                                        ?: if (reviewResult.presentation.result.newCandidateChanges.isNotEmpty()) {
                                            "问答完成，已生成待确认变更。"
                                        } else {
                                            "问答完成。"
                                        },
                                ),
                            )
                            is ReviewUseCaseResult.QaFailed -> emitQaFailed(reviewResult.presentation)
                        }
                    },
                    onFailure = { throwable ->
                        logger.warn("异步问答失败", throwable)
                        val message = "问答失败：${throwable.message ?: throwable.javaClass.simpleName}"
                        val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                        asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                        emitQaFailed(
                            QaFailedResult(
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
    ): Pair<com.charmnight.linkgraph.workbench.DraftValidationState, com.charmnight.linkgraph.workbench.StageEligibilityDecision> =
        com.charmnight.linkgraph.application.workflow.review.evaluateQaEligibility(snapshot, riskResolutionService)

    private fun effectiveRemoteRequested(): Boolean =
        com.charmnight.linkgraph.application.workflow.review.effectiveRemoteRequested(settingsProvider())

    private fun effectiveStreamingSupported(): Boolean =
        com.charmnight.linkgraph.application.workflow.review.effectiveStreamingSupported(settingsProvider())

    private fun buildQaStartMessage(
        remoteRequested: Boolean,
        streamingSupported: Boolean,
        modeContext: QaModeContext,
    ): String =
        com.charmnight.linkgraph.application.workflow.review.buildQaStartMessage(remoteRequested, streamingSupported, modeContext)

    /**
     * 将可重放请求分类为本轮唯一的模式上下文。
     */
    private fun modeContext(request: ReplayableQaRequest): QaModeContext =
        com.charmnight.linkgraph.application.workflow.review.resolveQaModeContext(request, qaModeClassifier)

    private fun resolutionFeedbackMessage(
        status: RiskResolutionStatus,
        codeAllowed: Boolean,
    ): String =
        com.charmnight.linkgraph.application.workflow.review.resolutionFeedbackMessage(status, codeAllowed)

    /**
     * 异步发起差异问答，并把修订草稿回写到前端。
     */
    fun requestDiffReviewAsync(
        question: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ) = diffReviewWorkflow.requestDiffReviewAsync(question, selectedDiffItemIds)

    /**
     * 异步生成链路讲解，并把请求状态同步到前端。
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
    ) = graphBeautificationWorkflow.requestGraphBeautificationAsync(
        goal = goal,
        preferredStyle = preferredStyle,
        explanationFocus = explanationFocus,
        focusNodeId = focusNodeId,
        followUp = followUp,
        granularity = granularity,
        assistantIntent = assistantIntent,
        assistantActionId = assistantActionId,
    )
}
