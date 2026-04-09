package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.codegen.CodeDraftWriterService
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.charmnight.linkgraph.codegen.GeneratedCodeDraft
import com.charmnight.linkgraph.codegen.GeneratedCodeDraftWriteReport
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
    /**
     * 基于当前规划上下文生成实现计划。
     */
    fun requestGenerationPlan() {
        val payload = planningContextFactory.computePlanningPayload(session.snapshot())
        val plan = planningContextFactory.buildPlanSnapshot(
            planningGraph = payload.planningGraph,
            diff = payload.diff,
            previewItems = payload.previewItems,
            snapshot = payload.snapshot,
        )
        session.mutate {
            markGenerationPlan(plan)
        }
    }

    /**
     * 异步生成实现计划，并把请求状态同步到前端。
     */
    fun requestGenerationPlanAsync() {
        val requestId = asyncRequestLifecycle.beginGenerationPlanRequest()
        val snapshot = session.snapshot()
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
                planningContextFactory.buildPlanSnapshot(
                    planningGraph = payload.planningGraph,
                    diff = payload.diff,
                    previewItems = payload.previewItems,
                    snapshot = payload.snapshot,
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
                            val requestState = asyncRequestLifecycle.buildSucceededRequestState(
                                presentation = presentation,
                                successMessage = "实现计划已生成。",
                                completedRemotely = plan.source == GenerationPlanSource.REMOTE,
                                warnings = plan.warnings,
                            )
                            asyncRequestLifecycle.logAsyncRequestEvent(logger, "succeeded", requestState)
                            val feedbackLevel = if (requestState.fallbackUsed) {
                                OperationFeedbackLevel.WARNING
                            } else {
                                OperationFeedbackLevel.SUCCESS
                            }
                            session.mutateBatch {
                                apply {
                                    markGenerationPlan(plan, requestState)
                                }
                                apply {
                                    markOperationFeedback(
                                        feedbackLevel,
                                        requestState.statusMessage ?: "实现计划已生成。",
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
        val payload = planningContextFactory.computePlanningPayload(session.snapshot())
        val plan = payload.snapshot.generationPlan ?: planningContextFactory.buildPlanSnapshot(
            planningGraph = payload.planningGraph,
            diff = payload.diff,
            previewItems = payload.previewItems,
            snapshot = payload.snapshot,
        )
        val result = codeGenerationService.generateDrafts(
            context = buildGenerationContext(payload),
            plan = plan,
            settings = settingsProvider(),
        )
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
        val requestId = asyncRequestLifecycle.beginCodeDraftRequest()
        val snapshot = session.snapshot()
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
                val plan = payload.snapshot.generationPlan ?: planningContextFactory.buildPlanSnapshot(
                    planningGraph = payload.planningGraph,
                    diff = payload.diff,
                    previewItems = payload.previewItems,
                    snapshot = payload.snapshot,
                    onPreview = previewUpdater,
                )
                codeGenerationService.generateDrafts(
                    context = buildGenerationContext(payload),
                    plan = plan,
                    settings = settings,
                    onPreview = previewUpdater,
                )
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed || !asyncRequestLifecycle.completeCodeDraftRequest(requestId)) {
                        return@invokeLater
                    }
                    result.fold(
                        onSuccess = { drafts ->
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
        val report = codeDraftWriterService.writeDrafts(project.basePath, snapshot.generatedCodeDrafts)
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
        val report = codeDraftWriterService.writeDrafts(project.basePath, listOf(draft))
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
}
