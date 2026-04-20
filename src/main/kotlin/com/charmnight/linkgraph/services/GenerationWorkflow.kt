package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.codegen.CodeDraftWriterService
import com.charmnight.linkgraph.codegen.CodeGenerationResult
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.codegen.ProjectPathNormalizer
import com.charmnight.linkgraph.codegen.emptyResultDetailMessage
import com.charmnight.linkgraph.codegen.emptyResultMessage
import com.charmnight.linkgraph.llm.GenerationContext
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.GraphGenerationService
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.artifact.AgentArtifactStoreService
import com.charmnight.linkgraph.llm.artifact.ArtifactType
import com.charmnight.linkgraph.llm.artifact.ArtifactStore
import com.charmnight.linkgraph.llm.artifact.PlanArtifact
import com.charmnight.linkgraph.llm.capability.CodegenCapability
import com.charmnight.linkgraph.llm.capability.CodegenCapabilityInput
import com.charmnight.linkgraph.llm.capability.PlanCapability
import com.charmnight.linkgraph.llm.capability.PlanCapabilityInput
import com.charmnight.linkgraph.llm.runtime.AgentRunCoordinator
import com.charmnight.linkgraph.llm.runtime.AgentRunResult
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.GraphEditorStateService.OperationFeedbackLevel
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.charmnight.linkgraph.workbench.StageEligibilityDecision
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 统一处理实现计划、代码草稿以及草稿写入项目目录相关流程。
 */
