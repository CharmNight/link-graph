package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandleFactory
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.charmnight.linkgraph.semantic.subject.canonicalTypeText
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.application.workflow.CurrentSubjectGraphRequestTracker
import com.charmnight.linkgraph.application.debug.DebugMethodSignatureLocator
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.application.planning.PlanningContextFactory
import com.charmnight.linkgraph.application.model.currentVisibleGraph
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.application.model.findNavigationNode
import com.charmnight.linkgraph.application.port.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.port.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.workflow.subject.AnalysisExecutionResult
import com.charmnight.linkgraph.application.workflow.subject.AnalysisOutcomeAsyncResult
import com.charmnight.linkgraph.application.workflow.subject.CurrentMethodNode
import com.charmnight.linkgraph.application.workflow.subject.InteractiveProjectionSettings
import com.charmnight.linkgraph.application.usecase.CurrentMethodNodeInput
import com.charmnight.linkgraph.application.usecase.SubjectGraphUseCase
import com.charmnight.linkgraph.application.usecase.SubjectGraphUseCaseResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.concurrency.CancellablePromise

/**
 * 统一处理当前主体定位、语义分析、当前方法补图与溢出展开。
 */
internal class SubjectGraphWorkflow(
    /** 当前项目。 */
    private val project: Project,
    private val snapshotProvider: EditorSnapshotProvider,
    /** 规划上下文工厂。 */
    private val planningContextFactory: PlanningContextFactory,
    /** 异步请求生命周期支持。 */
    private val asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    /** 当前主体定位器。 */
    private val subjectLocatorProvider: () -> SubjectLocator,
    /** 当前语义分析器。 */
    private val semanticAnalyzerProvider: () -> SemanticAnalyzer,
    /** 当前分析结果工厂。 */
    private val analysisOutcomeFactoryProvider: () -> AnalysisOutcomeFactory,
    /** 代码主体句柄工厂。 */
    private val codeSubjectHandleFactory: CodeSubjectHandleFactory,
    private val workspaceGraphCommitter: WorkspaceGraphCommitter,
    private val eventSink: GraphEditorApplicationEventSink,
    /** 失效异步分析请求。 */
    private val onInvalidateAuditRequests: () -> Unit,
    /** 图诊断日志。 */
    private val onLogGraphDiagnostics: (String, GraphDocument?) -> Unit,
    /** 运行时渲染链路 trace。 */
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
    /** 日志记录器。 */
    private val logger: com.intellij.openapi.diagnostic.Logger,
) {
    private val useCase = SubjectGraphUseCase()

    /** 当前主体链路请求轮次跟踪器。 */
    private val currentSubjectGraphRequestTracker = CurrentSubjectGraphRequestTracker()

    /** 当前正在执行的分析任务。 */
    @Volatile
    private var currentAnalysisPromise: CancellablePromise<AnalysisOutcomeAsyncResult>? = null

    private fun presentFeedback(
        level: ApplicationFeedbackLevel,
        message: String,
        preserveLastMessageType: Boolean = false,
    ) {
        eventSink.emit(GraphEditorApplicationEvent.Feedback(level, message, preserveLastMessageType))
    }

    /** 当前投影视图设置。 */
    @Volatile
    private var currentProjectionSettings: InteractiveProjectionSettings = InteractiveProjectionSettings()

    /** 当前请求的分析展示模式。 */
    @Volatile
    private var requestedAnalysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FLOWCHART

    /** 最近一次分析所针对的主题句柄。 */
    @Volatile
    private var lastAnalyzedSubjectHandle: SubjectHandle? = null

    /** 最近一次语义分析结果。 */
    @Volatile
    private var lastSemanticAnalysisResult: SemanticAnalysisResult? = null

    /**
     * 释放当前分析任务。
     */
    fun dispose() {
        currentAnalysisPromise?.cancel()
    }

    /**
     * 清空最近一次分析缓存。
     */
    fun clearLastAnalysisCache() {
        lastAnalyzedSubjectHandle = null
        lastSemanticAnalysisResult = null
    }

    /**
     * 预设后续分析所使用的展示模式；返回值表示是否发生变化。
     */
    fun prepareRequestedAnalysisDisplayMode(displayMode: AnalysisDisplayMode): Boolean {
        if (requestedAnalysisDisplayMode == displayMode) {
            return false
        }
        requestedAnalysisDisplayMode = displayMode
        return true
    }

    /**
     * 切换分析展示模式。
     */
    fun requestAnalysisDisplayMode(displayMode: AnalysisDisplayMode) {
        requestedAnalysisDisplayMode = displayMode
        val currentSnapshot = snapshotProvider.snapshot()
        when (val result = useCase.requestAnalysisDisplayMode(
            snapshot = currentSnapshot,
            displayMode = displayMode,
            cachedResult = lastSemanticAnalysisResult,
            lastGraphSource = currentSnapshot.lastGraphSource,
        )) {
            is SubjectGraphUseCaseResult.RequestedDisplayMode -> {
                eventSink.emit(GraphEditorApplicationEvent.AnalysisDisplayModeChanged(result.displayMode))
            }
            is SubjectGraphUseCaseResult.DisplayModeRejected -> {
                presentFeedback(result.level, result.message)
            }
            is SubjectGraphUseCaseResult.DisplayModeReady -> {
                applyAnalysisResult(
                    analysisResult = result.analysisResult,
                    source = result.source,
                    reason = "displayModeSwitch",
                    requestedDisplayModeOverride = result.displayMode,
                )
            }
            else -> Unit
        }
    }
    /**
     * 异步加载当前编辑器上下文链路。
     */
    fun loadCurrentEditorContextGraphAsync(editor: Editor? = null) {
        resetCurrentMethodExpansionState()
        val requestId = beginCurrentSubjectGraphRequest()
        loadCurrentEditorContextGraphAsync(
            requestId = requestId,
            deferUntilSmart = true,
            editor = editor,
        )
    }

    /**
     * 把当前方法节点追加到画布中。
     */
    fun addCurrentMethodNode(handle: CodeSubjectHandle? = null): Boolean {
        debugLazy(logger.isDebugEnabled, logger::debug) { "开始追加当前方法节点" }
        val currentMethodNode = try {
            computeCurrentMethodNode(handle)
        } catch (throwable: Throwable) {
            logger.warn("追加当前方法节点失败", throwable)
            presentFeedback(
                ApplicationFeedbackLevel.ERROR,
                "追加当前方法节点失败：${throwable.message ?: throwable.javaClass.simpleName}",
            )
            return false
        } ?: run {
            presentFeedback(
                ApplicationFeedbackLevel.WARNING,
                "当前光标不在方法内，请先把光标放到方法签名或方法体内。",
            )
            return false
        }

        val result = useCase.applyCurrentMethodNode(
            snapshot = snapshotProvider.snapshot(),
            currentMethodNode = CurrentMethodNodeInput(
                node = currentMethodNode.node,
                methodSignature = currentMethodNode.methodSignature,
                methodDisplayName = currentMethodNode.methodDisplayName,
            ),
        )
        presentFeedback(ApplicationFeedbackLevel.SUCCESS, result.feedbackMessage)
        workspaceGraphCommitter.commitWorkspaceGraph(
            graph = result.graph,
            selectedMethodSignature = result.selectedMethodSignature,
            preserveDraftPatchUndo = false,
            syncBrowser = true,
        )
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "当前方法节点追加完成: signature=${currentMethodNode.methodSignature}"
        }
        return true
    }

    /**
     * 把当前编辑器上下文对应的节点追加到画布中。
     */
    fun addCurrentEditorContextNode(): Boolean {
        if (shouldDeferCurrentMethodResolutionUntilSmart()) {
            presentFeedback(
                ApplicationFeedbackLevel.INFO,
                "项目正在索引，已在索引完成后追加当前方法节点。",
            )
            DumbService.getInstance(project).smartInvokeLater(
                {
                    if (!project.isDisposed) {
                        addCurrentEditorContextNode()
                    }
                },
                ModalityState.defaultModalityState(),
            )
            return true
        }

        val handle = try {
            locateCurrentSubject()
        } catch (throwable: Throwable) {
            logger.warn("解析当前编辑器上下文失败", throwable)
            presentFeedback(
                ApplicationFeedbackLevel.ERROR,
                "追加当前节点失败：${throwable.message ?: throwable.javaClass.simpleName}",
            )
            return false
        } ?: run {
            presentFeedback(
                ApplicationFeedbackLevel.WARNING,
                "当前光标不在可识别的方法或资源节点内，请把光标放到方法、Mapper SQL、配置项、Markdown 或 SQL 文件内容上。",
            )
            return false
        }

        return when (handle) {
            is CodeSubjectHandle -> addCurrentMethodNode(handle)
            is ResourceSubjectHandle -> addCurrentResourceNode(handle)
        }
    }

    /**
     * 展开摘要节点，重新以更宽的预算提取当前主体链路。
     */
    fun requestExpandOverflowNode(nodeId: String) {
        val snapshot = snapshotProvider.snapshot()
        val node = findNavigationNode(snapshot, nodeId)
        val selectedMethodSignature = snapshot.selectedMethodSignature
        val requestId = beginCurrentSubjectGraphRequest()
        if (node == null) {
            presentFeedback(
                ApplicationFeedbackLevel.WARNING,
                "未找到需要展开的摘要节点。",
            )
            return
        }
        if (selectedMethodSignature.isNullOrBlank()) {
            presentFeedback(
                ApplicationFeedbackLevel.WARNING,
                "当前没有可重新提取的主体上下文，请先重新加载当前编辑器上下文链路。",
            )
            return
        }

        currentProjectionSettings = currentProjectionSettings.expandFor(node)
        presentFeedback(
            ApplicationFeedbackLevel.INFO,
            "正在继续展开链路：${node.title}",
        )
        val cachedHandle = (lastAnalyzedSubjectHandle as? CodeSubjectHandle)
            ?.takeIf { handle -> handle.methodSignature == selectedMethodSignature }
        if (cachedHandle != null) {
            submitSubjectAnalysisTask(
                handle = cachedHandle,
                requestId = requestId,
                source = CURRENT_METHOD_SOURCE,
            )
            return
        }
        resolveCodeSubjectBySignatureAsync(
            signature = selectedMethodSignature,
            requestId = requestId,
            failureAction = "继续展开链路",
            onResolved = { handle ->
                submitSubjectAnalysisTask(
                    handle = handle,
                    requestId = requestId,
                    source = CURRENT_METHOD_SOURCE,
                )
            },
        )
    }

    /**
     * 把选中方法签名压入前端状态。
     */
    fun pushSelectedMethod(signature: String) {
        eventSink.emit(GraphEditorApplicationEvent.SelectedMethodChanged(signature))
    }

    /**
     * 预览当前编辑器主体类型，不提交文档内容。
     */
    fun previewCurrentEditorSubjectKind(editor: Editor? = null): SubjectPreviewKind? {
        return subjectLocatorProvider().previewKind(project, editor, commitDocument = false)
    }

    /**
     * 按方法签名异步载入真实方法链路图。
     */
    fun loadDebugMethodGraphBySignatureAsync(signature: String) {
        val trimmedSignature = signature.trim()
        val requestId = beginCurrentSubjectGraphRequest()
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "开始按签名自动提取真实链路: signature=$trimmedSignature"
        }
        presentFeedback(
            ApplicationFeedbackLevel.INFO,
            "正在按签名分析真实主体链路：$trimmedSignature",
        )
        resolveCodeSubjectBySignatureAsync(
            signature = trimmedSignature,
            requestId = requestId,
            failureAction = "按签名提取真实方法链路",
            onResolved = { handle ->
                submitSubjectAnalysisTask(
                    handle = handle,
                    requestId = requestId,
                    source = CURRENT_METHOD_SOURCE,
                )
            },
        )
    }

    /**
     * 开始一轮新的当前主体链路请求。
     */
    private fun beginCurrentSubjectGraphRequest(): Long {
        currentAnalysisPromise?.cancel()
        return currentSubjectGraphRequestTracker.beginRequest()
    }

    /**
     * 判断给定请求是否仍然是最新的一轮当前主体链路请求。
     */
    private fun isLatestCurrentSubjectGraphRequest(requestId: Long): Boolean {
        return currentSubjectGraphRequestTracker.isLatest(requestId)
    }

    /**
     * 重置当前主体链路的展开预算。
     */
    private fun resetCurrentMethodExpansionState() {
        currentProjectionSettings = InteractiveProjectionSettings()
    }

    /**
     * 异步加载当前编辑器上下文链路的内部实现。
     */
    private fun loadCurrentEditorContextGraphAsync(
        requestId: Long,
        deferUntilSmart: Boolean,
        editor: Editor? = null,
    ) {
        if (deferUntilSmart && shouldDeferCurrentMethodResolutionUntilSmart()) {
            presentFeedback(
                ApplicationFeedbackLevel.INFO,
                "项目正在索引，已在索引完成后继续分析当前编辑器上下文链路。",
            )
            DumbService.getInstance(project).smartInvokeLater(
                {
                    if (!project.isDisposed && isLatestCurrentSubjectGraphRequest(requestId)) {
                        loadCurrentEditorContextGraphAsync(
                            requestId = requestId,
                            deferUntilSmart = false,
                            editor = editor,
                        )
                    }
                },
                ModalityState.defaultModalityState(),
            )
            return
        }

        val handle = runCatching { locateCurrentSubject(editor) }.getOrElse { throwable ->
            logger.warn("解析当前编辑器上下文失败", throwable)
            presentFeedback(
                ApplicationFeedbackLevel.ERROR,
                "加载当前节点关联图失败：${throwable.message ?: throwable.javaClass.simpleName}",
            )
            return
        }

        when (handle) {
            null -> {
                presentFeedback(
                    ApplicationFeedbackLevel.WARNING,
                    "当前光标不在可识别的方法或资源节点内，请把光标放到方法、Mapper SQL、配置项、Markdown 或 SQL 文件内容上。",
                )
            }

            is CodeSubjectHandle -> {
                presentFeedback(
                    ApplicationFeedbackLevel.INFO,
                    "正在分析当前编辑器上下文链路：${handle.displayName}",
                )
                submitSubjectAnalysisTask(
                    handle = handle,
                    requestId = requestId,
                    source = CURRENT_METHOD_SOURCE,
                )
            }

            is ResourceSubjectHandle -> {
                presentFeedback(
                    ApplicationFeedbackLevel.INFO,
                    "正在分析当前节点关联图：${handle.displayName}",
                )
                submitSubjectAnalysisTask(
                    handle = handle,
                    requestId = requestId,
                    source = CURRENT_CONTEXT_SOURCE,
                )
            }
        }
    }

    /**
     * 提交主题语义分析任务。
     */
    private fun submitSubjectAnalysisTask(
        handle: SubjectHandle,
        requestId: Long,
        source: String,
    ) {
        var analysisTask = ReadAction
            .nonBlocking<AnalysisOutcomeAsyncResult> {
                if (project.isDisposed) {
                    return@nonBlocking AnalysisOutcomeAsyncResult.cancelled()
                }
                try {
                    AnalysisOutcomeAsyncResult.success(computeAnalysisResultInReadAction(handle))
                } catch (throwable: Throwable) {
                    if (isBenignCurrentSubjectGraphCancellation(throwable)) {
                        AnalysisOutcomeAsyncResult.cancelled()
                    } else {
                        AnalysisOutcomeAsyncResult.failure(throwable)
                    }
                }
            }
            .expireWith(project)
        if (handle is CodeSubjectHandle) {
            analysisTask = analysisTask.inSmartMode(project)
        }
        currentAnalysisPromise = analysisTask
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                handleSubjectAnalysisTaskResult(requestId, handle, source, result)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * 在 UI 线程处理主题分析任务结果。
     */
    private fun handleSubjectAnalysisTaskResult(
        requestId: Long,
        handle: SubjectHandle,
        source: String,
        result: AnalysisOutcomeAsyncResult,
    ) {
        if (project.isDisposed || !isLatestCurrentSubjectGraphRequest(requestId)) {
            return
        }
        when {
            result.cancelled -> {
                debugLazy(logger.isDebugEnabled, logger::debug) {
                    "当前主体语义分析已取消: subject=${handle.displayName}"
                }
            }

            result.failure != null -> {
                logger.warn("异步语义分析失败", result.failure)
                presentFeedback(
                    ApplicationFeedbackLevel.ERROR,
                    "加载当前主体链路失败：${result.failure.message ?: result.failure.javaClass.simpleName}",
                )
            }

            result.result == null -> {
                presentFeedback(
                    ApplicationFeedbackLevel.WARNING,
                    "当前主体在分析过程中失效，请重新触发链路分析。",
                )
            }

            else -> {
                applyAnalysisResult(
                    analysisResult = result.result.analysisResult,
                    source = source,
                    reason = "async",
                    prebuiltOutcome = result.result.outcome,
                )
            }
        }
    }

    /**
     * 定位当前编辑器光标所在的语义主体。
     */
    private fun locateCurrentSubject(editor: Editor? = null): SubjectHandle? {
        return computeOnIdeThread {
            val targetEditor = editor ?: FileEditorManager.getInstance(project).selectedTextEditor
                ?: return@computeOnIdeThread null
            subjectLocatorProvider().locate(project, targetEditor)
        }
    }

    /**
     * 仅在当前主体是代码主体时返回方法句柄。
     */
    private fun locateCurrentCodeSubject(): CodeSubjectHandle? {
        val handle = locateCurrentSubject()
        val codeHandle = handle as? CodeSubjectHandle
        if (codeHandle == null) {
            debugLazy(logger.isDebugEnabled, logger::debug) { "当前光标不在方法内，无法提取链路图" }
        }
        return codeHandle
    }

    /**
     * 在读动作里按方法签名反查代码主体。
     */
    private fun locateCodeSubjectBySignatureInReadAction(signature: String): CodeSubjectHandle? {
        val method = DebugMethodSignatureLocator.find(project, signature) ?: return null
        val file = method.containingFile ?: method.navigationElement.containingFile ?: return null
        return codeSubjectHandleFactory.create(file, method)
    }

    /**
     * 异步按签名解析代码主体，成功后交给调用方继续处理。
     */
    private fun resolveCodeSubjectBySignatureAsync(
        signature: String,
        requestId: Long,
        failureAction: String,
        onResolved: (CodeSubjectHandle) -> Unit,
    ) {
        val startedAt = System.nanoTime()
        asyncRequestLifecycle.runBackgroundTask(
            work = {
                ReadAction.compute<CodeSubjectHandle?, RuntimeException> {
                    locateCodeSubjectBySignatureInReadAction(signature)
                }
            },
            onCompleted = { result ->
                if (project.isDisposed || !isLatestCurrentSubjectGraphRequest(requestId)) {
                    return@runBackgroundTask
                }
                result.fold(
                    onSuccess = { handle ->
                        if (handle == null) {
                            logger.warn("$failureAction 失败: signature=$signature, reason=methodNotFound")
                            presentFeedback(
                                ApplicationFeedbackLevel.WARNING,
                                "未在当前项目中找到方法：$signature",
                            )
                        } else {
                            debugLazy(logger.isDebugEnabled, logger::debug) {
                                "$failureAction 定位主体完成: signature=${handle.methodSignature}, durationMs=${(System.nanoTime() - startedAt) / 1_000_000}"
                            }
                            onResolved(handle)
                        }
                    },
                    onFailure = { throwable ->
                        logger.warn("$failureAction 异常", throwable)
                        presentFeedback(
                            ApplicationFeedbackLevel.ERROR,
                            "$failureAction 失败：${throwable.message ?: throwable.javaClass.simpleName}",
                        )
                    },
                )
            },
        )
    }

    /**
     * 在读动作里执行语义分析并生成投影结果。
     */
    private fun computeAnalysisResultInReadAction(handle: SubjectHandle): AnalysisExecutionResult? {
        val totalStartedAt = System.nanoTime()
        val effectiveDisplayMode = useCase.effectiveAnalysisDisplayModeFor(
            subject = handle,
            requestedDisplayMode = requestedAnalysisDisplayMode,
        )
        val analysisStartedAt = System.nanoTime()
        val analysisResult = semanticAnalyzerProvider().analyze(
            handle = handle,
            capturePolicy = SemanticCapturePolicy(),
            budgetPolicy = currentProjectionSettings.toTraversalBudgetPolicy(),
        )
        traceStage(
            stage = "analysis.semanticAnalyzer",
            startedAtNanos = analysisStartedAt,
        ) {
            listOf(
                "subject=${handle.displayName}",
                "units=${analysisResult.semanticUnits.size}",
                "relations=${analysisResult.relations.size}",
                "anchors=${analysisResult.anchors.size}",
                "diagnostics=${analysisResult.diagnostics.size}",
            )
        }
        val outcomeStartedAt = System.nanoTime()
        val outcome = analysisOutcomeFactoryProvider().create(
            analysisResult = analysisResult,
            displayMode = effectiveDisplayMode,
            projectionPolicy = currentProjectionSettings.toProjectionPolicy(),
        )
        traceStage(
            stage = "analysis.outcomeFactory",
            startedAtNanos = outcomeStartedAt,
        ) {
            outcomeSummaryDetails(outcome)
        }
        traceStage(
            stage = "analysis.total",
            startedAtNanos = totalStartedAt,
        ) {
            listOf(
                "subject=${handle.displayName}",
                "mode=${outcome.displayMode}",
                "visible=${LinkGraphRenderTrace.graphSummary(outcome.visibleGraph)}",
                "full=${LinkGraphRenderTrace.graphSummary(outcome.fullGraph)}",
            )
        }
        return AnalysisExecutionResult(
            analysisResult = analysisResult,
            outcome = outcome,
        )
    }

    /**
     * 把语义分析结果应用到编辑器状态与内部缓存。
     */
    private fun applyAnalysisResult(
        analysisResult: SemanticAnalysisResult,
        source: String,
        reason: String,
        prebuiltOutcome: AnalysisOutcome? = null,
        requestedDisplayModeOverride: AnalysisDisplayMode? = null,
    ) {
        val effectiveDisplayMode = requestedDisplayModeOverride ?: useCase.effectiveAnalysisDisplayModeFor(
            subject = analysisResult.subject,
            requestedDisplayMode = requestedAnalysisDisplayMode,
        )
        val outcomeStartedAt = System.nanoTime()
        val outcome = prebuiltOutcome?.takeIf { it.displayMode == effectiveDisplayMode }
            ?: analysisOutcomeFactoryProvider().create(
                analysisResult = analysisResult,
                displayMode = effectiveDisplayMode,
                projectionPolicy = currentProjectionSettings.toProjectionPolicy(),
            )
        traceStage(
            stage = if (prebuiltOutcome?.displayMode == effectiveDisplayMode) {
                "analysis.outcomeReuse"
            } else {
                "analysis.outcomeFactory.apply"
            },
            startedAtNanos = outcomeStartedAt,
        ) {
            outcomeSummaryDetails(outcome)
        }
        lastAnalyzedSubjectHandle = analysisResult.subject
        lastSemanticAnalysisResult = analysisResult
        requestedAnalysisDisplayMode = outcome.displayMode
        onLogGraphDiagnostics("semantic:$reason:visible", outcome.visibleGraph)
        if (outcome.fullGraph != outcome.visibleGraph) {
            onLogGraphDiagnostics("semantic:$reason:full", outcome.fullGraph)
        }
        onInvalidateAuditRequests()
        val mutateStartedAt = System.nanoTime()
        eventSink.emit(GraphEditorApplicationEvent.AnalysisOutcomeLoaded(outcome, source))
        traceStage(
            stage = "analysis.applyState",
            startedAtNanos = mutateStartedAt,
        ) {
            outcomeSummaryDetails(outcome)
        }
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "统一语义分析加载完成[$reason]: source=$source, mode=${outcome.displayMode}, displayName=${outcome.displayName}, visibleNodes=${outcome.visibleGraph.nodes.size}, fullNodes=${outcome.fullGraph.nodes.size}"
        }
    }

    private fun traceStage(
        stage: String,
        startedAtNanos: Long,
        details: () -> List<String>,
    ) {
        val trace = runtimeTrace ?: return
        LinkGraphRenderTrace.stage(
            enabled = true,
            log = { message -> trace { message } },
            stage = stage,
            startedAtNanos = startedAtNanos,
            details = details,
        )
    }

    private fun outcomeSummaryDetails(outcome: AnalysisOutcome): List<String> {
        return listOf(
            "mode=${outcome.displayMode}",
            "visible=${LinkGraphRenderTrace.graphSummary(outcome.visibleGraph)}",
            "full=${LinkGraphRenderTrace.graphSummary(outcome.fullGraph)}",
            "factVisible=${LinkGraphRenderTrace.graphSummary(outcome.factGraphView?.visibleGraph)}",
            "flowVisible=${LinkGraphRenderTrace.graphSummary(outcome.flowchartView?.visibleGraph)}",
            "resourceVisible=${LinkGraphRenderTrace.graphSummary(outcome.resourceRelationView?.visibleGraph)}",
            "truncated=${outcome.projectionStats.truncated}",
        )
    }

    /**
     * 在索引未就绪且当前是代码主体时，推迟方法解析。
     */
    private fun shouldDeferCurrentMethodResolutionUntilSmart(): Boolean {
        if (!DumbService.isDumb(project)) {
            return false
        }
        return currentEditorPreviewKind() == SubjectPreviewKind.CODE_SUBJECT
    }

    /**
     * 获取当前编辑器里主体类型的轻量预览。
     */
    private fun currentEditorPreviewKind(): SubjectPreviewKind? {
        return computeOnIdeThread {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@computeOnIdeThread null
            previewCurrentEditorSubjectKind(editor)
        }
    }

    /**
     * 构造“当前方法节点”，用于把焦点方法补回图中。
     */
    private fun computeCurrentMethodNode(handle: CodeSubjectHandle? = null): CurrentMethodNode? {
        val codeHandle = handle ?: locateCurrentCodeSubject() ?: return null
        return computeOnBackgroundReadThread {
            val method = codeHandle.methodPointer.element ?: return@computeOnBackgroundReadThread null
            val location = buildString {
                append(codeHandle.sourcePath)
                codeHandle.sourceRange.startLine?.let { line ->
                    append(':')
                    append(line)
                    append(':')
                    append(1)
                }
            }
            CurrentMethodNode(
                node = GraphNode(
                    id = GraphNode.stableId(NodeType.METHOD, codeHandle.methodSignature),
                    type = NodeType.METHOD,
                    title = codeHandle.displayName,
                    location = location,
                    doc = method.docComment
                        ?.descriptionElements
                        ?.joinToString(separator = "") { element -> element.text }
                        ?.trim()
                        ?.ifBlank { null },
                    signature = codeHandle.methodSignature,
                    inputs = method.parameterList.parameters.map { parameter -> canonicalTypeText(parameter.type) },
                    outputs = listOf(canonicalTypeText(method.returnType)),
                    sourceKind = codeHandle.kind.name,
                    metadata = buildMap {
                        put("source.filePath", codeHandle.sourcePath)
                        put("source.startOffset", codeHandle.sourceRange.startOffset.toString())
                        put("source.endOffset", codeHandle.sourceRange.endOffset.toString())
                        codeHandle.sourceRange.startLine?.let { put("source.startLine", it.toString()) }
                        codeHandle.sourceRange.endLine?.let { put("source.endLine", it.toString()) }
                    },
                ),
                methodSignature = codeHandle.methodSignature,
                methodDisplayName = codeHandle.displayName,
            )
        }
    }

    /**
     * 把资源节点追加到画布中。
     */
    private fun addCurrentResourceNode(handle: ResourceSubjectHandle): Boolean {
        val node = resourceNodeForHandle(handle)
        debugLazy(logger.isDebugEnabled, logger::debug) {
            "开始追加当前节点: nodeId=${node.id}, title=${node.title}, kind=${handle.kind}"
        }
        val result = useCase.applyResourceNode(snapshotProvider.snapshot(), handle, node)
        workspaceGraphCommitter.commitWorkspaceGraph(
            graph = result.graph,
            selectedMethodSignature = null,
            preserveDraftPatchUndo = false,
            syncBrowser = false,
        )
        eventSink.emit(
            GraphEditorApplicationEvent.ResourceNodeAdded(
                selectedNodeId = result.selectedNodeId,
                feedbackMessage = result.feedbackMessage,
            ),
        )
        debugLazy(logger.isDebugEnabled, logger::debug) { "当前节点追加完成: nodeId=${node.id}" }
        return true
    }

    /**
     * 根据资源主题句柄构造图节点。
     */
    private fun resourceNodeForHandle(handle: ResourceSubjectHandle): GraphNode {
        val nodeType = when (handle.kind) {
            com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.CONFIG_ITEM -> NodeType.CONFIG_ITEM
            com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.MYBATIS_STATEMENT,
            com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.SQL_FILE -> NodeType.SQL
            com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.XML_RESOURCE -> NodeType.XML_RESOURCE
            com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.MARKDOWN_PAGE -> NodeType.DOC_PAGE
        }
        val location = handle.sourceRange.startLine?.let { line -> "${handle.sourcePath}:$line" } ?: handle.sourcePath
        val metadata = buildMap {
            putAll(handle.attributes)
            when (handle.kind) {
                com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.CONFIG_ITEM -> put("file", handle.sourcePath)
                else -> putIfAbsent("path", handle.sourcePath)
            }
        }
        val rawKey = when (handle.kind) {
            com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.MYBATIS_STATEMENT,
            com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.SQL_FILE -> handle.attributes["statementId"]
                ?: handle.attributes["path"]
                ?: handle.subjectId
            com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.CONFIG_ITEM -> handle.attributes["key"] ?: handle.subjectId
            com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.XML_RESOURCE,
            com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.MARKDOWN_PAGE -> handle.sourcePath
        }
        val ownerContext = when (handle.kind) {
            com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.MYBATIS_STATEMENT -> handle.attributes["statementType"]
            com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind.SQL_FILE -> "file"
            else -> null
        }
        return GraphNode(
            id = GraphNode.stableId(nodeType, rawKey, ownerContext = ownerContext),
            type = nodeType,
            title = handle.displayName,
            location = location,
            sourceKind = handle.kind.name,
            metadata = metadata,
        )
    }

    /**
     * 在 IDEA 线程中安全执行读操作。
     */
    private fun <T> computeOnIdeThread(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            return action()
        }
        val completed = AtomicBoolean(false)
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        application.invokeAndWait(
            {
                try {
                    result.set(action())
                    completed.set(true)
                } catch (throwable: Throwable) {
                    error.set(throwable)
                }
            },
            ModalityState.defaultModalityState(),
        )
        error.get()?.let { throw it }
        check(completed.get()) { "未能在 IDEA 线程中完成链路图请求" }
        return result.get()
    }

    /**
     * 在线程池里执行读动作并等待结果。
     */
    private fun <T> computeOnBackgroundReadThread(action: () -> T): T {
        return asyncRequestLifecycle.computeOnBackgroundReadThread(action)
    }

    /**
     * 判断当前主体链路请求的异常是否属于可忽略取消。
     */
    private fun isBenignCurrentSubjectGraphCancellation(throwable: Throwable): Boolean {
        if (throwable is ProcessCanceledException || throwable is AlreadyDisposedException || throwable is CancellationException) {
            return true
        }
        return throwable.cause?.let(::isBenignCurrentSubjectGraphCancellation) == true
    }

    companion object {
        /** 当前代码主体链路的来源标记。 */
        private const val CURRENT_METHOD_SOURCE = SubjectGraphUseCase.CURRENT_METHOD_SOURCE
        /** 当前上下文链路的来源标记。 */
        private const val CURRENT_CONTEXT_SOURCE = SubjectGraphUseCase.CURRENT_CONTEXT_SOURCE
    }
}
