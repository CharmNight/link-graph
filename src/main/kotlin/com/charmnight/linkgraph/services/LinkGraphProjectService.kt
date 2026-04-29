package com.charmnight.linkgraph.services
import com.charmnight.linkgraph.codegen.CodeDraftWriterService
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.llm.GraphAuditContext
import com.charmnight.linkgraph.llm.GraphAuditPatchService
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphBeautificationService
import com.charmnight.linkgraph.llm.GraphBeautificationFollowUpContext
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.llm.GraphGenerationService
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.artifact.AgentArtifactStoreService
import com.charmnight.linkgraph.llm.remoteConnectionOrNull
import com.charmnight.linkgraph.llm.usesRemoteProvider
import com.charmnight.linkgraph.llm.DefaultGraphBeautificationService
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.navigation.SourceNavigationService
import com.charmnight.linkgraph.semantic.SemanticAnalyzer
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.semantic.subject.CaretSubjectLocator
import com.charmnight.linkgraph.semantic.subject.canonicalTypeText
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandle
import com.charmnight.linkgraph.semantic.subject.CodeSubjectHandleFactory
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectHandle
import com.charmnight.linkgraph.semantic.subject.SubjectLocator
import com.charmnight.linkgraph.semantic.subject.SubjectPreviewKind
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcomeFactory
import com.charmnight.linkgraph.semantic.policy.ProjectionPolicy
import com.charmnight.linkgraph.semantic.policy.SemanticCapturePolicy
import com.charmnight.linkgraph.semantic.policy.TraversalBudgetPolicy
import com.charmnight.linkgraph.semantic.provider.SemanticProviderRegistry
import com.charmnight.linkgraph.semantic.provider.code.CodeSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.MarkdownSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.MyBatisXmlSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.SqlSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.XmlResourceSemanticProvider
import com.charmnight.linkgraph.semantic.provider.resource.YamlPropertiesSemanticProvider
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.charmnight.linkgraph.workbench.StepGranularity
import com.charmnight.linkgraph.ui.DraftPatchApplyResult
import com.charmnight.linkgraph.ui.OperationFeedbackLevel
import com.charmnight.linkgraph.ui.GraphLayoutPosition
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.CandidateDraftChangeStatus
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchService
import com.charmnight.linkgraph.workbench.RiskResolutionService
import com.charmnight.linkgraph.workbench.RiskResolutionStatus
import com.charmnight.linkgraph.workbench.WorkbenchLayoutPreferencesService
import com.charmnight.linkgraph.workbench.isEligibleForDraftConfirmation
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiMethod
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.concurrency.CancellablePromise
import org.jetbrains.annotations.TestOnly

/** 解析 `dense12` 这类调试模式参数的正则表达式。 */
private val DENSE_DEBUG_MODE_REGEX = Regex("^dense(\\d+)$")

/** 从调试模式字符串中提取允许展示的节点数量。 */
internal fun parseDenseDebugNodeCount(mode: String): Int? = DENSE_DEBUG_MODE_REGEX
    .matchEntire(mode)
    ?.groupValues
    ?.getOrNull(1)
    ?.toIntOrNull()
    ?.coerceIn(2, 400)

/**
 * Link Graph 在项目级别的总入口。
 * IDEA action、JCEF bridge 与状态服务最终都会汇聚到这里，便于统一处理导入、导出、diff 和跳转。
 */
