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
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project

/**
 * 当前主体链路 workflow 门面。
 *
 * 具体职责已拆分到 `application.workflow.subject`：
 * 主体定位、异步分析、结果应用和节点追加分别由专门子 workflow 处理。
 */
internal class SubjectGraphWorkflow(
    /** 当前 IntelliJ 项目句柄，用于访问 DumbService 等平台能力以及判断项目生命周期。 */
    private val project: Project,
    /** 提供当前编辑器快照的端口，用于读取当前选中节点、方法签名等上下文。 */
    snapshotProvider: EditorSnapshotProvider,
    /** 异步请求生命周期支持，负责请求排队、取消、去抖等通用编排逻辑。 */
    asyncRequestLifecycle: AsyncRequestLifecycleSupport,
    /** 延迟获取主体定位器，避免在初始化阶段过早依赖语义索引。 */
    subjectLocatorProvider: () -> SubjectLocator,
    /** 延迟获取语义分析器，便于在 dumb 模式或后台线程按需构造。 */
    semanticAnalyzerProvider: () -> SemanticAnalyzer,
    /** 延迟获取分析结果工厂，根据分析产物构造可用于 UI 展示的展示态结果。 */
    analysisOutcomeFactoryProvider: () -> AnalysisOutcomeFactory,
    /** 用于将光标处的方法或资源包装成统一主体句柄的工厂。 */
    codeSubjectHandleFactory: CodeSubjectHandleFactory,
    /** 将分析得到的图数据提交到工作区持久化并触发后续刷新。 */
    workspaceGraphCommitter: WorkspaceGraphCommitter,
    /** 应用层事件出口，向 UI 广播链路分析过程中的状态变化。 */
    eventSink: GraphEditorApplicationEventSink,
    /** 在状态变化或重置时回调上层作废当前的 QA 请求。 */
    onInvalidateQaRequests: () -> Unit,
    /** 用于在链路分析关键节点输出诊断日志的回调。 */
    onLogGraphDiagnostics: (String, GraphDocument?) -> Unit,
    /** 可选的运行时追踪回调，用于排查 workflow 执行顺序。 */
    runtimeTrace: ((() -> String) -> Unit)? = null,
    /** 共享日志器，输出警告、错误以及调试信息。 */
    logger: com.intellij.openapi.diagnostic.Logger,
) {
    /** 主题链路相关的纯领域用例，承担展示模式判定、结果分类等不依赖平台的逻辑。 */
    private val useCase = SubjectGraphUseCase()
    /** 汇总各端口和回调，供子 workflow 共享访问而不必逐个透传。 */
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
    /** 在多次分析之间保留最近一次结果、投影设置以及用户期望的展示模式。 */
    private val state = SubjectGraphWorkflowState()
    /** 协调请求编号，确保过期的异步结果不会覆盖更新的状态。 */
    private val requestCoordinator = SubjectGraphRequestCoordinator()
    /** 负责从光标位置定位当前的主体（方法、Mapper SQL、配置项等）。 */
    private val subjectResolutionWorkflow = SubjectResolutionWorkflow(
        dependencies = dependencies,
        requestCoordinator = requestCoordinator,
    )
    /** 把语义分析结果落到工作区并刷新 UI，承担数据合并、提交和反馈。 */
    private val resultApplier = SubjectAnalysisResultApplier(
        dependencies = dependencies,
        state = state,
        useCase = useCase,
    )
    /** 编排异步语义分析任务，处理线程切换、取消和最终的结果回调。 */
    private val analysisWorkflow = SubjectAnalysisWorkflow(
        dependencies = dependencies,
        state = state,
        requestCoordinator = requestCoordinator,
        resultApplier = resultApplier,
        useCase = useCase,
    )
    /** 负责在不重新分析链路的情况下，把当前主体追加为节点。 */
    private val nodeAppendWorkflow = SubjectNodeAppendWorkflow(
        dependencies = dependencies,
        subjectResolutionWorkflow = subjectResolutionWorkflow,
        useCase = useCase,
    )

    /** 释放占用的异步资源，主要用于销毁当前正在执行的语义分析任务。 */
    fun dispose() {
        requestCoordinator.cancelCurrentAnalysis()
    }

    /** 清空最近一次分析结果的内存缓存，强制下一次请求重新分析。 */
    fun clearLastAnalysisCache() {
        state.clearLastAnalysisCache()
    }

    /** 预置用户期望的展示模式，用于在后续请求命中缓存时立即按该模式呈现。返回是否成功接受。 */
    fun prepareRequestedAnalysisDisplayMode(displayMode: AnalysisDisplayMode): Boolean {
        return state.prepareRequestedDisplayMode(displayMode)
    }

    /** 主动请求切换展示模式：若已有缓存则直接套用，否则记录期望值等待下次分析完成后生效。 */
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

    /** 异步加载当前编辑器光标所在主体对应的链路图，会先重置投影设置。 */
    fun loadCurrentEditorContextGraphAsync() {
        state.resetProjectionSettings()
        val requestId = requestCoordinator.beginRequest()
        loadCurrentEditorContextGraphAsync(
            requestId = requestId,
            deferUntilSmart = true,
        )
    }

    /** 将当前方法主体追加为图谱节点；未指定句柄时由内部解析当前光标位置。返回是否成功追加。 */
    fun addCurrentMethodNode(handle: CodeSubjectHandle? = null): Boolean {
        return nodeAppendWorkflow.addCurrentMethodNode(handle)
    }

    /** 把当前编辑器中的主体（方法或资源）追加为节点；索引未完成时延迟到 smart 模式重试。 */
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

    /** 用户点击摘要节点上的"继续展开"时触发：展开该节点的下级链路并刷新结果。 */
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

    /** 在选中方法发生变化时广播事件，便于 UI 同步选中状态。 */
    fun pushSelectedMethod(signature: String) {
        dependencies.emit(GraphEditorApplicationEvent.SelectedMethodChanged(signature))
    }

    /** 仅预判当前光标处所属的主体类型（方法/Mapper SQL 等），不触发实际分析，用于 UI 提示。 */
    fun previewCurrentEditorSubjectKind(): SubjectPreviewKind? {
        return subjectResolutionWorkflow.previewCurrentEditorSubjectKind()
    }

    /** 调试入口：直接按方法签名异步分析真实链路，绕过光标定位步骤。 */
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

    /** 实际执行编辑器上下文加载的实现：处理 dumb 模式延迟、定位主体并触发对应分析任务。 */
    private fun loadCurrentEditorContextGraphAsync(
        requestId: Long,
        deferUntilSmart: Boolean,
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
                        )
                    }
                },
                ModalityState.defaultModalityState(),
            )
            return
        }

        val handle = runCatching { subjectResolutionWorkflow.locateCurrentSubject() }.getOrElse { throwable ->
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
        /** 表示当前链路来源为"当前方法"的标记，用于诊断与结果归因。 */
        private const val CURRENT_METHOD_SOURCE = SubjectGraphUseCase.CURRENT_METHOD_SOURCE
        /** 表示当前链路来源为"当前编辑器上下文（资源主体）"的标记。 */
        private const val CURRENT_CONTEXT_SOURCE = SubjectGraphUseCase.CURRENT_CONTEXT_SOURCE
    }
}
