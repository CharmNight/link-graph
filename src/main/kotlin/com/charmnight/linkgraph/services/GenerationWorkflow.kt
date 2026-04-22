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
import com.charmnight.linkgraph.llm.GenerationPlanDiscussionService
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
import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.GraphEditorStateService.OperationFeedbackLevel
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.charmnight.linkgraph.workbench.StageEligibilityDecision
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.merge.MergeRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path

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
    /** 实现建议追问服务。 */
    private val generationPlanDiscussionService: GenerationPlanDiscussionService = GenerationPlanDiscussionService(),
    /** 代码草稿 merge 打开器。 */
    private val showCodeDraftMergeRequest: (Project, MergeRequest) -> Unit = { currentProject, request ->
        DiffManager.getInstance().showMerge(currentProject, request)
    },
) {
    private data class GenerationPrerequisiteFailure(
        val scene: String,
        val message: String,
        val detailMessage: String?,
    )

    private data class RuntimeFailurePresentation(
        val message: String,
        val detailMessage: String?,
    )

    /**
     * 基于当前规划上下文生成实现计划。
     */
    fun requestGenerationPlan() {
        val snapshot = session.snapshot()
        refreshDraftAndCodeState(snapshot)
        val payload = planningContextFactory.computePlanningPayload(snapshot)
        val runtimeResult = executePlanRuntime(payload)
        val plan = resolvePlanResult(payload, runtimeResult)
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
            markOperationFeedback(
                OperationFeedbackLevel.SUCCESS,
                requestState.statusMessage ?: "实现计划已生成。",
                preserveLastMessageType = true,
            )
        }
    }

    /**
     * 异步生成实现计划，并把请求状态同步到前端。
     */
    fun requestGenerationPlanAsync() {
        val snapshot = session.snapshot()
        refreshDraftAndCodeState(snapshot)
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
        val payload = planningContextFactory.computePlanningPayload(snapshot)
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
                    payload = payload,
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
                            val normalizedPlan = resolvePlanResult(payload, runtimeResult)
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
     * 针对当前实现建议继续追问，不再跳回风险问答链路。
     */
    fun requestGenerationPlanDiscussion(
        question: String,
        focusItemId: String? = null,
    ) = runGenerationPlanDiscussion(
        question = question,
        focusItemId = focusItemId,
        mutateState = { result, requestState ->
            markGenerationPlanDiscussion(result, requestState)
        },
    )

    /**
     * 异步追问当前实现建议，并把请求状态同步到前端。
     */
    fun requestGenerationPlanDiscussionAsync(
        question: String,
        focusItemId: String? = null,
    ) {
        val normalizedQuestion = question.trim()
        if (normalizedQuestion.isEmpty()) {
            rejectGenerationPlanDiscussion(
                message = "请先输入你对实现建议的追问。",
                detailMessage = "实现建议追问不能为空。",
            )
            return
        }
        val snapshot = session.snapshot()
        refreshDraftAndCodeState(snapshot)
        val generationPlan = snapshot.generationPlan
        if (generationPlan == null) {
            rejectGenerationPlanDiscussion(
                message = "请先生成实现建议，再继续追问。",
                detailMessage = "实现建议追问依赖当前建议快照，当前还没有可讨论的实现建议。",
            )
            return
        }
        val requestId = asyncRequestLifecycle.beginGenerationPlanDiscussionRequest()
        val settings = settingsProvider()
        val presentation = asyncRequestLifecycle.buildAsyncRequestPresentation(
            requestId = requestId,
            sceneLabel = "实现建议追问",
            settings = settings,
        )
        val previewUpdater = if (presentation.requestState.streaming) {
            asyncRequestLifecycle.createStreamingPreviewUpdater(
                requestId,
                GraphEditorStateService::updateGenerationPlanDiscussionRequestPreview,
            )
        } else {
            null
        }
        session.mutateBatch {
            apply {
                beginGenerationPlanDiscussionRequest(presentation.requestState)
            }
            apply {
                markOperationFeedback(
                    OperationFeedbackLevel.INFO,
                    if (presentation.remoteRequested) {
                        if (presentation.streamingSupported) {
                            "已发起远程 LLM 实现建议追问请求，当前采用流式输出。"
                        } else {
                            "已发起远程 LLM 实现建议追问请求，当前采用完整返回。"
                        }
                    } else {
                        "正在追问当前实现建议，请稍候。"
                    },
                )
            }
        }
        asyncRequestLifecycle.logAsyncRequestEvent(logger, "started", presentation.requestState)
        asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = asyncRequestLifecycle::completeGenerationPlanDiscussionRequest,
            onTimeout = {
                val timedOutState = asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                asyncRequestLifecycle.logAsyncRequestEvent(logger, "timedOut", timedOutState)
                session.mutateBatch {
                    apply {
                        markGenerationPlanDiscussionRequestFailed(
                            timedOutState.errorMessage ?: "实现建议追问超时",
                            timedOutState,
                        )
                    }
                    apply {
                        markOperationFeedback(
                            OperationFeedbackLevel.ERROR,
                            timedOutState.errorMessage ?: "实现建议追问超时",
                        )
                    }
                }
            },
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                generationPlanDiscussionService.discuss(
                    context = planningPayloadToGenerationContext(
                        planningContextFactory.computePlanningPayload(
                            snapshot = snapshot,
                            generationPlanOverride = generationPlan,
                        ),
                    ),
                    plan = generationPlan,
                    question = normalizedQuestion,
                    settings = settings,
                    session = snapshot.generationPlanDiscussionSession,
                    focusItemId = focusItemId,
                    onPreview = previewUpdater,
                )
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed || !asyncRequestLifecycle.completeGenerationPlanDiscussionRequest(requestId)) {
                        return@invokeLater
                    }
                    result.fold(
                        onSuccess = { discussion ->
                            val requestState = asyncRequestLifecycle.buildSucceededRequestState(
                                presentation = presentation,
                                successMessage = "实现建议追问已更新。",
                                completedRemotely = discussion.source == LlmResultSource.REMOTE,
                                warnings = discussion.warnings,
                            )
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "succeeded", requestState)
                            val feedbackLevel = if (requestState.fallbackUsed) {
                                OperationFeedbackLevel.WARNING
                            } else {
                                OperationFeedbackLevel.SUCCESS
                            }
                            session.mutateBatch {
                                apply {
                                    markGenerationPlanDiscussion(discussion, requestState)
                                }
                                apply {
                                    markOperationFeedback(
                                        feedbackLevel,
                                        requestState.statusMessage ?: "实现建议追问已更新。",
                                        preserveLastMessageType = true,
                                    )
                                }
                            }
                        },
                        onFailure = { throwable ->
                            logger.warn("异步追问实现建议失败", throwable)
                            val message = "实现建议追问失败：${throwable.message ?: throwable.javaClass.simpleName}"
                            val requestState = asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                            session.mutateBatch {
                                apply {
                                    markGenerationPlanDiscussionRequestFailed(message, requestState)
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
        val codeDecision = refreshDraftAndCodeState(snapshot)
        rejectStageEligibility(codeDecision, "代码草稿") { message, requestState ->
            markCodeDraftRequestFailed(message, requestState)
        }?.let { return }
        rejectOrphanedGenerationPlan(snapshot, "代码草稿") { message, requestState ->
            markCodeDraftRequestFailed(message, requestState)
        }?.let { return }
        val result = executeCodegenRuntime(snapshot)
        val draftResult = result.output
        if (draftResult == null) {
            val failure = resolveCodegenRuntimeFailure(result.finalState)
            session.mutateBatch {
                apply {
                    markRuntimeArtifactSummaries("codegen", toRuntimeArtifactSummaries(result))
                }
                apply {
                    markCodeDraftRequestFailed(
                        failure.message,
                        asyncRequestLifecycle.withRuntimeMetadata(
                            requestState = GraphEditorStateService.AsyncRequestState.failed(
                                message = failure.message,
                                scene = "代码草稿",
                                detailMessage = failure.detailMessage,
                            ),
                            runtimeState = result.finalState,
                        ),
                    )
                }
                apply {
                    markOperationFeedback(
                        OperationFeedbackLevel.ERROR,
                        failure.message,
                        preserveLastMessageType = true,
                    )
                }
            }
            return
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
        val preparedDrafts = enrichDraftsWithPreparedEdits(draftResult.drafts)
        session.mutate {
            markRuntimeArtifactSummaries("codegen", toRuntimeArtifactSummaries(result))
            markGeneratedCodeDrafts(
                drafts = preparedDrafts,
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
        val codeDecision = refreshDraftAndCodeState(snapshot)
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
            val result: Result<PreparedCodegenRuntimeResult> = runCatching {
                val runtimeResult = executeCodegenRuntime(snapshot, previewUpdater)
                val preparedDrafts = runtimeResult.output
                    ?.drafts
                    ?.takeIf { drafts -> drafts.isNotEmpty() }
                    ?.let(::enrichDraftsWithPreparedEdits)
                PreparedCodegenRuntimeResult(runtimeResult, preparedDrafts)
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed || !asyncRequestLifecycle.completeCodeDraftRequest(requestId)) {
                        return@invokeLater
                    }
                    result.fold(
                        onSuccess = { preparedResult ->
                            val runtimeResult = preparedResult.runtimeResult
                            val drafts = runtimeResult.output
                            if (drafts == null) {
                                val failure = resolveCodegenRuntimeFailure(runtimeResult.finalState)
                                val requestState = asyncRequestLifecycle.withRuntimeMetadata(
                                    requestState = asyncRequestLifecycle.buildFailedRequestState(
                                        presentation = presentation,
                                        message = failure.message,
                                        detailMessageOverride = failure.detailMessage,
                                    ),
                                    runtimeState = runtimeResult.finalState,
                                )
                                asyncRequestLifecycle.logAsyncRequestEvent(logger, "failed", requestState)
                                session.mutateBatch {
                                    apply {
                                        markRuntimeArtifactSummaries("codegen", toRuntimeArtifactSummaries(runtimeResult))
                                    }
                                    apply {
                                        markCodeDraftRequestFailed(failure.message, requestState)
                                    }
                                    apply {
                                        markOperationFeedback(
                                            OperationFeedbackLevel.ERROR,
                                            failure.message,
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
                                        drafts = preparedResult.preparedDrafts ?: drafts.drafts,
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
        val draft = snapshot.generatedCodeDrafts.firstOrNull { it.id == draftId }
        if (draft == null) {
            session.mutate {
                markOperationFeedback(
                    OperationFeedbackLevel.ERROR,
                    "未找到代码草稿 '$draftId'，无法写入当前文件。",
                )
            }
            return
        }
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "开始写入单个代码草稿: draftId=$draftId, targetPath=${draft.targetPath}"
        }
        val report = codeDraftWriterService.writeDrafts(project.basePath, listOf(draft))
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "单个代码草稿写入完成: ${GenerationDiagnostics.summarizeWriteReport(report)}"
        }
        session.mutateBatch {
            apply {
                markGeneratedCodeDraftWriteReport(mergeWriteReport(snapshot.generatedCodeDraftWriteReport, report))
            }
            apply {
                val (level, message) = when {
                    report.writtenFiles.contains(draft.targetPath) -> OperationFeedbackLevel.SUCCESS to
                        "代码草稿已写入当前文件。"

                    report.warnings.isNotEmpty() -> OperationFeedbackLevel.WARNING to
                        report.warnings.first()

                    report.skippedFiles.contains(draft.targetPath) -> OperationFeedbackLevel.WARNING to
                        "当前文件未写入，请查看代码 diff 写入报告。"

                    else -> OperationFeedbackLevel.WARNING to
                        "当前文件未写入，请查看代码 diff 写入报告。"
                }
                markOperationFeedback(
                    level,
                    message,
                )
            }
        }
        report.writtenFiles.firstOrNull()?.let(sourceNavigationServiceProvider()::navigateToPath)
    }

    fun openCodeDraftNativeDiff(draftId: String) {
        val snapshot = session.snapshot()
        val draft = snapshot.generatedCodeDrafts.firstOrNull { it.id == draftId }
        if (draft == null) {
            session.mutate {
                markOperationFeedback(
                    OperationFeedbackLevel.ERROR,
                    "未找到代码草稿 '$draftId'，无法打开原生 diff。",
                )
            }
            return
        }
        val projectBasePath = project.basePath
        if (projectBasePath.isNullOrBlank()) {
            session.mutate {
                markOperationFeedback(
                    OperationFeedbackLevel.ERROR,
                    "项目根路径不可用，无法打开代码草稿 diff。",
                )
            }
            return
        }
        val normalizedDraft = ProjectPathNormalizer.normalizeDraft(draft, projectBasePath)
        val target = Path.of(projectBasePath).normalize().resolve(normalizedDraft.targetPath).normalize()
        val beforeText = when {
            Files.exists(target) -> Files.readString(target)
            normalizedDraft.content != null -> ""
            else -> null
        }
        if (beforeText == null) {
            session.mutate {
                markOperationFeedback(
                    OperationFeedbackLevel.ERROR,
                    "目标文件 '${normalizedDraft.targetPath}' 不存在，无法打开代码草稿 diff。",
                )
            }
            return
        }
        val afterText = when {
            normalizedDraft.editOperations.isNotEmpty() -> {
                val prepared = codeDraftWriterService.prepareExistingFileDraft(projectBasePath, normalizedDraft)
                if (!prepared.canApply) {
                    session.mutate {
                        markOperationFeedback(
                            OperationFeedbackLevel.ERROR,
                            prepared.warnings.firstOrNull() ?: "无法为代码草稿准备原生 diff。",
                        )
                    }
                    return
                }
                prepared.previewText
            }
            normalizedDraft.content != null -> normalizedDraft.content
            else -> {
                session.mutate {
                    markOperationFeedback(
                        OperationFeedbackLevel.ERROR,
                        "当前代码草稿没有可展示的 diff 内容。",
                    )
                }
                return
            }
        }
        ApplicationManager.getApplication().invokeLater(
            {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
                if (virtualFile == null) {
                    session.mutate {
                        markOperationFeedback(
                            OperationFeedbackLevel.ERROR,
                            "无法定位目标文件 '${normalizedDraft.targetPath}' 的 IDE VirtualFile，无法打开可写入 merge。",
                        )
                    }
                    return@invokeLater
                }
                val request = runCatching {
                    DiffRequestFactory.getInstance().createMergeRequest(
                        project,
                        virtualFile,
                        listOf(
                            beforeText.toByteArray(virtualFile.charset),
                            beforeText.toByteArray(virtualFile.charset),
                            afterText.toByteArray(virtualFile.charset),
                        ),
                        "代码草稿 Merge · ${normalizedDraft.title}",
                        listOf("当前文件", "当前基线", "生成预览"),
                    ) { result ->
                        when (result) {
                            MergeResult.CANCEL,
                            MergeResult.LEFT,
                            -> session.mutate {
                                markOperationFeedback(
                                    OperationFeedbackLevel.INFO,
                                    "已取消代码草稿 merge，当前文件未写入。",
                                    preserveLastMessageType = true,
                                )
                            }

                            MergeResult.RIGHT,
                            MergeResult.RESOLVED,
                            -> session.mutate {
                                markGeneratedCodeDraftWriteReport(
                                    mergeWriteReport(
                                        snapshot().generatedCodeDraftWriteReport,
                                        GeneratedCodeDraftWriteReport(
                                            writtenFiles = listOf(normalizedDraft.targetPath),
                                        ),
                                    ),
                                )
                                markOperationFeedback(
                                    OperationFeedbackLevel.SUCCESS,
                                    "代码草稿 merge 已写入当前文件。",
                                    preserveLastMessageType = true,
                                )
                            }
                        }
                    }
                }.getOrElse { error ->
                    session.mutate {
                        markOperationFeedback(
                            OperationFeedbackLevel.ERROR,
                            error.message ?: "无法为代码草稿创建可写入 merge 请求。",
                        )
                    }
                    return@invokeLater
                }
                showCodeDraftMergeRequest(project, request)
            },
            ModalityState.defaultModalityState(),
        )
    }

    private fun enrichDraftsWithPreparedEdits(drafts: List<GeneratedCodeDraft>): List<GeneratedCodeDraft> {
        val projectBasePath = project.basePath ?: return drafts
        return drafts.map { draft ->
            if (draft.editOperations.isEmpty()) {
                draft
            } else {
                val prepared = codeDraftWriterService.prepareExistingFileDraft(projectBasePath, draft)
                draft.copy(
                    preparedEdits = prepared.preparedEdits,
                    warnings = (draft.warnings + prepared.warnings).distinct(),
                )
            }
        }
    }

    private data class PreparedCodegenRuntimeResult(
        val runtimeResult: AgentRunResult<CodeGenerationResult>,
        val preparedDrafts: List<GeneratedCodeDraft>?,
    )

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

    private fun resolvePlanResult(
        payload: PlanningPayload,
        runtimeResult: AgentRunResult<GenerationPlan>,
    ): GenerationPlan {
        runtimeResult.output?.let { return it }
        val fallbackPlan = ProjectPathNormalizer.normalizePlan(
            planningContextFactory.buildPlanSnapshot(
                planningGraph = payload.planningGraph,
                diff = payload.diff,
                previewItems = payload.previewItems,
                snapshot = payload.snapshot,
                sourceContext = payload.sourceContext,
            ),
            project.basePath,
        )
        return fallbackPlan.copy(
            warnings = listOf(
                "实现计划 runtime 未返回结果，已直接基于当前草稿快照生成实现建议。",
            ) + fallbackPlan.warnings,
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

    private fun resolveCodegenRuntimeFailure(
        runtimeState: AgentRunState,
    ): RuntimeFailurePresentation {
        val lastStepSummary = runtimeState.stepRecords.lastOrNull()?.summary
        val message = when (lastStepSummary) {
            "validate-generated-drafts" ->
                "生成代码草稿失败：生成结果未通过本地安全校验。"
            "prevalidate-existing-file-targets" ->
                "生成代码草稿失败：目标范围未通过本地安全校验。"
            else -> when (runtimeState.failureReason) {
                com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason.EVIDENCE_INSUFFICIENT ->
                    "生成代码草稿失败：当前证据不足以形成安全代码 diff。"
                com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason.MAX_STEPS_EXCEEDED,
                com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason.MAX_FILES_READ_EXCEEDED,
                com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason.MAX_SNIPPETS_EXCEEDED,
                com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason.MAX_SNIPPET_LINES_EXCEEDED,
                com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason.MAX_TOTAL_SNIPPET_LINES_EXCEEDED,
                com.charmnight.linkgraph.llm.runtime.AgentRunFailureReason.MAX_RUNTIME_SECONDS_EXCEEDED ->
                    "生成代码草稿失败：runtime 预算已耗尽。"
                else -> "生成代码草稿失败：runtime 执行失败。"
            }
        }
        val detailMessage = runtimeState.lastModelOutput
            ?.trim()
            ?.takeIf { detail -> detail.isNotEmpty() && detail != message }
        return RuntimeFailurePresentation(
            message = message,
            detailMessage = detailMessage,
        )
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

    private fun runGenerationPlanDiscussion(
        question: String,
        focusItemId: String?,
        mutateState: GraphEditorStateService.(com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult, GraphEditorStateService.AsyncRequestState) -> Unit,
    ): com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult? {
        val normalizedQuestion = question.trim()
        if (normalizedQuestion.isEmpty()) {
            rejectGenerationPlanDiscussion(
                message = "请先输入你对实现建议的追问。",
                detailMessage = "实现建议追问不能为空。",
            )
            return null
        }
        val snapshot = session.snapshot()
        refreshDraftAndCodeState(snapshot)
        val generationPlan = snapshot.generationPlan
        if (generationPlan == null) {
            rejectGenerationPlanDiscussion(
                message = "请先生成实现建议，再继续追问。",
                detailMessage = "实现建议追问依赖当前建议快照，当前还没有可讨论的实现建议。",
            )
            return null
        }
        val result = generationPlanDiscussionService.discuss(
            context = planningPayloadToGenerationContext(
                planningContextFactory.computePlanningPayload(
                    snapshot = snapshot,
                    generationPlanOverride = generationPlan,
                ),
            ),
            plan = generationPlan,
            question = normalizedQuestion,
            settings = settingsProvider(),
            session = snapshot.generationPlanDiscussionSession,
            focusItemId = focusItemId,
        )
        val requestState = GraphEditorStateService.AsyncRequestState.succeeded(
            scene = "实现建议追问",
            statusMessage = "实现建议追问已更新。",
        )
        session.mutateBatch {
            apply {
                mutateState(result, requestState)
            }
            apply {
                markOperationFeedback(
                    level = if (result.warnings.isNotEmpty()) OperationFeedbackLevel.WARNING else OperationFeedbackLevel.SUCCESS,
                    message = if (result.warnings.isNotEmpty()) {
                        result.warnings.first()
                    } else {
                        "实现建议追问已更新。"
                    },
                    preserveLastMessageType = true,
                )
            }
        }
        return result
    }

    private fun rejectGenerationPlanDiscussion(
        message: String,
        detailMessage: String?,
    ) {
        val requestState = GraphEditorStateService.AsyncRequestState.failed(
            message = message,
            scene = "实现建议追问",
            detailMessage = detailMessage,
        )
        session.mutateBatch {
            apply {
                markGenerationPlanDiscussionRequestFailed(message, requestState)
            }
            apply {
                markOperationFeedback(
                    OperationFeedbackLevel.WARNING,
                    message,
                    preserveLastMessageType = true,
                )
            }
        }
    }

    private fun planningPayloadToGenerationContext(
        payload: PlanningPayload,
    ) = GenerationContext(
        graph = payload.planningGraph,
        mermaidIssues = payload.snapshot.mermaidIssues,
        diff = payload.diff,
        syncPreviewItems = payload.previewItems,
        confirmedChanges = payload.snapshot.draftWorkbenchState.draftChanges,
        sourceContext = payload.sourceContext,
    )

    private fun refreshDraftAndCodeState(
        snapshot: GraphEditorStateService.Snapshot,
    ): StageEligibilityDecision {
        val draftValidationState = riskResolutionService.evaluateDraftValidation(snapshot)
        val codeDecision = riskResolutionService.evaluateCodeEligibility(snapshot)
        session.mutateBatch {
            apply {
                markDraftValidationState(draftValidationState)
            }
            apply {
                markCodeEligibilityDecision(codeDecision)
            }
        }
        return codeDecision
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
