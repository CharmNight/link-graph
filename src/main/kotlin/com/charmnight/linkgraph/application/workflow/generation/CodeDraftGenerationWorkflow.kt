package com.charmnight.linkgraph.application.workflow.generation

import com.charmnight.linkgraph.application.model.toRiskResolutionSnapshot
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.codegen.CodeGenerationResult
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.ProjectPathNormalizer
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.artifact.ArtifactType
import com.charmnight.linkgraph.llm.artifact.PlanArtifact
import com.charmnight.linkgraph.llm.capability.CodegenCapability
import com.charmnight.linkgraph.llm.capability.CodegenCapabilityInput
import com.charmnight.linkgraph.llm.runtime.AgentRunResult
import com.charmnight.linkgraph.llm.runtime.AgentRuntimeContext
import com.charmnight.linkgraph.llm.runtime.withRuntimeDeadlineTimeout
import com.charmnight.linkgraph.application.diagnostics.GenerationDiagnostics
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.application.port.GenerationRequestFailurePresentation
import com.charmnight.linkgraph.application.port.GenerationRequestScene
import com.charmnight.linkgraph.application.port.GenerationRequestStartedPresentation
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.usecase.GenerationUseCase
import com.charmnight.linkgraph.application.usecase.GenerationUseCaseResult