internal class GenerationWorkflow(
    /** 当前项目。 */
    private val project: Project,
    /** 项目级编辑器状态会话。 */
    private val session: ProjectEditorSession,
    /** 规划上下文工厂。 */
    private val planningContextFactory: PlanningContextFactory,
    /** 实现计划生成服务。 */
    private val graphGenerationService: GraphGenerationService,
    /** 代码草稿生成服务。 */
    private val codeGenerationService: CodeGenerationService,
    /** 草稿写入服务。 */
    private val codeDraftWriterService: CodeDraftWriterService,
    /** 源码跳转服务提供器。 */
    private val sourceNavigationServiceProvider: () -> SourceNavigationService,
    /** 当前生效设置。 */
    private val settingsProvider: () -> LinkGraphSettingsState,
    /** 异步请求生命周期支持。 */
    private val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    /** 日志记录器。 */
    private val logger: Logger,
    /** runtime 协调器。 */
    private val agentRunCoordinator: AgentRunCoordinator = AgentRunCoordinator(),
    /** 跨 run 共享的 artifact store。 */
    private val artifactStoreProvider: () -> ArtifactStore = {
        project.getService(AgentArtifactStoreService::class.java).artifactStore
    },
    /** 计划 capability 工厂。 */
    private val planCapabilityFactory: (PlanCapability.PlanExecutor) -> PlanCapability = { planExecutor ->
        PlanCapability(planExecutor = planExecutor)
    },
    /** 代码 capability 工厂。 */
    private val codegenCapabilityFactory: (CodegenCapability.CodegenExecutor) -> CodegenCapability = { codegenExecutor ->
        CodegenCapability(project = project, codegenExecutor = codegenExecutor)
    },
    /** 风险决策与阶段准入服务。 */
    private val riskResolutionService: RiskResolutionService = RiskResolutionService(),
) {
    private data class GenerationPrerequisiteFailure(
        val scene: String,
        val message: String,
        val detailMessage: String,
    )

    /**
     * 基于当前规划上下文生成实现计划。
     */
    fun requestGenerationPlan() {
        val snapshot = session.snapshot()
        val (planDecision, _) = refreshEligibilityDecisions(snapshot)
        rejectStageEligibility(planDecision, "实现计划") { message, requestState ->
            markGenerationPlanRequestFailed(message, requestState)
        }?.let { return }
        val payload = planningContextFactory.computePlanningPayload(snapshot)
        val runtimeResult = executePlanRuntime(payload)
        val plan = requireNotNull(runtimeResult.output) {
            "实现计划 runtime 未返回结果，runId=${runtimeResult.finalState.runId}"
        }
        val requestState = asyncRequestLifecycle.withRuntimeMetadata(
            requestState = GraphEditorStateService.AsyncRequestState.succeeded(
                scene = "实现计划",
                statusMessage = "实现计划已生成。",
            ),
            runtimeState = runtimeResult.finalState,
        )
        session.mutate {
            markRuntimeArtifactSummaries("plan", toRuntimeArtifactSummaries(runtimeResult))
            markGenerationPlan(plan, requestState)
        }
    }

    /**
     * 异步生成实现计划，并把请求状态同步到前端。
     */
    fun requestGenerationPlanAsync() {
        val snapshot = session.snapshot()
        val (planDecision, _) = refreshEligibilityDecisions(snapshot)
        rejectStageEligibility(planDecision, "实现计划") { message, requestState ->
            markGenerationPlanRequestFailed(message, requestState)
        }?.let { return }
        val requestId = asyncRequestLifecycle.beginGenerationPlanRequest()
        val effectiveSettings = settingsProvider()
        val presentation = asyncRequestLifecycle.buildAsyncRequestPresentation(
            requestId = requestId,
            sceneLabel = "实现计划生成",
            settings = effectiveSettings,
            disabledMode = GraphEditorStateService.AsyncRequestExecutionMode.DISABLED,
        )
        val previewUpdater = if (presentation.requestState.streaming) {
            asyncRequestLifecycle.createStreamingPreviewUpdater(
                requestId,
                GraphEditorStateService::updateGenerationPlanRequestPreview,
            )
        } else {
            null
        }
        session.mutateBatch {
            apply {
                beginGenerationPlanRequest(presentation.requestState)
            }
            apply {
                markRuntimeArtifactSummaries("plan", emptyList())
            }
            apply {
                markOperationFeedback(
                    OperationFeedbackLevel.INFO,
                    if (presentation.remoteRequested) {
                        if (presentation.streamingSupported) {
                            "已发起远程 LLM 实现计划请求，当前采用流式输出。"
                        } else {
                            "已发起远程 LLM 实现计划请求，当前采用完整返回。"
                        }
                    } else {
                        "正在生成实现计划，请稍候。"
                    },
                )
            }
        }
        asyncRequestLifecycle.logAsyncRequestEvent(logger, "started", presentation.requestState)
        asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = asyncRequestLifecycle::completeGenerationPlanRequest,
            onTimeout = {
                val timedOutState = asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                asyncRequestLifecycle.logAsyncRequestEvent(logger, "timedOut", timedOutState)
                session.mutateBatch {
                    apply {
                        markGenerationPlanRequestFailed(
                            timedOutState.errorMessage ?: "实现计划生成超时",
                            timedOutState,
                        )
                    }
                    apply {
                        markOperationFeedback(
                            OperationFeedbackLevel.ERROR,
                            timedOutState.errorMessage ?: "实现计划生成超时",
                        )
                    }
                }
            },
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                executePlanRuntime(
                    payload = planningContextFactory.computePlanningPayload(snapshot),
                    onPreview = previewUpdater,
                )
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed || !asyncRequestLifecycle.completeGenerationPlanRequest(requestId)) {
                        return@invokeLater
                    }
                    result.fold(
                        onSuccess = { runtimeResult ->
                            val normalizedPlan = runtimeResult.output
                            if (normalizedPlan == null) {
                                val message = "生成计划失败：runtime 未返回结果。"
                                val requestState = asyncRequestLifecycle.withRuntimeMetadata(
                                    requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message),
                                    runtimeState = runtimeResult.finalState,
                                )
                                asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                                session.mutateBatch {
                                    apply {
                                        markRuntimeArtifactSummaries("plan", toRuntimeArtifactSummaries(runtimeResult))
                                    }
                                    apply {
                                        markGenerationPlanRequestFailed(message, requestState)
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
                            debugLazy(logger.isDebugEnabled, logger::debug) {
                                "异步实现计划生成完成: ${GenerationDiagnostics.summarizePlan(normalizedPlan)}, " +
                                    "artifactCount=${runtimeResult.artifactSummaries.size}, filesRead=${runtimeResult.finalState.budget.filesRead}, " +
                                    "stepsUsed=${runtimeResult.finalState.budget.usedSteps}"
                            }
                            val requestState = asyncRequestLifecycle.withRuntimeMetadata(
                                requestState = asyncRequestLifecycle.buildSucceededRequestState(
                                    presentation = presentation,
                                    successMessage = "实现计划已生成。",
                                    completedRemotely = normalizedPlan.source == GenerationPlanSource.REMOTE,
                                    warnings = normalizedPlan.warnings,
                                ),
                                runtimeState = runtimeResult.finalState,
                            )
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "succeeded", requestState)
                            val feedbackLevel = if (requestState.fallbackUsed) {
                                OperationFeedbackLevel.WARNING
                            } else {
                                OperationFeedbackLevel.SUCCESS
                            }
                            session.mutateBatch {
                                apply {
                                    markRuntimeArtifactSummaries("plan", toRuntimeArtifactSummaries(runtimeResult))
                                }
                                apply {
                                    markGenerationPlan(normalizedPlan, requestState)
                                }
                                apply {
                                    markOperationFeedback(
                                        feedbackLevel,
                                        requestState.statusMessage ?: "实现计划已生成。",
                                        preserveLastMessageType = true,
                                    )
                                }
                            }
                        },
                        onFailure = { throwable ->
                            logger.warn("异步生成计划失败", throwable)
                            val message = "生成计划失败：${throwable.message ?: throwable.javaClass.simpleName}"
                            val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                            session.mutateBatch {
                                apply {
                                    markRuntimeArtifactSummaries("plan", emptyList())
                                }
                                apply {
                                    markGenerationPlanRequestFailed(message, requestState)
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
     * 基于当前规划上下文生成代码草稿。
     */
    fun requestCodeDrafts() {
        val snapshot = session.snapshot()
        val (_, codeDecision) = refreshEligibilityDecisions(snapshot)
        rejectStageEligibility(codeDecision, "代码草稿") { message, requestState ->
            markCodeDraftRequestFailed(message, requestState)
        }?.let { return }
        rejectOrphanedGenerationPlan(snapshot, "代码草稿") { message, requestState ->
            markCodeDraftRequestFailed(message, requestState)
        }?.let { return }
        val result = executeCodegenRuntime(snapshot)
        val draftResult = requireNotNull(result.output) {
            "代码生成 runtime 未返回结果，runId=${result.finalState.runId}"
        }
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "代码草稿生成完成: ${GenerationDiagnostics.summarizeCodeGenerationResult(draftResult)}, " +
                "artifactCount=${result.artifactSummaries.size}, filesRead=${result.finalState.budget.filesRead}, " +
                "stepsUsed=${result.finalState.budget.usedSteps}"
        }
        if (draftResult.drafts.isEmpty()) {
            val message = draftResult.emptyResultMessage()
            session.mutateBatch {
                apply {
                    markCodeDraftRequestFailed(
                        message,
                        GraphEditorStateService.AsyncRequestState.failed(
                            message = message,
                            detailMessage = draftResult.emptyResultDetailMessage(),
                        ),
                    )
                }
                apply {
                    markOperationFeedback(
                        OperationFeedbackLevel.ERROR,
                        message,
                        preserveLastMessageType = true,
                    )
                }
            }
            return
        }
        session.mutate {
            markRuntimeArtifactSummaries("codegen", toRuntimeArtifactSummaries(result))
            markGeneratedCodeDrafts(
                drafts = draftResult.drafts,
                warnings = draftResult.warnings,
                source = draftResult.source,
                promptPreview = draftResult.promptPreview,
                requestState = asyncRequestLifecycle.withRuntimeMetadata(
                    requestState = GraphEditorStateService.AsyncRequestState.succeeded(
                        scene = "代码草稿",
                        statusMessage = "代码草稿已生成。",
                    ),
                    runtimeState = result.finalState,
                ),
            )
        }
    }

    /**
     * 异步生成代码草稿，并把请求状态同步到前端。
     */
    fun requestCodeDraftsAsync() {
        val snapshot = session.snapshot()
        val (_, codeDecision) = refreshEligibilityDecisions(snapshot)
        rejectStageEligibility(codeDecision, "代码草稿") { message, requestState ->
            markCodeDraftRequestFailed(message, requestState)
        }?.let { return }
        rejectOrphanedGenerationPlan(snapshot, "代码草稿") { message, requestState ->
            markCodeDraftRequestFailed(message, requestState)
        }?.let { return }
        val requestId = asyncRequestLifecycle.beginCodeDraftRequest()
        val settings = settingsProvider()
        val presentation = asyncRequestLifecycle.buildAsyncRequestPresentation(
            requestId = requestId,
            sceneLabel = "代码草稿生成",
            settings = settings,
        )
        val previewUpdater = if (presentation.requestState.streaming) {
            asyncRequestLifecycle.createStreamingPreviewUpdater(
                requestId,
                GraphEditorStateService::updateCodeDraftRequestPreview,
            )
        } else {
            null
        }
        session.mutateBatch {
            apply {
                beginCodeDraftRequest(presentation.requestState)
            }
            apply {
                markRuntimeArtifactSummaries("codegen", emptyList())
            }
            apply {
                markOperationFeedback(
                    OperationFeedbackLevel.INFO,
                    if (presentation.remoteRequested) {
                        if (presentation.streamingSupported) {
                            "已发起远程 LLM 代码草稿请求，当前采用流式输出。"
                        } else {
                            "已发起远程 LLM 代码草稿请求，当前采用完整返回。"
                        }
                    } else {
                        "正在生成代码草稿，请稍候。"
                    },
                )
            }
        }
        asyncRequestLifecycle.logAsyncRequestEvent(logger, "started", presentation.requestState)
        asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = asyncRequestLifecycle::completeCodeDraftRequest,
            onTimeout = {
                val timedOutState = asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                asyncRequestLifecycle.logAsyncRequestEvent(logger, "timedOut", timedOutState)
                session.mutateBatch {
                    apply {
                        markCodeDraftRequestFailed(
                            timedOutState.errorMessage ?: "代码草稿生成超时",
                            timedOutState,
                        )
                    }
                    apply {
                        markOperationFeedback(
                            OperationFeedbackLevel.ERROR,
                            timedOutState.errorMessage ?: "代码草稿生成超时",
                        )
                    }
                }
            },
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                executeCodegenRuntime(snapshot, previewUpdater)
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed || !asyncRequestLifecycle.completeCodeDraftRequest(requestId)) {
                        return@invokeLater
                    }
                    result.fold(
                        onSuccess = { runtimeResult ->
                            val drafts = runtimeResult.output
                            if (drafts == null) {
                                val message = "生成代码草稿失败：runtime 未返回结果。"
                                val requestState = asyncRequestLifecycle.withRuntimeMetadata(
                                    requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message),
                                    runtimeState = runtimeResult.finalState,
                                )
                                asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                                session.mutateBatch {
                                    apply {
                                        markRuntimeArtifactSummaries("codegen", toRuntimeArtifactSummaries(runtimeResult))
                                    }
                                    apply {
                                        markCodeDraftRequestFailed(message, requestState)
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
                            debugLazy(logger.isDebugEnabled, logger::debug) {
                                "异步代码草稿生成完成: ${GenerationDiagnostics.summarizeCodeGenerationResult(drafts)}, " +
                                    "artifactCount=${runtimeResult.artifactSummaries.size}, filesRead=${runtimeResult.finalState.budget.filesRead}, " +
                                    "stepsUsed=${runtimeResult.finalState.budget.usedSteps}"
                            }
                            if (drafts.drafts.isEmpty()) {
                                val message = drafts.emptyResultMessage()
                                val requestState = asyncRequestLifecycle.buildFailedRequestState(
                                    presentation = presentation,
                                    message = message,
                                    detailMessageOverride = drafts.emptyResultDetailMessage(),
                                )
                                asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                                session.mutateBatch {
                                    apply {
                                        markRuntimeArtifactSummaries("codegen", toRuntimeArtifactSummaries(runtimeResult))
                                    }
                                    apply {
                                        markCodeDraftRequestFailed(message, requestState)
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
                                    successMessage = "代码草稿已生成。",
                                    completedRemotely = drafts.source == LlmResultSource.REMOTE,
                                    warnings = drafts.warnings,
                                ),
                                runtimeState = runtimeResult.finalState,
                            )
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "succeeded", requestState)
                            val feedbackLevel = if (requestState.fallbackUsed) {
                                OperationFeedbackLevel.WARNING
                            } else {
                                OperationFeedbackLevel.SUCCESS
                            }
                            session.mutateBatch {
                                apply {
                                    markRuntimeArtifactSummaries("codegen", toRuntimeArtifactSummaries(runtimeResult))
                                }
                                apply {
                                    markGeneratedCodeDrafts(
                                        drafts = drafts.drafts,
                                        warnings = drafts.warnings,
                                        source = drafts.source,
                                        promptPreview = drafts.promptPreview,
                                        requestState = requestState,
                                    )
                                }
                                apply {
                                    markOperationFeedback(
                                        feedbackLevel,
                                        requestState.statusMessage ?: "代码草稿已生成。",
                                        preserveLastMessageType = true,
                                    )
                                }
                            }
                        },
                        onFailure = { throwable ->
                            logger.warn("异步生成代码草稿失败", throwable)
                            val message = "生成代码草稿失败：${throwable.message ?: throwable.javaClass.simpleName}"
                            val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                            session.mutateBatch {
                                apply {
                                    markRuntimeArtifactSummaries("codegen", emptyList())
                                }
                                apply {
                                    markCodeDraftRequestFailed(message, requestState)
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
     * 把当前全部代码草稿批量写入项目目录。
     */
    fun applyCodeDrafts() {
        val snapshot = session.snapshot()
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "开始批量写入代码草稿: drafts=${snapshot.generatedCodeDrafts.size}"
        }
        val report = codeDraftWriterService.writeDrafts(project.basePath, snapshot.generatedCodeDrafts)
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "批量写入代码草稿完成: ${GenerationDiagnostics.summarizeWriteReport(report)}"
        }
        session.mutate {
            markGeneratedCodeDraftWriteReport(report)
        }
        report.writtenFiles.firstOrNull()?.let(sourceNavigationServiceProvider()::navigateToPath)
    }

    /**
     * 仅写入单个指定代码草稿。
     */
    fun applySingleCodeDraft(draftId: String) {
        val snapshot = session.snapshot()
        val draft = snapshot.generatedCodeDrafts.firstOrNull { it.id == draftId } ?: return
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "开始写入单个代码草稿: draftId=$draftId, targetPath=${draft.targetPath}"
        }
        val report = codeDraftWriterService.writeDrafts(project.basePath, listOf(draft))
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "单个代码草稿写入完成: ${GenerationDiagnostics.summarizeWriteReport(report)}"
        }
        session.mutate {
            markGeneratedCodeDraftWriteReport(mergeWriteReport(snapshot.generatedCodeDraftWriteReport, report))
        }
        report.writtenFiles.firstOrNull()?.let(sourceNavigationServiceProvider()::navigateToPath)
    }

    /**
     * 请求跳转到草稿文件路径。
     */
    fun requestDraftNavigation(targetPath: String) {
        sourceNavigationServiceProvider().navigateToPath(targetPath)
    }

    /**
     * 构造代码生成与计划生成共用的上下文。
     */
    private fun buildGenerationContext(payload: PlanningPayload): GenerationContext {
        return GenerationContext(
            graph = payload.planningGraph,
            mermaidIssues = payload.snapshot.mermaidIssues,
            diff = payload.diff,
            syncPreviewItems = payload.previewItems,
            confirmedChanges = emptyList(),
            sourceContext = emptyList(),
        )
    }

    /**
     * 统一执行计划 runtime。
     */
    private fun executePlanRuntime(
        payload: PlanningPayload,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): AgentRunResult<GenerationPlan> {
        val runtimeResult = agentRunCoordinator.run(
            capability = planCapabilityFactory(
                PlanCapability.PlanExecutor { input, _, _ ->
                    ProjectPathNormalizer.normalizePlan(
                        planningContextFactory.buildPlanSnapshot(
                            planningGraph = input.planningPayload.planningGraph,
                            diff = input.planningPayload.diff,
                            previewItems = input.planningPayload.previewItems,
                            snapshot = input.planningPayload.snapshot,
                            sourceContext = input.planningPayload.sourceContext,
                            onPreview = onPreview,
                        ),
                        project.basePath,
                    )
                },
            ),
            input = PlanCapabilityInput(payload),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = session::snapshot,
                artifactStore = artifactStoreProvider(),
            ),
        )
        asyncRequestLifecycle.logRuntimeTrace(logger, runtimeResult.finalState)
        return runtimeResult
    }

    /**
     * 统一执行代码生成 runtime。
     */
    private fun executeCodegenRuntime(
        snapshot: GraphEditorStateService.Snapshot,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): AgentRunResult<CodeGenerationResult> {
        val runtimeResult = agentRunCoordinator.run(
            capability = codegenCapabilityFactory(
                CodegenCapability.CodegenExecutor { input, _, _ ->
                    ProjectPathNormalizer.normalizeDraftResult(
                        codeGenerationService.generateDrafts(
                            context = input.generationContext,
                            plan = input.plan,
                            settings = settingsProvider(),
                            onPreview = onPreview,
                        ),
                        project.basePath,
                    )
                },
            ),
            input = buildCodegenCapabilityInput(snapshot),
            runtimeContext = AgentRuntimeContext(
                project = project,
                snapshotSupplier = session::snapshot,
                artifactStore = artifactStoreProvider(),
            ),
        )
        asyncRequestLifecycle.logRuntimeTrace(logger, runtimeResult.finalState)
        return runtimeResult
    }

    private fun buildCodegenCapabilityInput(
        snapshot: GraphEditorStateService.Snapshot,
    ): CodegenCapabilityInput {
        val snapshotPlan = snapshot.generationPlan?.let { rawPlan ->
            ProjectPathNormalizer.normalizePlan(rawPlan, project.basePath)
        }
        val plan = snapshotPlan?.let { normalizedPlan ->
            artifactStoreProvider()
                .byType(ArtifactType.PLAN)
                .filterIsInstance<PlanArtifact>()
                .lastOrNull { artifact -> artifact.plan == normalizedPlan }
                ?.plan
                ?: error("当前实现计划缺少对应的 PlanArtifact，请重新生成实现计划后再生成代码草稿。")
        }
        val generationPayload = planningContextFactory.computePlanningPayload(
            snapshot,
            generationPlanOverride = plan,
        )
        return CodegenCapabilityInput(
            generationContext = buildGenerationContext(generationPayload),
            plan = plan,
        )
    }

    /**
     * workflow 只消费 runtime 提供的最小摘要，不重新解析 artifact store。
     */
    private fun toRuntimeArtifactSummaries(
        result: AgentRunResult<*>,
    ): List<GraphEditorStateService.RuntimeArtifactSummary> {
        return result.artifactSummaries.map(GraphEditorStateService.RuntimeArtifactSummary::from)
    }

    /**
     * 单条写入时保留已有状态，避免前端 badge 在多次点击后回退成 READY。
     */
    private fun mergeWriteReport(
        previous: GeneratedCodeDraftWriteReport?,
        current: GeneratedCodeDraftWriteReport,
    ) = GeneratedCodeDraftWriteReport(
        writtenFiles = (previous?.writtenFiles.orEmpty() + current.writtenFiles).distinct(),
        skippedFiles = (previous?.skippedFiles.orEmpty() + current.skippedFiles)
            .filterNot { it in current.writtenFiles || it in previous?.writtenFiles.orEmpty() }
            .distinct(),
        warnings = (previous?.warnings.orEmpty() + current.warnings).distinct(),
    )

    private fun refreshEligibilityDecisions(
        snapshot: GraphEditorStateService.Snapshot,
    ): Pair<StageEligibilityDecision, StageEligibilityDecision> {
        val planDecision = riskResolutionService.evaluatePlanEligibility(snapshot)
        val codeDecision = riskResolutionService.evaluateCodeEligibility(snapshot)
        session.mutateBatch {
            apply {
                markPlanEligibilityDecision(planDecision)
            }
            apply {
                markCodeEligibilityDecision(codeDecision)
            }
        }
        return planDecision to codeDecision
    }

    private fun rejectStageEligibility(
        decision: StageEligibilityDecision,
        scene: String,
        rejectRequest: GraphEditorStateService.(String, GraphEditorStateService.AsyncRequestState) -> Unit,
    ): GenerationPrerequisiteFailure? {
        if (decision.allowed) {
            return null
        }
        val failure = GenerationPrerequisiteFailure(
            scene = scene,
            message = decision.message,
            detailMessage = decision.detailMessage,
        )
        session.mutateBatch {
            apply {
                rejectRequest(
                    failure.message,
                    GraphEditorStateService.AsyncRequestState.failed(
                        message = failure.message,
                        scene = failure.scene,
                        detailMessage = failure.detailMessage,
                    ),
                )
            }
            apply {
                markOperationFeedback(
                    OperationFeedbackLevel.WARNING,
                    failure.message,
                    preserveLastMessageType = true,
                )
            }
        }
        return failure
    }

    private fun rejectOrphanedGenerationPlan(
        snapshot: GraphEditorStateService.Snapshot,
        scene: String,
        rejectRequest: GraphEditorStateService.(String, GraphEditorStateService.AsyncRequestState) -> Unit,
    ): GenerationPrerequisiteFailure? {
        val snapshotPlan = snapshot.generationPlan?.let { rawPlan ->
            ProjectPathNormalizer.normalizePlan(rawPlan, project.basePath)
        } ?: return null
        val hasPlanArtifact = artifactStoreProvider()
            .byType(ArtifactType.PLAN)
            .filterIsInstance<PlanArtifact>()
            .any { artifact -> artifact.plan == snapshotPlan }
        if (hasPlanArtifact) {
            return null
        }
        val failure = GenerationPrerequisiteFailure(
            scene = scene,
            message = "生成${scene}前请先使用 runtime 重新生成实现计划，当前实现计划缺少对应的 PlanArtifact。",
            detailMessage = "当前 UI 中存在实现计划，但缺少对应的 PlanArtifact。重新生成实现计划后再继续代码草稿生成，避免脱离 artifact lineage 继续执行。",
        )
        session.mutateBatch {
            apply {
                rejectRequest(
                    failure.message,
                    GraphEditorStateService.AsyncRequestState.failed(
                        message = failure.message,
                        scene = failure.scene,
                        detailMessage = failure.detailMessage,
                    ),
                )
            }
            apply {
                markOperationFeedback(
                    OperationFeedbackLevel.WARNING,
                    failure.message,
                    preserveLastMessageType = true,
                )
            }
        }
        return failure
    }
}
