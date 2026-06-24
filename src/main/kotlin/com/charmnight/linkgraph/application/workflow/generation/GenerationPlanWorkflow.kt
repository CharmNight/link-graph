package com.charmnight.linkgraph.application.workflow.generation

import com.charmnight.linkgraph.application.model.PlanningInput
import com.charmnight.linkgraph.application.model.toRiskResolutionSnapshot
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.capability.PlanCapability
import com.charmnight.linkgraph.llm.capability.PlanCapabilityInput
import com.charmnight.linkgraph.llm.runtime.AgentRunResult
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.llm.runtime.withRuntimeDeadlineTimeout
import com.charmnight.linkgraph.application.diagnostics.GenerationDiagnostics
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.application.model.AsyncRequestExecutionMode
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.result.GenerationRequestFailureResult
import com.charmnight.linkgraph.application.result.GenerationRequestScene
import com.charmnight.linkgraph.application.result.GenerationRequestStartedResult
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.usecase.GenerationUseCase
import com.charmnight.linkgraph.application.usecase.GenerationUseCaseResult
import com.charmnight.linkgraph.codegen.ProjectPathNormalizer
import com.charmnight.linkgraph.settings.LinkGraphSettingsState

/**
 * 实现计划生成工作流：协调快照采集、异步请求生命周期、远程/规则化生成与事件分发，
 * 是"用户触发实现计划生成"这一交互在应用层的编排入口。
 */