internal class CodeDraftGenerationWorkflow(
    private val dependencies: GenerationWorkflowDependencies,
) {
    private val useCase = GenerationUseCase(
        planSnapshotBuilder = { planningGraph, diff, previewItems, mermaidIssues, confirmedChanges, sourceContext ->
            dependencies.planningContextFactory.buildPlanSnapshot(
                planningGraph = planningGraph,
                diff = diff,
                previewItems = previewItems,
                mermaidIssues = mermaidIssues,
                confirmedChanges = confirmedChanges,
                sourceContext = sourceContext,
            )
        },
        projectBasePathProvider = { dependencies.project.basePath },
    )

    fun requestCodeDrafts() {
        val snapshot = dependencies.snapshotProvider.snapshot()
        val codeDecision = dependencies.refreshDraftAndCodeState(snapshot.toApplicationSnapshot().toRiskResolutionSnapshot())
        dependencies.rejectStageEligibility(codeDecision, "代码草稿")?.let { return }
        dependencies.rejectOrphanedGenerationPlan(snapshot, "代码草稿")?.let { return }
        val result = executeCodegenRuntime(snapshot)
        val draftResult = result.output
        if (draftResult != null) {
            debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
                "代码草稿生成完成: ${GenerationDiagnostics.summarizeCodeGenerationResult(draftResult)}, " +
                    "artifactCount=${result.artifactSummaries.size}, filesRead=${result.finalState.budget.filesRead}, " +
                    "stepsUsed=${result.finalState.budget.usedSteps}"
            }
        }
        val requestState = dependencies.asyncRequestLifecycle.withRuntimeMetadata(
            requestState = if (draftResult != null && draftResult.drafts.isNotEmpty()) {
                AsyncRequestState.succeeded(
                    scene = "代码草稿",
                    statusMessage = "代码草稿已生成。",
                )
            } else {
                AsyncRequestState.failed(
                    message = "代码草稿生成失败。",
                    scene = "代码草稿",
                )
            },
            runtimeState = result.finalState,
        )
        emitCodeDraftResult(
            useCase.resolveCodeDrafts(
                runtimeResult = result,
                requestState = requestState,
                runtimeArtifacts = dependencies.toRuntimeArtifactSummaries(result),
                preparedDrafts = draftResult?.drafts?.takeIf { drafts -> drafts.isNotEmpty() }?.let(::enrichDraftsWithPreparedEdits),
            ),
        )
    }

    fun requestCodeDraftsAsync() {
        val snapshot = dependencies.snapshotProvider.snapshot()
        val codeDecision = dependencies.refreshDraftAndCodeState(snapshot.toApplicationSnapshot().toRiskResolutionSnapshot())
        dependencies.rejectStageEligibility(codeDecision, "代码草稿")?.let { return }
        dependencies.rejectOrphanedGenerationPlan(snapshot, "代码草稿")?.let { return }
        val requestId = dependencies.asyncRequestLifecycle.beginCodeDraftRequest()
        val settings = dependencies.settingsProvider()
        val presentation = dependencies.asyncRequestLifecycle.buildAsyncRequestPresentation(
            requestId = requestId,
            sceneLabel = "代码草稿生成",
            settings = settings,
        )
        val previewUpdater = if (presentation.requestState.streaming) {
            dependencies.asyncRequestLifecycle.createStreamingPreviewUpdater(
                requestId,
                { _, previewText, finalizing ->
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
                GenerationRequestStartedPresentation(
                    scene = GenerationRequestScene.CODE_DRAFT,
                    requestState = presentation.requestState,
                    clearRuntimeArtifactScene = "codegen",
                    feedbackMessage = if (presentation.remoteRequested) {
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
                        GenerationRequestFailurePresentation(
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
                val runtimeResult = executeCodegenRuntime(snapshot, previewUpdater)
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
                                GenerationRequestFailurePresentation(
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
    private fun enrichDraftsWithPreparedEdits(drafts: List<GeneratedCodeDraft>): List<GeneratedCodeDraft> {
        val projectBasePath = dependencies.project.basePath ?: return drafts
        return drafts.map { draft ->
            if (draft.editOperations.isEmpty()) {
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

    private fun executeCodegenRuntime(
        snapshot: WorkflowEditorSnapshot,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): AgentRunResult<CodeGenerationResult> {
        val runtimeResult = dependencies.agentRunCoordinator.run(
            capability = dependencies.codegenCapabilityFactory(
                CodegenCapability.CodegenExecutor { input, runtimeContext, _ ->
                    ProjectPathNormalizer.normalizeDraftResult(
                        dependencies.codeGenerationService.generateDrafts(
                            context = input.generationContext,
                            plan = input.plan,
                            settings = dependencies.settingsProvider().withRuntimeDeadlineTimeout(runtimeContext),
                            onPreview = onPreview,
                        ),
                        dependencies.project.basePath,
                    )
                },
            ),
            input = buildCodegenCapabilityInput(snapshot),
            runtimeContext = AgentRuntimeContext(
                project = dependencies.project,
                snapshotSupplier = dependencies.toolGraphSnapshotProvider::snapshot,
                artifactStore = dependencies.artifactStoreProvider(),
            ),
        )
        dependencies.asyncRequestLifecycle.logRuntimeTrace(dependencies.logger, runtimeResult.finalState)
        return runtimeResult
    }

    private fun buildCodegenCapabilityInput(
        snapshot: WorkflowEditorSnapshot,
    ): CodegenCapabilityInput {
        val snapshotPlan = snapshot.generationPlan?.let { rawPlan ->
            ProjectPathNormalizer.normalizePlan(rawPlan, dependencies.project.basePath)
        }
        val plan = snapshotPlan?.let { normalizedPlan ->
            dependencies.artifactStoreProvider()
                .byType(ArtifactType.PLAN)
                .filterIsInstance<PlanArtifact>()
                .lastOrNull { artifact -> artifact.plan == normalizedPlan }
                ?.plan
                ?: error("当前实现计划缺少对应的 PlanArtifact，请重新生成实现计划后再生成代码草稿。")
        }
        val generationPayload = dependencies.planningContextFactory.computePlanningPayload(
            snapshot,
            generationPlanOverride = plan,
        )
        return CodegenCapabilityInput(
            generationContext = dependencies.buildGenerationContext(generationPayload),
            plan = plan,
        )
    }

    private data class PreparedCodegenRuntimeResult(
        val runtimeResult: AgentRunResult<CodeGenerationResult>,
        val preparedDrafts: List<GeneratedCodeDraft>?,
    )
}
