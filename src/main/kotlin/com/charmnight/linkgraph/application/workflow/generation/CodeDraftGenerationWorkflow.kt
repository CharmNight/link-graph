package com.charmnight.linkgraph.application.workflow.generation

import com.charmnight.linkgraph.application.model.toRiskResolutionSnapshot
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.codegen.CodeGenerationResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.ProjectPathNormalizer
import com.charmnight.linkgraph.agent.model.LlmResultSource
import com.charmnight.linkgraph.agent.artifact.ArtifactType
import com.charmnight.linkgraph.agent.artifact.PlanArtifact
import com.charmnight.linkgraph.agent.capability.CodegenCapability
import com.charmnight.linkgraph.agent.capability.CodegenCapabilityInput
import com.charmnight.linkgraph.agent.runtime.AgentRunResult
import com.charmnight.linkgraph.agent.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.agent.runtime.withRuntimeDeadlineTimeout
import com.charmnight.linkgraph.application.diagnostics.GenerationDiagnostics
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.result.GenerationRequestFailureResult
import com.charmnight.linkgraph.application.result.GenerationRequestScene
import com.charmnight.linkgraph.application.result.GenerationRequestStartedResult
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.usecase.GenerationUseCase
import com.charmnight.linkgraph.application.usecase.GenerationUseCaseResult
import com.charmnight.linkgraph.settings.LinkGraphSettingsState

/**
 * 代码草稿生成工作流：负责协调"代码草稿"这一异步任务的完整生命周期，
 * 涵盖前置校验、远程 LLM 请求调度、流式预览推送、超时处理、运行时结果归一化、
 * 草稿文件编辑预准备以及失败/成功事件分发。
 */
