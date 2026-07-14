package com.charmnight.linkgraph.application.workflow.review

import com.charmnight.linkgraph.application.diagnostics.GenerationDiagnostics
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.GraphEditRequestExecutor
import com.charmnight.linkgraph.agent.tools.ToolGraphSnapshotProvider
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.result.ApplicationRuntimeArtifactSummary
import com.charmnight.linkgraph.application.result.QaCompletedResult
import com.charmnight.linkgraph.application.result.QaFailedResult
import com.charmnight.linkgraph.application.result.ReviewRequestScene
import com.charmnight.linkgraph.application.result.ReviewRequestStartedResult
import com.charmnight.linkgraph.application.usecase.ReviewUseCase
import com.charmnight.linkgraph.application.usecase.ReviewUseCaseResult
import com.charmnight.linkgraph.application.workflow.QaResultNormalizer
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.agent.model.GraphQaContext
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.agent.artifact.AgentArtifactStoreService
import com.charmnight.linkgraph.agent.artifact.ArtifactStore
import com.charmnight.linkgraph.agent.capability.QaCapability
import com.charmnight.linkgraph.agent.capability.QaCapabilityInput
import com.charmnight.linkgraph.agent.model.markRuntimeEvidenceTrusted
import com.charmnight.linkgraph.agent.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.agent.runtime.AgentRunResult
import com.charmnight.linkgraph.agent.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.QaMode
import com.charmnight.linkgraph.workbench.QaModeContext
import com.charmnight.linkgraph.workbench.QaRequestLifecycleService
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.charmnight.linkgraph.workbench.StageEligibilityDecision
import com.charmnight.linkgraph.workbench.DraftValidationState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 异步问答请求执行器（P2-1 真正的架构分解）。
 *
 * 从 ReviewWorkflow 抽出的独立类，负责完整的 QA 请求生命周期：
 * - 请求构建 → 异步派发 → 运行时执行 → 结果归一化 → 资格评估 → 事件发射
 *
 * ReviewWorkflow 不再混入 QA 执行细节，只做"该路由到哪个子工作流"的分发。
 *
 * @param deps QA 工作流共享依赖
 * @param graphQaPatchService 图问答 LLM 服务
 * @param qaExecutorHook 测试用执行器覆盖
 * @param qaResultNormalizer 问答结果归一化
 * @param riskResolutionService 风险决策服务
 * @param reviewUseCase 评审用例
 * @param eventSink 事件出口
 * @param logger 日志
 */