internal class GenerationPlanWorkflow(
    private val dependencies: GenerationWorkflowDependencies,
) {
    // 实现计划相关的用例，负责把计划快照构造与项目路径提供桥接到生成逻辑
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
     * 异步发起实现计划生成请求：
     * 刷新草稿/代码状态，登记请求并发出"开始"事件，必要时启动流式预览推送，
     * 调度超时守护任务并在后台真正执行生成逻辑，最终按成功/失败分别发出对应事件。
     */
    fun requestGenerationPlanAsync(userGoal: String = "") {
        val snapshot = dependencies.snapshotProvider.snapshot()
        dependencies.refreshDraftAndCodeState(snapshot.toApplicationSnapshot().toRiskResolutionSnapshot())
        val requestId = dependencies.asyncRequestLifecycle.beginGenerationPlanRequest()
        val effectiveSettings = dependencies.settingsProvider()
        val presentation = dependencies.asyncRequestLifecycle.buildAsyncRequestLifecycleResult(
            requestId = requestId,
            sceneLabel = "实现计划生成",
            settings = effectiveSettings,
            disabledMode = AsyncRequestExecutionMode.DISABLED,
        )
        val previewUpdater = if (presentation.requestState.streaming) {
            dependencies.asyncRequestLifecycle.createStreamingPreviewUpdater(
                requestId,
                { _, previewText, finalizing ->
                    dependencies.emitGenerationStreamingPreview(
                        scene = GenerationRequestScene.PLAN,
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
                    scene = GenerationRequestScene.PLAN,
                    requestState = presentation.requestState,
                    clearRuntimeArtifactScene = "plan",
                    statusMessage = if (presentation.remoteRequested) {
                        if (presentation.streamingSupported) {
                            "已发起远程 LLM 实现计划请求，当前采用流式输出。"
                        } else {
                            "已发起远程 LLM 实现计划请求，当前采用完整返回。"
                        }
                    } else {
                        "正在生成实现计划，请稍候。"
                    },
                ),
            ),
        )
        dependencies.asyncRequestLifecycle.logAsyncRequestEvent(dependencies.logger, "started", presentation.requestState)
        val payload = dependencies.planningContextFactory.computePlanningPayload(
            snapshot = snapshot,
            userGoal = userGoal,
        )
        dependencies.asyncRequestLifecycle.scheduleAsyncRequestTimeout(
            requestId = requestId,
            timeoutMillis = presentation.timeoutMillis,
            completeRequest = dependencies.asyncRequestLifecycle::completeGenerationPlanRequest,
            onTimeout = {
                val timedOutState = dependencies.asyncRequestLifecycle.buildTimedOutRequestState(presentation)
                dependencies.asyncRequestLifecycle.logAsyncRequestEvent(dependencies.logger, "timedOut", timedOutState)
                dependencies.emit(
                    GraphEditorApplicationEvent.GenerationPlanRequestFailed(
                        GenerationRequestFailureResult(
                            scene = "实现计划生成",
                            message = timedOutState.errorMessage ?: "实现计划生成超时",
                            requestState = timedOutState,
                        ),
                    ),
                )
            },
        )
        dependencies.asyncRequestLifecycle.runBackgroundTask(
            work = {
                executePlanRuntime(
                    payload = payload,
                    settings = effectiveSettings,
                    onPreview = previewUpdater,
                )
            },
            onCompleted = { result ->
                if (dependencies.project.isDisposed || !dependencies.asyncRequestLifecycle.completeGenerationPlanRequest(requestId)) {
                    return@runBackgroundTask
                }
                result.fold(
                    onSuccess = { runtimeResult ->
                        val previewPlan = runtimeResult.output ?: useCase.resolvePlan(
                            payload = payload,
                            runtimeResult = runtimeResult,
                            requestState = presentation.requestState,
                            runtimeArtifacts = emptyList(),
                        ).presentation.plan
                        debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
                            "异步实现计划生成完成: ${GenerationDiagnostics.summarizePlan(previewPlan)}, " +
                                "artifactCount=${runtimeResult.artifactSummaries.size}, filesRead=${runtimeResult.finalState.budget.filesRead}, " +
                                "stepsUsed=${runtimeResult.finalState.budget.usedSteps}"
                        }
                        val requestState = dependencies.asyncRequestLifecycle.withRuntimeMetadata(
                            requestState = dependencies.asyncRequestLifecycle.buildSucceededRequestState(
                                presentation = presentation,
                                successMessage = "实现计划已生成。",
                                completedRemotely = previewPlan.source == GenerationPlanSource.REMOTE,
                                warnings = previewPlan.warnings,
                            ),
                            runtimeState = runtimeResult.finalState,
                        )
                        val resolved = useCase.resolvePlan(
                            payload = payload,
                            runtimeResult = runtimeResult,
                            requestState = requestState,
                            runtimeArtifacts = dependencies.toRuntimeArtifactSummaries(runtimeResult),
                        )
                        dependencies.asyncRequestLifecycle.logAsyncRequestEvent(dependencies.logger, "succeeded", resolved.presentation.requestState)
                        dependencies.emit(
                            GraphEditorApplicationEvent.GenerationPlanReady(
                                resolved.presentation,
                            ),
                        )
                    },
                    onFailure = { throwable ->
                        dependencies.logger.warn("异步生成计划失败", throwable)
                        val message = "生成计划失败：${throwable.message ?: throwable.javaClass.simpleName}"
                        val requestState = dependencies.asyncRequestLifecycle.buildFailedRequestState(presentation, message)
                        dependencies.asyncRequestLifecycle.logAsyncRequestEvent(dependencies.logger, "failed", requestState)
                        dependencies.emit(
                            GraphEditorApplicationEvent.GenerationPlanRequestFailed(
                                GenerationRequestFailureResult(
                                    scene = "实现计划生成",
                                    message = message,
                                    requestState = requestState,
                                ),
                            ),
                        )
                    },
                )
            },
        )
    }

    /**
     * 在后台真正执行实现计划生成：构造计划执行能力并基于运行时上下文调用代理运行协调器，
     * 把路径归一化后的计划作为结果返回，并在结束后输出运行时追踪日志。
     */
    private fun executePlanRuntime(
        payload: PlanningInput,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): AgentRunResult<GenerationPlan> {
        val runtimeResult = dependencies.agentRunCoordinator.run(
            capability = dependencies.planCapabilityFactory(
                PlanCapability.PlanExecutor { input, runtimeContext, _ ->
                    ProjectPathNormalizer.normalizePlan(
                        dependencies.planningContextFactory.buildPlanSnapshot(
                            planningGraph = input.planningPayload.planningGraph,
                            diff = input.planningPayload.diff,
                            previewItems = input.planningPayload.previewItems,
                            mermaidIssues = input.planningPayload.mermaidIssues,
                            confirmedChanges = input.planningPayload.confirmedChanges,
                            sourceContext = input.planningPayload.sourceContext,
                            userGoal = input.planningPayload.userGoal,
                            onPreview = onPreview,
                            settingsOverride = settings.withRuntimeDeadlineTimeout(runtimeContext),
                        ),
                        dependencies.project.basePath,
                    )
                },
            ),
            input = PlanCapabilityInput(
                planningPayload = payload,
                settings = settings,
            ),
            runtimeContext = AgentRuntimeContext(
                project = dependencies.project,
                snapshotSupplier = dependencies.toolGraphSnapshotProvider::snapshot,
                artifactStore = dependencies.artifactStoreProvider(),
            ),
        )
        dependencies.asyncRequestLifecycle.logRuntimeTrace(dependencies.logger, runtimeResult.finalState)
        return runtimeResult
    }

}
