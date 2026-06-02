package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.application.model.findNavigationNode
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.application.request.AsyncRequestLifecycleSupport
import com.charmnight.linkgraph.application.usecase.SubjectGraphUseCase
import com.charmnight.linkgraph.application.usecase.SubjectGraphUseCaseResult
import com.charmnight.linkgraph.application.workflow.subject.SubjectAnalysisResultApplier
import com.charmnight.linkgraph.application.workflow.subject.SubjectAnalysisWorkflow
import com.charmnight.linkgraph.application.workflow.subject.SubjectGraphRequestCoordinator
import com.charmnight.linkgraph.application.workflow.subject.SubjectGraphWorkflowDependencies
import com.charmnight.linkgraph.application.workflow.subject.SubjectGraphWorkflowState
import com.charmnight.linkgraph.application.workflow.subject.SubjectNodeAppendWorkflow
import com.charmnight.linkgraph.application.workflow.subject.SubjectResolutionWorkflow
import com.charmnight.linkgraph.foundation.debugLazy
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandleFactory
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project

/**
 * 当前主体链路 workflow 门面。
 *
 * 具体职责已拆分到 `application.workflow.subject`：
 * 主体定位、异步分析、结果应用和节点追加分别由专门子 workflow 处理。
 */
internal class SubjectGraphWorkflow(
    private val project: Project,
    snapshotProvider: EditorSnapshotProvider,
    asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    subjectLocatorProvider: () -> SubjectLocator,
    semanticAnalyzerProvider: () -> SemanticAnalyzer,
    analysisOutcomeFactoryProvider: () -> AnalysisOutcomeFactory,
    codeSubjectHandleFactory: CodeSubjectHandleFactory,
    workspaceGraphCommitter: WorkspaceGraphCommitter,
    eventSink: GraphEditorApplicationEventSink,
    onInvalidateQaRequests: () -> Unit,
    onLogGraphDiagnostics: (String, GraphDocument?) -> Unit,
    runtimeTrace: ((() -> String) -> Unit)? = null,
    logger: com.intellij.openapi.diagnostic.Logger,
) {
    private val useCase = SubjectGraphUseCase()
    private val dependencies = SubjectGraphWorkflowDependencies(
        project = project,
        snapshotProvider = snapshotProvider,
        asyncRequestLifecycle = asyncRequestLifecycle,
        subjectLocatorProvider = subjectLocatorProvider,
        semanticAnalyzerProvider = semanticAnalyzerProvider,
        analysisOutcomeFactoryProvider = analysisOutcomeFactoryProvider,
        codeSubjectHandleFactory = codeSubjectHandleFactory,
        workspaceGraphCommitter = workspaceGraphCommitter,
        eventSink = eventSink,
        invalidateQaRequests = onInvalidateQaRequests,
        logGraphDiagnostics = onLogGraphDiagnostics,
        runtimeTrace = runtimeTrace,
        logger = logger,
    )
    private val state = SubjectGraphWorkflowState()
    private val requestCoordinator = SubjectGraphRequestCoordinator()
    private val subjectResolutionWorkflow = SubjectResolutionWorkflow(
        dependencies = dependencies,
        requestCoordinator = requestCoordinator,
    )
    private val resultApplier = SubjectAnalysisResultApplier(
        dependencies = dependencies,
        state = state,
        useCase = useCase,
    )
    private val analysisWorkflow = SubjectAnalysisWorkflow(
        dependencies = dependencies,
        state = state,
        requestCoordinator = requestCoordinator,
        resultApplier = resultApplier,
        useCase = useCase,
    )
    private val nodeAppendWorkflow = SubjectNodeAppendWorkflow(
        dependencies = dependencies,
        subjectResolutionWorkflow = subjectResolutionWorkflow,
        useCase = useCase,
    )

    fun dispose() {
        requestCoordinator.cancelCurrentAnalysis()
    }

    fun clearLastAnalysisCache() {
        state.clearLastAnalysisCache()
    }

    fun prepareRequestedAnalysisDisplayMode(displayMode: AnalysisDisplayMode): Boolean {
        return state.prepareRequestedDisplayMode(displayMode)
    }

    fun requestAnalysisDisplayMode(displayMode: AnalysisDisplayMode) {
        state.requestedDisplayMode = displayMode
        val currentSnapshot = dependencies.snapshotProvider.snapshot()
        when (val result = useCase.requestAnalysisDisplayMode(
            snapshot = currentSnapshot,
            displayMode = displayMode,
            cachedResult = state.lastSemanticAnalysisResult,
            lastGraphSource = currentSnapshot.lastGraphSource,
        )) {
            is SubjectGraphUseCaseResult.RequestedDisplayMode -> {
                dependencies.emit(GraphEditorApplicationEvent.AnalysisDisplayModeChanged(result.displayMode))
            }
            is SubjectGraphUseCaseResult.DisplayModeRejected -> {
                dependencies.emitFeedback(result.level, result.message)
            }
            is SubjectGraphUseCaseResult.DisplayModeReady -> {
                resultApplier.apply(
                    analysisResult = result.analysisResult,
                    source = result.source,
                    reason = "displayModeSwitch",
                    requestedDisplayModeOverride = result.displayMode,
                )
            }
            else -> Unit
        }
    }

    fun loadCurrentEditorContextGraphAsync(editor: Editor? = null) {
        state.resetProjectionSettings()
        val requestId = requestCoordinator.beginRequest()
        loadCurrentEditorContextGraphAsync(
            requestId = requestId,
            deferUntilSmart = true,
            editor = editor,
        )
    }

    fun addCurrentMethodNode(handle: CodeSubjectHandle? = null): Boolean {
        return nodeAppendWorkflow.addCurrentMethodNode(handle)
    }

    fun addCurrentEditorContextNode(): Boolean {
        if (subjectResolutionWorkflow.shouldDeferCurrentMethodResolutionUntilSmart()) {
            dependencies.emitFeedback(
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
            subjectResolutionWorkflow.locateCurrentSubject()
        } catch (throwable: Throwable) {
            dependencies.logger.warn("解析当前编辑器上下文失败", throwable)
            dependencies.emitFeedback(
                ApplicationFeedbackLevel.ERROR,
                "追加当前节点失败：${throwable.message ?: throwable.javaClass.simpleName}",
            )
            return false
        } ?: run {
            dependencies.emitFeedback(
                ApplicationFeedbackLevel.WARNING,
                "当前光标不在可识别的方法或资源节点内，请把光标放到方法、Mapper SQL、配置项、Markdown 或 SQL 文件内容上。",
            )
            return false
        }

        return when (handle) {
            is CodeSubjectHandle -> addCurrentMethodNode(handle)
            is ResourceSubjectHandle -> nodeAppendWorkflow.addCurrentResourceNode(handle)
        }
    }

    fun requestExpandOverflowNode(nodeId: String) {
        val snapshot = dependencies.snapshotProvider.snapshot()
        val node = findNavigationNode(snapshot, nodeId)
        val selectedMethodSignature = snapshot.selectedMethodSignature
        val requestId = requestCoordinator.beginRequest()
        if (node == null) {
            dependencies.emitFeedback(
                ApplicationFeedbackLevel.WARNING,
                "未找到需要展开的摘要节点。",
            )
            return
        }
        if (selectedMethodSignature.isNullOrBlank()) {
            dependencies.emitFeedback(
                ApplicationFeedbackLevel.WARNING,
                "当前没有可重新提取的主体上下文，请先重新加载当前编辑器上下文链路。",
            )
            return
        }

        state.projectionSettings = state.projectionSettings.expandFor(node)
        dependencies.emitFeedback(
            ApplicationFeedbackLevel.INFO,
            "正在继续展开链路：${node.title}",
        )
        val cachedHandle = (state.lastAnalyzedSubjectHandle as? CodeSubjectHandle)
            ?.takeIf { handle -> handle.methodSignature == selectedMethodSignature }
        if (cachedHandle != null) {
            analysisWorkflow.submit(
                handle = cachedHandle,
                requestId = requestId,
                source = CURRENT_METHOD_SOURCE,
            )
            return
        }
        subjectResolutionWorkflow.resolveCodeSubjectBySignatureAsync(
            signature = selectedMethodSignature,
            requestId = requestId,
            failureAction = "继续展开链路",
            onResolved = { handle ->
                analysisWorkflow.submit(
                    handle = handle,
                    requestId = requestId,
                    source = CURRENT_METHOD_SOURCE,
                )
            },
        )
    }

    fun pushSelectedMethod(signature: String) {
        dependencies.emit(GraphEditorApplicationEvent.SelectedMethodChanged(signature))
    }

    fun previewCurrentEditorSubjectKind(editor: Editor? = null): SubjectPreviewKind? {
        return subjectResolutionWorkflow.previewCurrentEditorSubjectKind(editor)
    }

    fun loadDebugMethodGraphBySignatureAsync(signature: String) {
        val trimmedSignature = signature.trim()
        val requestId = requestCoordinator.beginRequest()
        debugLazy(dependencies.logger.isDebugEnabled, dependencies.logger::debug) {
            "开始按签名自动提取真实链路: signature=$trimmedSignature"
        }
        dependencies.emitFeedback(
            ApplicationFeedbackLevel.INFO,
            "正在按签名分析真实主体链路：$trimmedSignature",
        )
        subjectResolutionWorkflow.resolveCodeSubjectBySignatureAsync(
            signature = trimmedSignature,
            requestId = requestId,
            failureAction = "按签名提取真实方法链路",
            onResolved = { handle ->
                analysisWorkflow.submit(
                    handle = handle,
                    requestId = requestId,
                    source = CURRENT_METHOD_SOURCE,
                )
            },
        )
    }

    private fun loadCurrentEditorContextGraphAsync(
        requestId: Long,
        deferUntilSmart: Boolean,
        editor: Editor? = null,
    ) {
        if (deferUntilSmart && subjectResolutionWorkflow.shouldDeferCurrentMethodResolutionUntilSmart()) {
            dependencies.emitFeedback(
                ApplicationFeedbackLevel.INFO,
                "项目正在索引，已在索引完成后继续分析当前编辑器上下文链路。",
            )
            DumbService.getInstance(project).smartInvokeLater(
                {
                    if (!project.isDisposed && requestCoordinator.isLatest(requestId)) {
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

        val handle = runCatching { subjectResolutionWorkflow.locateCurrentSubject(editor) }.getOrElse { throwable ->
            dependencies.logger.warn("解析当前编辑器上下文失败", throwable)
            dependencies.emitFeedback(
                ApplicationFeedbackLevel.ERROR,
                "加载当前节点关联图失败：${throwable.message ?: throwable.javaClass.simpleName}",
            )
            return
        }

        when (handle) {
            null -> {
                dependencies.emitFeedback(
                    ApplicationFeedbackLevel.WARNING,
                    "当前光标不在可识别的方法或资源节点内，请把光标放到方法、Mapper SQL、配置项、Markdown 或 SQL 文件内容上。",
                )
            }

            is CodeSubjectHandle -> {
                dependencies.emitFeedback(
                    ApplicationFeedbackLevel.INFO,
                    "正在分析当前编辑器上下文链路：${handle.displayName}",
                )
                analysisWorkflow.submit(
                    handle = handle,
                    requestId = requestId,
                    source = CURRENT_METHOD_SOURCE,
                )
            }

            is ResourceSubjectHandle -> {
                dependencies.emitFeedback(
                    ApplicationFeedbackLevel.INFO,
                    "正在分析当前节点关联图：${handle.displayName}",
                )
                analysisWorkflow.submit(
                    handle = handle,
                    requestId = requestId,
                    source = CURRENT_CONTEXT_SOURCE,
                )
            }
        }
    }

    private companion object {
        private const val CURRENT_METHOD_SOURCE = SubjectGraphUseCase.CURRENT_METHOD_SOURCE
        private const val CURRENT_CONTEXT_SOURCE = SubjectGraphUseCase.CURRENT_CONTEXT_SOURCE
    }
}