internal class QaRequestExecutor(
    private val deps: QaWorkflowDeps,
    private val graphQaPatchService: com.charmnight.linkgraph.application.port.GraphQaPatchPort,
    private val qaExecutorHook: () -> ((GraphQaContext, String) -> GraphPatchResult)?,
    private val reviewUseCase: ReviewUseCase,
    private val eventSink: GraphEditorApplicationEventSink,
    private val logger: Logger,
) {
    private val asyncRequestLifecycle = deps.asyncRequestLifecycle
    private val settingsProvider = deps.settingsProvider
    private val runtimeQaTraceEnabled = deps.runtimeQaTraceEnabled

    /**
     * 异步发起链路问答。
     *
     * 如果是确定性取证模式（INVESTIGATE + 有 sourceThreadId），走 investigation 路径。
     * 否则走标准 QA 执行路径。
     */
    fun requestQaAsync(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
        sourceThreadId: String? = null,
        mode: QaMode = QaMode.AUTO,
        qaRequestLifecycleService: QaRequestLifecycleService,
        riskResolutionService: RiskResolutionService,
        investigationResultRunner: (WorkflowEditorSnapshot, QaModeContext) -> GraphPatchResult,
    ) {
        val workflowSnapshot = deps.snapshot()
        val request = qaRequestLifecycleService.buildReplayableRequest(
            qaResult = workflowSnapshot.qaResult,
            question = question,
            selectedNodeIds = selectedNodeIds,
            sourceThreadId = sourceThreadId,
            mode = mode,
        )
        val modeCtx = modeContext(request)
        if (modeCtx.isDeterministicInvestigation) {
            executeInvestigationAsync(workflowSnapshot, modeCtx, investigationResultRunner, riskResolutionService)
            return
        }
        executeQaAsync(
            snapshot = workflowSnapshot,
            modeContext = modeCtx,
            statusMessage = buildQaStartMessage(
                remoteRequested = effectiveRemoteRequested(),
                streamingSupported = effectiveStreamingSupported(),
                modeContext = modeCtx,
            ),
        )
    }

    /** 重试上一次失败的 QA 请求。 */
    fun retryLastQaRequestAsync(
        snapshot: WorkflowEditorSnapshot,
        qaRequestLifecycleService: QaRequestLifecycleService,
    ) {
        val request = snapshot.qaRequestRecoveryState.lastFailedRequest
        if (request == null) {
            emitQaFailed(
                eventSink,
                QaFailedResult(
                    message = "当前没有可直接重试的失败问答请求。",
                    requestState = AsyncRequestState.failed(
                        message = "当前没有可直接重试的失败问答请求。",
                        scene = "问答",
                    ),
                    feedbackLevel = ApplicationFeedbackLevel.WARNING,
                ),
            )
            return
        }
        val modeCtx = modeContext(request)
        executeQaAsync(
            snapshot = snapshot,
            modeContext = modeCtx,
            statusMessage = "正在重试上一次失败的问答请求，请稍候。",
        )
    }

    /**
     * 执行异步 QA 请求：开始请求 → 设置超时 → 后台执行 → 结果处理。
     *
     * 这是整个 QA 流程的核心编排方法。它把 asyncRequestLifecycle 的请求追踪、
     * 运行时的代理执行、reviewUseCase 的结果解析和事件发射串联在一起。
     */
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
                requestId = requestId,
                isRequestActive = asyncRequestLifecycle::isQaRequestActive,
            ) { _, previewText, finalizing ->
                emitReviewStreamingPreview(
                    eventSink = eventSink,
                    scene = ReviewRequestScene.QA,
                    requestId = requestId,
                    previewText = previewText,
                    finalizingStructuredResult = finalizing,
                )
            }
        } else {
            null
        }
        emitReviewRequestStarted(
            eventSink,
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
                    eventSink,
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
                    input = buildQaCapabilityInput(snapshot, modeContext, settings, previewUpdater),
                )
            },
            onCompleted = { result ->
                if (deps.project.isDisposed || !asyncRequestLifecycle.completeQaRequest(requestId)) {
                    return@runBackgroundTask
                }
                result.fold(
                    onSuccess = { runtimeResult ->
                        handleQaRuntimeSuccess(runtimeResult, snapshot, modeContext, presentation, request)
                    },
                    onFailure = { throwable ->
                        handleQaRuntimeFailure(throwable, presentation, request)
                    },
                )
            },
        )
    }

    /**
     * 处理运行时成功：归一化结果、评估资格、解析评审用例、发射完成/失败事件。
     */
    private fun handleQaRuntimeSuccess(
        runtimeResult: AgentRunResult<GraphPatchResult>,
        snapshot: WorkflowEditorSnapshot,
        modeContext: QaModeContext,
        presentation: com.charmnight.linkgraph.application.request.AsyncRequestLifecycleResult,
        request: com.charmnight.linkgraph.workbench.ReplayableQaRequest,
    ) {
        val qaResultNormalizer = deps.qaResultNormalizer
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
        val eligibility = normalizedQaResult?.let { output ->
            evaluateQaEligibility(snapshot.copy(qaResult = output), deps.riskResolutionService)
        }
        val reviewResult = reviewUseCase.resolveQaRuntimeResult(
            runtimeResult = runtimeResult,
            modeContext = modeContext,
            requestState = requestState,
            runtimeArtifacts = com.charmnight.linkgraph.application.workflow.review.toRuntimeArtifactSummaries(runtimeResult),
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
                eventSink,
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
            is ReviewUseCaseResult.QaFailed -> emitQaFailed(eventSink, reviewResult.presentation)
        }
    }

    /** 处理运行时失败：记录日志、发射失败事件。 */
    private fun handleQaRuntimeFailure(
        throwable: Throwable,
        presentation: com.charmnight.linkgraph.application.request.AsyncRequestLifecycleResult,
        request: com.charmnight.linkgraph.workbench.ReplayableQaRequest,
    ) {
        logger.warn("异步问答失败", throwable)
        val message = "问答失败：${throwable.message ?: throwable.javaClass.simpleName}"
        val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
        asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
        emitQaFailed(
            eventSink,
            QaFailedResult(
                message = message,
                requestState = requestState,
                failedRequest = request,
                runtimeArtifacts = emptyList(),
            ),
        )
    }

    /**
     * 异步执行确定性继续取证（INVESTIGATE 模式）。
     */
    private fun executeInvestigationAsync(
        snapshot: WorkflowEditorSnapshot,
        modeContext: QaModeContext,
        investigationResultRunner: (WorkflowEditorSnapshot, QaModeContext) -> GraphPatchResult,
        riskResolutionService: RiskResolutionService,
    ) {
        val requestId = asyncRequestLifecycle.beginQaRequest()
        val presentation = asyncRequestLifecycle.buildAsyncRequestLifecycleResult(
            requestId = requestId,
            sceneLabel = "问答",
            settings = settingsProvider(),
            requestedMode = modeContext.requestedMode,
            effectiveMode = modeContext.effectiveMode,
        )
        emitReviewRequestStarted(
            eventSink,
            ReviewRequestStartedResult(
                scene = ReviewRequestScene.QA,
                requestState = presentation.requestState,
                submittedRequest = modeContext.request,
                statusMessage = "正在执行确定性继续取证，请稍候。",
            ),
        )
        asyncRequestLifecycle.runBackgroundTask(
            work = { investigationResultRunner(snapshot, modeContext) },
            onCompleted = { result ->
                if (deps.project.isDisposed || !asyncRequestLifecycle.completeQaRequest(requestId)) {
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
                        val (draftValidationState, codeDecision) = evaluateQaEligibility(
                            snapshot.copy(qaResult = output),
                            riskResolutionService,
                        )
                        emitQaCompleted(
                            eventSink,
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
                            eventSink,
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
    }

    /**
     * 统一执行运行时问答：构造能力对象并调用 agentRunCoordinator。
     */
    private fun executeQaRuntime(input: QaCapabilityInput): AgentRunResult<GraphPatchResult> {
        val capability = deps.qaCapabilityFactory(
            QaCapability.QaExecutor { qaInput, _, _ ->
                val overrideExecutor = qaExecutorHook()
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
        val runtimeResult = deps.agentRunCoordinator.run(
            capability = capability,
            input = input,
            runtimeContext = AgentRuntimeContext(
                project = deps.project,
                snapshotSupplier = deps.toolGraphSnapshotProvider::snapshot,
                artifactStore = deps.artifactStoreProvider(),
                graphEditRequestExecutor = deps.graphEditRequestExecutor,
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

    // ---- 以下为从 ReviewWorkflow 移入的辅助委托 ----

    private fun modeContext(request: com.charmnight.linkgraph.workbench.ReplayableQaRequest): QaModeContext =
        resolveQaModeContext(request, deps.qaModeClassifier)

    private fun effectiveRemoteRequested(): Boolean =
        com.charmnight.linkgraph.application.workflow.review.effectiveRemoteRequested(settingsProvider())

    private fun effectiveStreamingSupported(): Boolean =
        com.charmnight.linkgraph.application.workflow.review.effectiveStreamingSupported(settingsProvider())

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
            planningContextFactory = deps.planningContextFactory,
            onPreview = onPreview,
        )

    private fun runtimeTrace(message: () -> String) {
        if (runtimeQaTraceEnabled) {
            logger.warn(message())
        }
    }
}

/** 评估快照的草稿校验状态 + 阶段准入决策。 */
/** evaluateEligibility 和 toRuntimeArtifactSummaries 使用同包顶层函数（QaReviewPresentation.kt / QaEligibilityPolicy.kt）。 */
