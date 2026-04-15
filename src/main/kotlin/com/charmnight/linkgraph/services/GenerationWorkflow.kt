package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.codegen.CodeDraftWriterService
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
import com.charmnight.linkgraph.codegen.ProjectPathNormalizer
import com.charmnight.linkgraph.codegen.emptyResultDetailMessage
import com.charmnight.linkgraph.codegen.emptyResultMessage
import com.charmnight.linkgraph.llm.GenerationContext
import com.charmnight.linkgraph.llm.GenerationPlanSource
import com.charmnight.linkgraph.llm.GraphGenerationService
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.GraphEditorStateService.OperationFeedbackLevel
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
        rejectMissingConfirmedDraftChanges(snapshot, "实现计划") { message, requestState ->
            markGenerationPlanRequestFailed(message, requestState)
        }?.let { return }
        val payload = planningContextFactory.computePlanningPayload(snapshot)
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "开始生成实现计划: ${GenerationDiagnostics.summarizePlanningPayload(payload)}"
        }
        val plan = ProjectPathNormalizer.normalizePlan(
            planningContextFactory.buildPlanSnapshot(
            planningGraph = payload.planningGraph,
            diff = payload.diff,
            previewItems = payload.previewItems,
            snapshot = payload.snapshot,
            sourceContext = payload.sourceContext,
            ),
            project.basePath,
        )
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "实现计划生成完成: ${GenerationDiagnostics.summarizePlan(plan)}"
        }
        session.mutate {
            markGenerationPlan(plan)
        }
    }

    /**
     * 异步生成实现计划，并把请求状态同步到前端。
     */
    fun requestGenerationPlanAsync() {
        val snapshot = session.snapshot()
        rejectMissingConfirmedDraftChanges(snapshot, "实现计划") { message, requestState ->
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
                val payload = planningContextFactory.computePlanningPayload(snapshot)
                debugLazy(logger.isDebugEnabled, logger::debug) {
                    "开始异步生成实现计划: ${GenerationDiagnostics.summarizePlanningPayload(payload)}"
                }
                planningContextFactory.buildPlanSnapshot(
                    planningGraph = payload.planningGraph,
                    diff = payload.diff,
                    previewItems = payload.previewItems,
                    snapshot = payload.snapshot,
                    sourceContext = payload.sourceContext,
                    onPreview = previewUpdater,
                )
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed || !asyncRequestLifecycle.completeGenerationPlanRequest(requestId)) {
                        return@invokeLater
                    }
                    result.fold(
                        onSuccess = { plan ->
                            val normalizedPlan = ProjectPathNormalizer.normalizePlan(plan, project.basePath)
                            debugLazy(logger.isDebugEnabled, logger::debug) {
                                "异步实现计划生成完成: ${GenerationDiagnostics.summarizePlan(normalizedPlan)}"
                            }
                            val requestState = asyncRequestLifecycle.buildSucceededRequestState(
                                presentation = presentation,
                                successMessage = "实现计划已生成。",
                                completedRemotely = normalizedPlan.source == GenerationPlanSource.REMOTE,
                                warnings = normalizedPlan.warnings,
                            )
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "succeeded", requestState)
                            val feedbackLevel = if (requestState.fallbackUsed) {
                                OperationFeedbackLevel.WARNING
                            } else {
                                OperationFeedbackLevel.SUCCESS
                            }
                            session.mutateBatch {
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
        rejectMissingConfirmedDraftChanges(snapshot, "代码草稿") { message, requestState ->
            markCodeDraftRequestFailed(message, requestState)
        }?.let { return }
        val payload = planningContextFactory.computePlanningPayload(snapshot)
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "开始生成代码草稿: ${GenerationDiagnostics.summarizePlanningPayload(payload)}"
        }
        val rawPlan = payload.snapshot.generationPlan ?: planningContextFactory.buildPlanSnapshot(
            planningGraph = payload.planningGraph,
            diff = payload.diff,
            previewItems = payload.previewItems,
            snapshot = payload.snapshot,
            sourceContext = payload.sourceContext,
        )
        val plan = ProjectPathNormalizer.normalizePlan(rawPlan, project.basePath)
        val generationPayload = if (payload.snapshot.generationPlan == plan) {
            payload
        } else {
            planningContextFactory.computePlanningPayload(payload.snapshot, generationPlanOverride = plan)
        }
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "代码草稿生成使用计划: ${GenerationDiagnostics.summarizePlan(plan)}"
        }
        val result = ProjectPathNormalizer.normalizeDraftResult(
            codeGenerationService.generateDrafts(
            context = buildGenerationContext(generationPayload),
            plan = plan,
            settings = settingsProvider(),
            ),
            project.basePath,
        )
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "代码草稿生成完成: ${GenerationDiagnostics.summarizeCodeGenerationResult(result)}"
        }
        if (result.drafts.isEmpty()) {
            val message = result.emptyResultMessage()
            session.mutateBatch {
                apply {
                    markCodeDraftRequestFailed(
                        message,
                        GraphEditorStateService.AsyncRequestState.failed(
                            message = message,
                            detailMessage = result.emptyResultDetailMessage(),
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
            markGeneratedCodeDrafts(
                drafts = result.drafts,
                warnings = result.warnings,
                source = result.source,
                promptPreview = result.promptPreview,
            )
        }
    }

    /**
     * 异步生成代码草稿，并把请求状态同步到前端。
     */
    fun requestCodeDraftsAsync() {
        val snapshot = session.snapshot()
        rejectMissingConfirmedDraftChanges(snapshot, "代码草稿") { message, requestState ->
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
                val payload = planningContextFactory.computePlanningPayload(snapshot)
                debugLazy(logger.isDebugEnabled, logger::debug) {
                    "开始异步生成代码草稿: ${GenerationDiagnostics.summarizePlanningPayload(payload)}"
                }
                val rawPlan = payload.snapshot.generationPlan ?: planningContextFactory.buildPlanSnapshot(
                    planningGraph = payload.planningGraph,
                    diff = payload.diff,
                    previewItems = payload.previewItems,
                    snapshot = payload.snapshot,
                    sourceContext = payload.sourceContext,
                    onPreview = previewUpdater,
                )
                val plan = ProjectPathNormalizer.normalizePlan(rawPlan, project.basePath)
                val generationPayload = if (payload.snapshot.generationPlan == plan) {
                    payload
                } else {
                    planningContextFactory.computePlanningPayload(payload.snapshot, generationPlanOverride = plan)
                }
                debugLazy(logger.isDebugEnabled, logger::debug) {
                    "异步代码草稿生成使用计划: ${GenerationDiagnostics.summarizePlan(plan)}"
                }
                ProjectPathNormalizer.normalizeDraftResult(
                    codeGenerationService.generateDrafts(
                    context = buildGenerationContext(generationPayload),
                    plan = plan,
                    settings = settings,
                    onPreview = previewUpdater,
                    ),
                    project.basePath,
                )
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed || !asyncRequestLifecycle.completeCodeDraftRequest(requestId)) {
                        return@invokeLater
                    }
                    result.fold(
                        onSuccess = { drafts ->
                            debugLazy(logger.isDebugEnabled, logger::debug) {
                                "异步代码草稿生成完成: ${GenerationDiagnostics.summarizeCodeGenerationResult(drafts)}"
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
                            val requestState = asyncRequestLifecycle.buildSucceededRequestState(
                                presentation = presentation,
                                successMessage = "代码草稿已生成。",
                                completedRemotely = drafts.source == LlmResultSource.REMOTE,
                                warnings = drafts.warnings,
                            )
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "succeeded", requestState)
                            val feedbackLevel = if (requestState.fallbackUsed) {
                                OperationFeedbackLevel.WARNING
                            } else {
                                OperationFeedbackLevel.SUCCESS
                            }
                            session.mutateBatch {
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
            confirmedChanges = payload.snapshot.draftWorkbenchState.draftChanges,
            sourceContext = payload.sourceContext,
        )
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

    private fun rejectMissingConfirmedDraftChanges(
        snapshot: GraphEditorStateService.Snapshot,
        scene: String,
        rejectRequest: GraphEditorStateService.(String, GraphEditorStateService.AsyncRequestState) -> Unit,
    ): GenerationPrerequisiteFailure? {
        if (snapshot.draftWorkbenchState.draftChanges.isNotEmpty()) {
            return null
        }
        val failure = GenerationPrerequisiteFailure(
            scene = scene,
            message = "生成${scene}前请先确认至少一条草稿变更。",
            detailMessage = "当前草稿层为空。先在审计结果中确认候选变更，使草稿层承载已确认的修改目标，再继续生成。",
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
