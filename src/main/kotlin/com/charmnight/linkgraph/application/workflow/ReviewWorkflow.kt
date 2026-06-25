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

    // P2-1 真正的架构分解：QA 执行逻辑封装到 QaRequestExecutor，ReviewWorkflow 只做分发
    private val qaWorkflowDeps = com.charmnight.linkgraph.application.workflow.review.QaWorkflowDeps(
        project = project,
        snapshotProvider = snapshotProvider,
        toolGraphSnapshotProvider = toolGraphSnapshotProvider,
        eventSink = eventSink,
        planningContextFactory = planningContextFactory,
        settingsProvider = settingsProvider,
        asyncRequestLifecycle = asyncRequestLifecycle,
        agentRunCoordinator = agentRunCoordinator,
        artifactStoreProvider = artifactStoreProvider,
        graphEditRequestExecutor = graphEditRequestExecutor,
        runtimeQaTraceEnabled = runtimeQaTraceEnabled,
        qaCapabilityFactory = qaCapabilityFactory,
        qaRequestLifecycleService = qaRequestLifecycleService,
        qaResultNormalizer = qaResultNormalizer,
        riskResolutionService = riskResolutionService,
        investigationPipelineFactory = investigationPipelineFactory,
        investigationGraphPatchAdapter = investigationGraphPatchAdapter,
        qaModeClassifier = qaModeClassifier,
    )
    private val qaRequestExecutor = com.charmnight.linkgraph.application.workflow.review.QaRequestExecutor(
        deps = qaWorkflowDeps,
        graphQaPatchService = graphQaPatchService,
        qaExecutorOverrideProvider = qaExecutorOverrideProvider,
        reviewUseCase = reviewUseCase,
        eventSink = eventSink,
        logger = logger,
    )

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
     * 异步发起链路问答。委托给 [qaRequestExecutor]，ReviewWorkflow 只做路由分发。
     */
    fun requestQaAsync(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
        sourceThreadId: String? = null,
        mode: QaMode = QaMode.AUTO,
    ) {
        qaRequestExecutor.requestQaAsync(
            question = question,
            selectedNodeIds = selectedNodeIds,
            sourceThreadId = sourceThreadId,
            mode = mode,
            qaRequestLifecycleService = qaRequestLifecycleService,
            riskResolutionService = riskResolutionService,
            investigationResultRunner = ::runInvestigationResult,
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

    /** 重试上一次失败的 QA 请求。委托给 [qaRequestExecutor]。 */
    fun retryLastQaRequestAsync() {
        qaRequestExecutor.retryLastQaRequestAsync(
            snapshot = snapshotProvider.snapshot(),
            qaRequestLifecycleService = qaRequestLifecycleService,
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
        val (draftValidationState, codeDecision) = com.charmnight.linkgraph.application.workflow.review.evaluateQaEligibility(
            snapshot.copy(qaResult = updatedResult),
            riskResolutionService,
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

    /** resolutionFeedbackMessage 已抽到 top-level（QaReviewPresentation.kt）。 */
    private fun resolutionFeedbackMessage(
        status: RiskResolutionStatus,
        codeAllowed: Boolean,
    ): String =
        com.charmnight.linkgraph.application.workflow.review.resolutionFeedbackMessage(status, codeAllowed)
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