@Service(Service.Level.PROJECT)
class LinkGraphProjectService(
    /** 当前服务所属项目。 */
    private val project: Project,
) : Disposable {
    /** 草稿补丁预览的来源类型。 */
    enum class DraftPatchPreviewSource {
        AUDIT,
        DIFF_REVIEW,
        LAST_APPLIED,
    }

    private val testOverrides: LinkGraphProjectTestOverrides by lazy(LazyThreadSafetyMode.NONE) {
        project.getService(LinkGraphProjectTestOverrides::class.java)
    }

    /** 负责把编辑器上下文封装成代码主题句柄的工厂。 */
    private val codeSubjectHandleFactory: CodeSubjectHandleFactory by lazy(LazyThreadSafetyMode.NONE) { CodeSubjectHandleFactory() }
    /** 默认的主题定位器实现。 */
    private val defaultSubjectLocator: SubjectLocator by lazy(LazyThreadSafetyMode.NONE) { CaretSubjectLocator() }
    /** 默认的语义分析器实现。 */
    private val defaultSemanticAnalyzer: SemanticAnalyzer by lazy(LazyThreadSafetyMode.NONE) {
        SemanticAnalyzer(
            registry = SemanticProviderRegistry(
                listOf(
                    CodeSemanticProvider(),
                    MyBatisXmlSemanticProvider(),
                    XmlResourceSemanticProvider(),
                    YamlPropertiesSemanticProvider(),
                    MarkdownSemanticProvider(),
                    SqlSemanticProvider(),
                ),
            ),
            project = project,
        )
    }
    /** 默认的分析结果工厂实现。 */
    private val defaultAnalysisOutcomeFactory: AnalysisOutcomeFactory by lazy(LazyThreadSafetyMode.NONE) {
        AnalysisOutcomeFactory(
            runtimeTrace = runtimeSupport.runtimeTraceSink(),
        )
    }
    /** 当前生效的主题定位器。 */
    private val subjectLocator: SubjectLocator
        get() = testOverrides.subjectLocator ?: defaultSubjectLocator
    /** 当前生效的语义分析器。 */
    private val semanticAnalyzer: SemanticAnalyzer
        get() = testOverrides.semanticAnalyzer ?: defaultSemanticAnalyzer
    /** 当前生效的分析结果工厂。 */
    private val analysisOutcomeFactory: AnalysisOutcomeFactory
        get() = testOverrides.analysisOutcomeFactory ?: defaultAnalysisOutcomeFactory
    /** Mermaid 导入器。 */
    private val mermaidImporter by lazy { MermaidImporter() }
    /** Mermaid 校验器。 */
    private val mermaidValidator by lazy { MermaidValidator() }
    /** Mermaid 导出器。 */
    private val mermaidExporter by lazy { MermaidExporter() }
    /** 图 diff 比较器。 */
    private val graphDiffer by lazy { GraphDiffer() }
    /** 同步预览规划器。 */
    private val syncPreviewPlanner by lazy { SyncPreviewPlanner() }
    /** 图补丁应用服务。 */
    private val graphPatchApplyService by lazy { GraphPatchApplyService() }
    /** 图生成服务。 */
    private val graphGenerationService by lazy { GraphGenerationService() }
    /** 图问答补丁服务。 */
    private val graphAuditPatchService by lazy { GraphAuditPatchService() }
    /** 统一草稿层服务。 */
    private val draftWorkbenchService by lazy { DraftWorkbenchService() }
    /** 风险决策与阶段准入服务。 */
    private val riskResolutionService by lazy { RiskResolutionService() }
    /** diff 审核补丁服务。 */
    private val graphDiffPatchService by lazy { GraphDiffPatchService() }
    /** 链路讲解服务。 */
    private val graphBeautificationService: GraphBeautificationService by lazy { DefaultGraphBeautificationService() }
    /** 代码草稿生成服务。 */
    private val codeGenerationService by lazy { CodeGenerationService() }
    /** 代码草稿写入服务。 */
    private val codeDraftWriterService by lazy { CodeDraftWriterService(project) }
    /** 已确认候选变更 workflow。 */
    private val confirmedDraftChangeWorkflow by lazy { ConfirmedDraftChangeWorkflow(draftWorkbenchService, graphPatchApplyService) }
    /** 图结构诊断日志。 */
    private val graphDiagnosticsLogger by lazy { GraphDiagnosticsLogger(logger) }
    /** 线程切换、设置解析与 trace 运行时支持。 */
    private val runtimeSupport by lazy(LazyThreadSafetyMode.NONE) {
        LinkGraphProjectRuntimeSupport(
            project = project,
            logger = logger,
            openSettingsOverrideProvider = { testOverrides.openSettings },
            effectiveGenerationSettingsOverrideProvider = { testOverrides.effectiveGenerationSettings },
        )
    }
    /** 已确认候选变更同步 workflow。 */
    private val confirmedDraftChangeSyncWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        ConfirmedDraftChangeSyncWorkflow(
            stateServiceProvider = runtimeSupport::stateService,
            confirmedDraftChangeWorkflow = confirmedDraftChangeWorkflow,
            riskResolutionService = riskResolutionService,
            graphDiagnosticsLogger = graphDiagnosticsLogger,
            artifactStoreProvider = { artifactStore },
            invalidateAuditRequests = ::invalidateAuditRequests,
            mutateEditorStateBatch = { block -> mutateEditorStateBatch(block = block) },
            logger = logger,
            runtimeTrace = runtimeSupport.eagerRuntimeTraceSink(),
        )
    }
    /** 调试图工厂。 */
    private val debugGraphFactory by lazy { DebugGraphFactory() }
    /** 项目级编辑器状态会话。 */
    internal val editorSession by lazy(LazyThreadSafetyMode.NONE) {
        ProjectEditorSession(
            stateService = runtimeSupport.stateService(),
            onBrowserSyncRequested = project.getService(GraphEditorSyncNotifier::class.java)::requestSync,
            runtimeTrace = runtimeSupport.runtimeTraceSink(),
        )
    }
    /** 跨阶段共享的 runtime artifact store。 */
    private val artifactStore by lazy(LazyThreadSafetyMode.NONE) {
        project.getService(AgentArtifactStoreService::class.java).artifactStore
    }

    /** 图工作区流程。 */
    internal val graphWorkspaceWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        GraphWorkspaceWorkflow(
            session = editorSession,
            mermaidImporter = mermaidImporter,
            mermaidValidator = mermaidValidator,
            mermaidExporter = mermaidExporter,
            graphDiffer = graphDiffer,
            syncPreviewPlanner = syncPreviewPlanner,
            copyToClipboard = { exported ->
                runCatching {
                    CopyPasteManager.getInstance().setContents(StringSelection(exported))
                    true
                }.getOrDefault(false)
            },
        )
    }

    /** 草稿补丁流程。 */
    internal val draftPatchWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        DraftPatchWorkflow(
            session = editorSession,
            graphPatchApplyService = graphPatchApplyService,
            onMarkGraphChanged = { graph, preserveDraftPatchUndo, syncBrowser ->
                markGraphChanged(
                    graph = graph,
                    preserveDraftPatchUndo = preserveDraftPatchUndo,
                    syncBrowser = syncBrowser,
                )
            },
        )
    }

    /** 异步请求生命周期支持。 */
    private val asyncRequestLifecycle by lazy(LazyThreadSafetyMode.NONE) {
        AsyncRequestLifecycleSupport(
            project = project,
            session = editorSession,
            timeoutOverrideProvider = { testOverrides.asyncRequestTimeoutMillis },
        )
    }

    /** 规划、问答与链路讲解上下文工厂。 */
    private val planningContextFactory by lazy(LazyThreadSafetyMode.NONE) {
        PlanningContextFactory(
            graphDiffer = graphDiffer,
            syncPreviewPlanner = syncPreviewPlanner,
            graphGenerationService = graphGenerationService,
            settingsProvider = runtimeSupport::effectiveGenerationSettings,
            projectBasePathProvider = { project.basePath },
        )
    }

    /** 当前主体分析与补图流程。 */
    private val subjectGraphWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        SubjectGraphWorkflow(
            project = project,
            session = editorSession,
            planningContextFactory = planningContextFactory,
            asyncRequestLifecycle = asyncRequestLifecycle,
            subjectLocatorProvider = { subjectLocator },
            semanticAnalyzerProvider = { semanticAnalyzer },
            analysisOutcomeFactoryProvider = { analysisOutcomeFactory },
            codeSubjectHandleFactory = codeSubjectHandleFactory,
            onMarkGraphChanged = { graph, selectedMethodSignature, preserveDraftPatchUndo, syncBrowser ->
                markGraphChanged(
                    graph = graph,
                    selectedMethodSignature = selectedMethodSignature,
                    preserveDraftPatchUndo = preserveDraftPatchUndo,
                    syncBrowser = syncBrowser,
                )
            },
            onInvalidateAuditRequests = ::invalidateAuditRequests,
            onLogGraphDiagnostics = graphDiagnosticsLogger::log,
            runtimeTrace = runtimeSupport.runtimeTraceSink(),
            logger = logger,
        )
    }

    /** 实现计划与代码草稿流程。 */
    internal val generationWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        GenerationWorkflow(
            project = project,
            session = editorSession,
            planningContextFactory = planningContextFactory,
            graphGenerationService = graphGenerationService,
            codeGenerationService = codeGenerationService,
            codeDraftWriterService = codeDraftWriterService,
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = runtimeSupport::effectiveGenerationSettings,
            asyncRequestLifecycle = asyncRequestLifecycle,
            logger = logger,
            artifactStoreProvider = { artifactStore },
        )
    }

    /** 问答、差异分析与链路讲解流程。 */
    internal val reviewWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        ReviewWorkflow(
            project = project,
            session = editorSession,
            planningContextFactory = planningContextFactory,
            graphAuditPatchService = graphAuditPatchService,
            graphDiffPatchService = graphDiffPatchService,
            graphBeautificationService = graphBeautificationService,
            graphDiffer = graphDiffer,
            settingsProvider = runtimeSupport::effectiveGenerationSettings,
            auditExecutorOverrideProvider = { testOverrides.auditExecutor },
            asyncRequestLifecycle = asyncRequestLifecycle,
            logger = logger,
            artifactStoreProvider = { artifactStore },
        )
    }

    /** 源码跳转与设置页流程。 */
    internal val sourceNavigationWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        SourceNavigationWorkflow(
            project = project,
            session = editorSession,
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            navigationNodeFinder = ::findTrustedNavigationNode,
            showSettingsDialog = runtimeSupport::openSettingsDialog,
            logger = logger,
        )
    }
    /** 调试模式 workflow。 */
    private val projectDebugWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        ProjectDebugWorkflow(
            logger = logger,
            debugGraphFactory = debugGraphFactory,
            subjectGraphWorkflow = subjectGraphWorkflow,
            invalidateAuditRequests = ::invalidateAuditRequests,
            mutateEditorStateBatch = { block -> mutateEditorStateBatch(block = block) },
        )
    }

    /** 释放当前分析任务。 */
    override fun dispose() {
        subjectGraphWorkflow.dispose()
    }

    /** 使所有异步分析类请求失效。 */
    private fun invalidateAuditRequests() {
        asyncRequestLifecycle.invalidateRequests()
    }

    /** 统一处理工作图变更及浏览器同步。 */
    private fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        syncBrowser: Boolean = true,
    ) {
        subjectGraphWorkflow.clearLastAnalysisCache()
        invalidateAuditRequests()
        editorSession.markGraphChanged(
            graph = graph,
            selectedMethodSignature = selectedMethodSignature,
            preserveDraftPatchUndo = preserveDraftPatchUndo,
            syncBrowser = syncBrowser,
        )
    }

    internal fun resetWorkspaceGraphContext() {
        subjectGraphWorkflow.clearLastAnalysisCache()
        invalidateAuditRequests()
    }

    /** 在同步会话中批量修改编辑器状态。 */
    private fun <T> mutateEditorStateBatch(
        syncBrowser: Boolean = true,
        block: com.charmnight.linkgraph.ui.GraphEditorStateMutationContext.() -> T,
    ): T {
        return editorSession.mutateBatch(syncBrowser = syncBrowser, block = block)
    }

    /** 切换分析展示模式。 */
    fun requestAnalysisDisplayMode(displayMode: AnalysisDisplayMode) {
        subjectGraphWorkflow.requestAnalysisDisplayMode(displayMode)
    }

    /** 异步加载当前编辑器上下文链路。 */
    fun loadCurrentEditorContextGraphAsync(editor: Editor? = null) {
        subjectGraphWorkflow.loadCurrentEditorContextGraphAsync(editor)
    }

    /** 把当前方法节点追加到画布中。 */
    fun addCurrentMethodNode(handle: CodeSubjectHandle? = null): Boolean {
        return subjectGraphWorkflow.addCurrentMethodNode(handle)
    }

    /** 把当前编辑器上下文对应的节点追加到画布中。 */
    fun addCurrentEditorContextNode(): Boolean {
        return subjectGraphWorkflow.addCurrentEditorContextNode()
    }

    /** 导入 Mermaid 文本并刷新编辑器状态。 */
    fun importMermaid(mermaid: String): GraphDocument {
        subjectGraphWorkflow.clearLastAnalysisCache()
        invalidateAuditRequests()
        return graphWorkspaceWorkflow.importMermaid(mermaid)
    }

    /** 导出当前可见图或设计基线为 Mermaid 文本。 */
    fun exportMermaid(): String {
        return graphWorkspaceWorkflow.exportMermaid()
    }

    /** 进入代码事实图与设计基线的差异模式。 */
    fun showDiffMode(): GraphDifferResult? {
        invalidateAuditRequests()
        return graphWorkspaceWorkflow.showDiffMode()
    }

    /** 生成当前图的同步预览列表。 */
    fun requestSyncPreview(): List<SyncPreviewItem> {
        return graphWorkspaceWorkflow.requestSyncPreview()
    }

    /** 基于当前规划上下文生成实现计划。 */
    fun requestGenerationPlan() {
        generationWorkflow.requestGenerationPlan()
    }

    /** 针对当前实现建议继续追问。 */
    fun requestGenerationPlanDiscussion(
        question: String,
        focusItemId: String? = null,
    ) {
        generationWorkflow.requestGenerationPlanDiscussion(question, focusItemId)
    }

    /** 基于当前规划上下文生成代码草稿。 */
    fun requestCodeDrafts() {
        generationWorkflow.requestCodeDrafts()
    }

    /** 在前端展示草稿补丁预览。 */
    fun previewDraftPatch(patch: GraphPatch) {
        draftPatchWorkflow.previewDraftPatch(patch)
    }

    /** 把当前草稿补丁预览真正应用到工作图。 */
    fun applyDraftPatchPreview(operationIds: Set<String>? = null): GraphDocument? {
        return draftPatchWorkflow.applyDraftPatchPreview(operationIds)
    }

    /** 清空当前草稿补丁预览。 */
    fun clearDraftPatchPreview() {
        draftPatchWorkflow.clearDraftPatchPreview()
    }

    /** 从指定来源恢复草稿补丁预览。 */
    fun restoreDraftPatchPreview(source: DraftPatchPreviewSource): GraphPatch? {
        return draftPatchWorkflow.restoreDraftPatchPreview(source)
    }

    /** 撤销上一次草稿补丁应用，并恢复预览状态。 */
    fun undoLastDraftPatchApply(): GraphDocument? {
        return draftPatchWorkflow.undoLastDraftPatchApply()
    }

    /** 基于当前工作图发起同步问答。 */
    fun requestAudit(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
        sourceThreadId: String? = null,
    ): GraphPatchResult {
        return reviewWorkflow.requestAudit(question, selectedNodeIds, sourceThreadId)
    }

    /** 直接重试最近一次失败的问答请求。 */
    fun retryLastAuditRequestAsync() {
        reviewWorkflow.retryLastAuditRequestAsync()
    }

    /** 对风险线程写入人工决策。 */
    fun resolveInvestigationThread(
        threadId: String,
        status: RiskResolutionStatus,
        note: String = "",
    ) {
        reviewWorkflow.resolveInvestigationThread(threadId, status, note)
    }

    /** 确认一条问答候选变更并写入统一草稿层。 */
    fun confirmAuditCandidateChange(changeId: String): DraftWorkbenchEntry? {
        return confirmedDraftChangeSyncWorkflow.confirm(changeId)
    }

    /** 撤销一条已经确认的问答候选变更，并恢复待确认状态。 */
    fun unconfirmAuditCandidateChange(changeId: String): DraftWorkbenchEntry? {
        return confirmedDraftChangeSyncWorkflow.unconfirm(changeId)
    }

    /** 基于代码事实图和设计基线发起同步差异问答。 */
    fun requestDiffReview(
        question: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ): GraphPatchResult? {
        return reviewWorkflow.requestDiffReview(question, selectedDiffItemIds)
    }

    /** 生成当前链路图的讲解与润色说明。 */
    fun requestGraphBeautification(
        goal: String = "",
        preferredStyle: String? = null,
        explanationFocus: String? = null,
        followUp: GraphBeautificationFollowUpContext? = null,
        granularity: StepGranularity = StepGranularity.BUSINESS,
    ): GraphBeautificationResult {
        return reviewWorkflow.requestGraphBeautification(goal, preferredStyle, explanationFocus, followUp, granularity)
    }

    /** 展开摘要节点，重新以更宽的预算提取当前主体链路。 */
    fun requestExpandOverflowNode(nodeId: String) {
        subjectGraphWorkflow.requestExpandOverflowNode(nodeId)
    }

    /** 打开插件设置页。 */
    fun openSettings() {
        sourceNavigationWorkflow.openSettings()
    }

    /** 直接按节点信息跳转源码。 */
    fun navigate(node: GraphNode): SourceNavigationService.NavigationTarget? {
        return sourceNavigationWorkflow.navigate(node)
    }

    /** 把选中方法签名压入前端状态。 */
    fun pushSelectedMethod(signature: String) {
        subjectGraphWorkflow.pushSelectedMethod(signature)
    }

    /** 预览当前编辑器主体类型，不提交文档内容。 */
    fun previewCurrentEditorSubjectKind(editor: Editor? = null): SubjectPreviewKind? {
        return subjectGraphWorkflow.previewCurrentEditorSubjectKind(editor)
    }

    /** 把最新状态同步到浏览器面板。 */
    /** 在调试模式下按环境变量预设启动后的展示模式。 */
    @JvmName("prepareDebugRequestedAnalysisDisplayModeIfPresent")
    internal fun prepareDebugRequestedAnalysisDisplayModeIfPresent() {
        projectDebugWorkflow.prepareDebugRequestedAnalysisDisplayModeIfPresent(DEBUG_ANALYSIS_DISPLAY_MODE_ENV)
    }

    /** 按调试模式注入预置链路图。 */
    @JvmName("loadDebugGraph")
    internal fun loadDebugGraph(mode: String) {
        projectDebugWorkflow.loadDebugGraph(mode)
    }

    /** 按方法签名异步载入真实方法链路图。 */
    @JvmName("loadDebugMethodGraphBySignatureAsync")
    internal fun loadDebugMethodGraphBySignatureAsync(signature: String) {
        projectDebugWorkflow.loadDebugMethodGraphBySignatureAsync(signature)
    }

    companion object {
        /** 调试启动时指定展示模式的环境变量。 */
        private const val DEBUG_ANALYSIS_DISPLAY_MODE_ENV = "LINKGRAPH_DEBUG_ANALYSIS_DISPLAY_MODE"
        /** 服务日志记录器。 */
        private val logger = Logger.getInstance(LinkGraphProjectService::class.java)
    }
}

/** 按优先级从多个图快照里查找指定节点。 */
internal fun findNavigationNode(
    snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    nodeId: String,
): GraphNode? {
    return sequenceOf(
        currentVisibleGraph(snapshot),
        currentWorkingGraph(snapshot),
        snapshot.semanticFactGraph,
        snapshot.designBaselineGraph,
    )
        .filterNotNull()
        .flatMap { graph -> graph.nodes.asSequence() }
        .firstOrNull { node -> node.id == nodeId }
}

/** 仅从后端可信导航索引中查找允许跳转的节点。 */
internal fun findTrustedNavigationNode(
    snapshot: com.charmnight.linkgraph.ui.GraphEditorStateSnapshot,
    nodeId: String,
): GraphNode? {
    return snapshot.trustedNavigationNodes[nodeId]
}