internal class CodeDraftGenerationWorkflow(
    /** 注入的依赖集合（项目、状态、日志、设置等） */
    private val dependencies: GenerationWorkflowDependencies,
) {
    /** 通用生成用例，用于把运行时结果与计划上下文归一化成可向上层汇报的结果对象 */
    private val useCase = GenerationUseCase(
        planSnapshotBuilder = { planningGraph, diff, previewItems, mermaidIssues, confirmedChanges, sourceContext, userGoal ->
            dependencies.planningContextFactory.buildPlanSnapshot(
                planningGraph = planningGraph,
                diff = diff,
                previewItems = previewItems,
                mermaidIssues = mermaidIssues,
                confirmedChanges = confirmedChanges,
                sourceContext = sourceContext,
                userGoal = userGoal,
            )
        },
        projectBasePathProvider = { dependencies.project.basePath },
    )

    /**
     * 异步发起一次代码草稿生成请求：
     * 1) 取最新编辑器快照并校验前置条件（草稿阶段、孤儿计划）。
     * 2) 申请 requestId，构造面向用户的状态展示并发出"已开始"事件。
     * 3) 根据设置决定是否走流式预览，并调度超时兜底。
     * 4) 在后台任务中真正执行 codegen 运行时，并把结果（成功/失败）翻译为应用事件。
     */
    fun requestCodeDraftsAsync() {
        val snapshot = dependencies.snapshotProvider.snapshot()
        val codeDecision = dependencies.refreshDraftAndCodeState(snapshot.toApplicationSnapshot().toRiskResolutionSnapshot())
        dependencies.rejectStageEligibility(codeDecision, "代码草稿")?.let { return }
        dependencies.rejectOrphanedGenerationPlan(snapshot, "代码草稿")?.let { return }
        val requestId = dependencies.asyncRequestLifecycle.beginCodeDraftRequest()
        val settings = dependencies.settingsProvider()
        val presentation = dependencies.asyncRequestLifecycle.buildAsyncRequestLifecycleResult(
            requestId = requestId,
            sceneLabel = "代码草稿生成",
            settings = settings,
        )
        val previewUpdater = if (presentation.requestState.streaming) {
            dependencies.asyncRequestLifecycle.createStreamingPreviewUpdater(
                requestId = requestId,
                isRequestActive = dependencies.asyncRequestLifecycle::isCodeDraftRequestActive,
                updatePreview = { _, previewText, finalizing ->
                    dependencies.emitGenerationStreamingPreview(
                        scene = GenerationRequestScene.CODE_DRAFT,
                        requestId = requestId,
                        previewText = previewText,
                        finalizingStructuredResult = finalizing,
                    )
                },
            )
        } else {
            null
        }
        dependencies.emit(
            GraphEditorApplicationEvent.GenerationRequestStarted(
                GenerationRequestStartedResult(
                    scene = GenerationRequestScene.CODE_DRAFT,
                    requestState = presentation.requestState,
                    clearRuntimeArtifactScene = "codegen",
                    statusMessage = if (presentation.remoteRequested) {
                        if (presentation.streamingSupported) {
                            "已发起远程 LLM 代码草稿请求，当前采用流式输出。"
                        } else {
                            "已发起远程 LLM 代码草稿请求，当前采用完整返回。"
                        }
                    } else {
                        "正在生成代码草稿，请稍候。"
                    },
                ),
            ),
        )
        dependencies.asyncRequestLifecycle.logAsyncRequestEvent(dependencies.logger, "started", presentation.requestState)
        dependencies.asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = dependencies.asyncRequestLifecycle::completeCodeDraftRequest,
            onTimeout = {
                val timedOutState = dependencies.asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                dependencies.asyncRequestLifecycle.logAsyncRequestEvent(dependencies.logger, "timedOut", timedOutState)
                dependencies.emit(
                    GraphEditorApplicationEvent.CodeDraftRequestFailed(
                        GenerationRequestFailureResult(
                            scene = "代码草稿生成",
                            message = timedOutState.errorMessage ?: "代码草稿生成超时",
                            requestState = timedOutState,
                        ),
                    ),
                )
            },
        )
        dependencies.asyncRequestLifecycle.runBackgroundTask(
            work = {
                val runtimeResult = executeCodegenRuntime(
                    snapshot = snapshot,
                    settings = settings,
                    onPreview = previewUpdater,
                )
                val preparedDrafts = runtimeResult.output
                    ?.drafts
                    ?.takeIf { drafts -> drafts.isNotEmpty() }
                    ?.let(::enrichDraftsWithPreparedEdits)
                PreparedCodegenRuntimeResult(runtimeResult, preparedDrafts)
            },
            onCompleted = { result ->
                if (dependencies.project.isDisposed || !dependencies.asyncRequestLifecycle.completeCodeDraftRequest(requestId)) {
                    return@runBackgroundTask
                }
                result.fold(
                    onSuccess = { preparedResult ->
                        val runtimeResult = preparedResult.runtimeResult
                        val drafts = runtimeResult.output
                        if (drafts != null) {
                            debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
                                "异步代码草稿生成完成: ${GenerationDiagnostics.summarizeCodeGenerationResult(drafts)}, " +
                                    "artifactCount=${runtimeResult.artifactSummaries.size}, filesRead=${runtimeResult.finalState.budget.filesRead}, " +
                                    "stepsUsed=${runtimeResult.finalState.budget.usedSteps}"
                            }
                        }
                        val requestState = if (drafts != null && drafts.drafts.isNotEmpty()) {
                            dependencies.asyncRequestLifecycle.withRuntimeMetadata(
                                requestState = dependencies.asyncRequestLifecycle.buildSucceededRequestState(
                                    presentation = presentation,
                                    successMessage = "代码草稿已生成。",
                                    completedRemotely = drafts.source == LlmResultSource.REMOTE,
                                    warnings = drafts.warnings,
                                ),
                                runtimeState = runtimeResult.finalState,
                            )
                        } else {
                            val failurePreview = useCase.resolveCodeDrafts(
                                runtimeResult = runtimeResult,
                                requestState = dependencies.asyncRequestLifecycle.buildFailedRequestState(
                                    presentation = presentation,
                                    message = "代码草稿生成失败。",
                                ),
                                runtimeArtifacts = emptyList(),
                            ) as GenerationUseCaseResult.CodeDraftFailed
                            dependencies.asyncRequestLifecycle.withRuntimeMetadata(
                                requestState = dependencies.asyncRequestLifecycle.buildFailedRequestState(
                                    presentation = presentation,
                                    message = failurePreview.presentation.message,
                                    detailMessageOverride = failurePreview.presentation.requestState.detailMessage,
                                ),
                                runtimeState = runtimeResult.finalState,
                            )
                        }
                        val resolved = useCase.resolveCodeDrafts(
                            runtimeResult = runtimeResult,
                            requestState = requestState,
                            runtimeArtifacts = dependencies.toRuntimeArtifactSummaries(runtimeResult),
                            preparedDrafts = preparedResult.preparedDrafts,
                        )
                        dependencies.asyncRequestLifecycle.logAsyncRequestEvent(
                            dependencies.logger,
                            when (resolved) {
                                is GenerationUseCaseResult.CodeDraftsReady -> "succeeded"
                                is GenerationUseCaseResult.CodeDraftFailed -> "failed"
                                else -> "completed"
                            },
                            when (resolved) {
                                is GenerationUseCaseResult.CodeDraftsReady -> resolved.presentation.requestState
                                is GenerationUseCaseResult.CodeDraftFailed -> resolved.presentation.requestState
                                else -> requestState
                            },
                        )
                        emitCodeDraftResult(resolved)
                    },
                    onFailure = { throwable ->
                        dependencies.logger.warn("异步生成代码草稿失败", throwable)
                        val message = "生成代码草稿失败：${throwable.message ?: throwable.javaClass.simpleName}"
                        val requestState = dependencies.asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                        dependencies.asyncRequestLifecycle.logAsyncRequestEvent(dependencies.logger, "failed", requestState)
                        dependencies.emit(
                            GraphEditorApplicationEvent.CodeDraftRequestFailed(
                                GenerationRequestFailureResult(
                                    scene = "代码草稿",
                                    message = message,
                                    requestState = requestState,
                                    runtimeArtifacts = emptyList(),
                                ),
                            ),
                        )
                    },
                )
            },
        )
    }

    /** 把归一化后的生成用例结果翻译为对应的应用事件并向上层广播 */
    private fun emitCodeDraftResult(result: GenerationUseCaseResult) {
        when (result) {
            is GenerationUseCaseResult.CodeDraftsReady -> dependencies.emit(
                GraphEditorApplicationEvent.GeneratedCodeDraftsReady(result.presentation),
            )
            is GenerationUseCaseResult.CodeDraftFailed -> dependencies.emit(
                GraphEditorApplicationEvent.CodeDraftRequestFailed(result.presentation),
            )
            is GenerationUseCaseResult.PlanReady -> Unit
        }
    }
    /** 对包含编辑操作的草稿做"已存在文件"的预准备，把行号/上下文对齐成可执行的 preparedEdits，并合并额外告警 */
    private fun enrichDraftsWithPreparedEdits(drafts: List<GeneratedCodeDraft>): List<GeneratedCodeDraft> {
        val projectBasePath = dependencies.project.basePath ?: return drafts
        return drafts.map { draft ->
            if (draft.command !is com.charmnight.linkgraph.codegen.CodeDraftCommand.PatchExistingFile) {
                draft
            } else {
                val prepared = dependencies.codeDraftWriterService.prepareExistingFileDraft(projectBasePath, draft)
                draft.copy(
                    preparedEdits = prepared.preparedEdits,
                    warnings = (draft.warnings + prepared.warnings).distinct(),
                )
            }
        }
    }

    /**
     * 真正驱动 codegen 运行时：通过 agentRunCoordinator 把生成能力（codegen）跑在受限的运行时上下文中，
     * 并把生成结果进行路径规范化、记录运行时追踪日志。
     */
    private fun executeCodegenRuntime(
        snapshot: WorkflowEditorSnapshot,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): AgentRunResult<CodeGenerationResult> {
        val runtimeResult = dependencies.agentRunCoordinator.run(
            capability = dependencies.codegenCapabilityFactory(
                CodegenCapability.CodegenExecutor { input, runtimeContext, _ ->
                    ProjectPathNormalizer.normalizeDraftResult(
                        dependencies.codeGenerationService.generateDrafts(
                            context = input.generationContext,
                            plan = input.plan,
                            settings = settings.withRuntimeDeadlineTimeout(runtimeContext),
                            onPreview = onPreview,
                        ),
                        dependencies.project.basePath,
                    )
                },
            ),
            input = buildCodegenCapabilityInput(snapshot, settings),
            runtimeContext = AgentRuntimeContext(
                project = dependencies.project,
                snapshotSupplier = dependencies.toolGraphSnapshotProvider::snapshot,
                artifactStore = dependencies.artifactStoreProvider(),
            ),
        )
        dependencies.asyncRequestLifecycle.logRuntimeTrace(dependencies.logger, runtimeResult.finalState)
        return runtimeResult
    }

    /**
     * 构造 codegen 能力输入：从快照中取出实现计划并校验对应的 PlanArtifact 是否存在，
     * 再以规范化后的计划重算规划上下文载荷，最终装配成 CodegenCapabilityInput。
     */
    private fun buildCodegenCapabilityInput(
        snapshot: WorkflowEditorSnapshot,
        settings: LinkGraphSettingsState,
    ): CodegenCapabilityInput {
        // 先把快照中的计划路径规范化，确保与 artifactStore 中存放的 PlanArtifact 可比对
        val snapshotPlan = snapshot.generationPlan?.let { rawPlan ->
            ProjectPathNormalizer.normalizePlan(rawPlan, dependencies.project.basePath)
        }
        // 在 artifactStore 中匹配同一份计划，缺失则说明计划与工件不一致，需要重新生成计划
        val plan = snapshotPlan?.let { normalizedPlan ->
            dependencies.artifactStoreProvider()
                .byType(ArtifactType.PLAN)
                .filterIsInstance<PlanArtifact>()
                .lastOrNull { artifact -> artifact.plan == normalizedPlan }
                ?.plan
                ?: error("当前实现计划缺少对应的 PlanArtifact，请重新生成实现计划后再生成代码草稿。")
        }
        // 基于已对齐的计划重算规划上下文载荷，避免计划与上下文漂移
        val generationPayload = dependencies.planningContextFactory.computePlanningPayload(
            snapshot,
            generationPlanOverride = plan,
        )
        return CodegenCapabilityInput(
            generationContext = dependencies.buildGenerationContext(generationPayload),
            plan = plan,
            settings = settings,
        )
    }

    /** 后台任务内部承载：原始运行时结果 + 经过 preparedEdits 增强的草稿（草稿为空时为 null） */
    private data class PreparedCodegenRuntimeResult(
        /** codegen agent 运行结果（含状态、工件、输出） */
        val runtimeResult: AgentRunResult<CodeGenerationResult>,
        /** 经过 preparedEdits 增强后的草稿列表；运行未产出草稿时为 null */
        val preparedDrafts: List<GeneratedCodeDraft>?,
    )
}
