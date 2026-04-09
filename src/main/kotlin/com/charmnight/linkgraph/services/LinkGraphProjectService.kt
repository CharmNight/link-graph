package com.charmnight.linkgraph.services
import com.charmnight.linkgraph.codegen.CodeDraftWriterService
import com.charmnight.linkgraph.codegen.CodeGenerationService
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.llm.GraphAuditContext
import com.charmnight.linkgraph.llm.GraphAuditPatchService
import com.charmnight.linkgraph.llm.GraphBeautificationResult
import com.charmnight.linkgraph.llm.GraphBeautificationService
import com.charmnight.linkgraph.llm.GraphDiffPatchService
import com.charmnight.linkgraph.llm.GraphGenerationService
import com.charmnight.linkgraph.llm.GraphPatchResult
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.remoteConnectionOrNull
import com.charmnight.linkgraph.llm.usesRemoteProvider
import com.charmnight.linkgraph.llm.DefaultGraphBeautificationService
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsConfigurable
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.settings.LinkGraphSettingsService
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
import com.charmnight.linkgraph.ui.DraftPatchApplyResult
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.GraphEditorStateService.OperationFeedbackLevel
import com.charmnight.linkgraph.ui.GraphLayoutPosition
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

    /** 测试环境下替换打开设置行为的钩子。 */
    @Volatile
    @TestOnly
    var testOpenSettingsOverride: (() -> Unit)? = null

    /** 测试环境下替换主题定位器的钩子。 */
    @Volatile
    @TestOnly
    var testSubjectLocatorOverride: SubjectLocator? = null

    /** 测试环境下替换语义分析器的钩子。 */
    @Volatile
    @TestOnly
    var testSemanticAnalyzerOverride: SemanticAnalyzer? = null

    /** 测试环境下替换分析结果工厂的钩子。 */
    @Volatile
    @TestOnly
    var testAnalysisOutcomeFactoryOverride: AnalysisOutcomeFactory? = null

    /** 测试环境下替换图审计执行器的钩子。 */
    @Volatile
    @TestOnly
    var testAuditExecutorOverride: ((GraphAuditContext, String) -> GraphPatchResult)? = null

    /** 测试环境下替换生效生成设置的钩子。 */
    @Volatile
    @TestOnly
    var testEffectiveGenerationSettingsOverride: LinkGraphSettingsState? = null

    /** 测试环境下替换异步请求超时时间的钩子。 */
    @Volatile
    @TestOnly
    var testAsyncRequestTimeoutMillisOverride: Long? = null

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
        AnalysisOutcomeFactory()
    }
    /** 当前生效的主题定位器。 */
    private val subjectLocator: SubjectLocator
        get() = testSubjectLocatorOverride ?: defaultSubjectLocator
    /** 当前生效的语义分析器。 */
    private val semanticAnalyzer: SemanticAnalyzer
        get() = testSemanticAnalyzerOverride ?: defaultSemanticAnalyzer
    /** 当前生效的分析结果工厂。 */
    private val analysisOutcomeFactory: AnalysisOutcomeFactory
        get() = testAnalysisOutcomeFactoryOverride ?: defaultAnalysisOutcomeFactory
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
    /** 图审计补丁服务。 */
    private val graphAuditPatchService by lazy { GraphAuditPatchService() }
    /** diff 审核补丁服务。 */
    private val graphDiffPatchService by lazy { GraphDiffPatchService() }
    /** 链路讲解服务。 */
    private val graphBeautificationService: GraphBeautificationService by lazy { DefaultGraphBeautificationService() }
    /** 代码草稿生成服务。 */
    private val codeGenerationService by lazy { CodeGenerationService() }
    /** 代码草稿写入服务。 */
    private val codeDraftWriterService by lazy { CodeDraftWriterService() }
    /** 调试图工厂。 */
    private val debugGraphFactory by lazy { DebugGraphFactory() }
    /** 项目级编辑器状态会话。 */
    private val editorSession by lazy(LazyThreadSafetyMode.NONE) {
        ProjectEditorSession(
            stateService = stateService(),
            onBrowserSyncRequested = project.getService(GraphEditorSyncNotifier::class.java)::requestSync,
        )
    }

    /** 图工作区流程。 */
    private val graphWorkspaceWorkflow by lazy(LazyThreadSafetyMode.NONE) {
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
    private val draftPatchWorkflow by lazy(LazyThreadSafetyMode.NONE) {
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
            timeoutOverrideProvider = { testAsyncRequestTimeoutMillisOverride },
        )
    }

    /** 规划、审计与链路讲解上下文工厂。 */
    private val planningContextFactory by lazy(LazyThreadSafetyMode.NONE) {
        PlanningContextFactory(
            graphDiffer = graphDiffer,
            syncPreviewPlanner = syncPreviewPlanner,
            graphGenerationService = graphGenerationService,
            settingsProvider = ::effectiveGenerationSettings,
        )
    }

    /** 当前主体分析与补图流程。 */
    private val subjectGraphWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        SubjectGraphWorkflow(
            project = project,
            session = editorSession,
            planningContextFactory = planningContextFactory,
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
            onLogGraphDiagnostics = ::logGraphDiagnostics,
            logger = logger,
        )
    }

    /** 实现计划与代码草稿流程。 */
    private val generationWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        GenerationWorkflow(
            project = project,
            session = editorSession,
            planningContextFactory = planningContextFactory,
            graphGenerationService = graphGenerationService,
            codeGenerationService = codeGenerationService,
            codeDraftWriterService = codeDraftWriterService,
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            settingsProvider = ::effectiveGenerationSettings,
            asyncRequestLifecycle = asyncRequestLifecycle,
            logger = logger,
        )
    }

    /** 审计、差异分析与链路讲解流程。 */
    private val reviewWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        ReviewWorkflow(
            project = project,
            session = editorSession,
            planningContextFactory = planningContextFactory,
            graphAuditPatchService = graphAuditPatchService,
            graphDiffPatchService = graphDiffPatchService,
            graphBeautificationService = graphBeautificationService,
            graphDiffer = graphDiffer,
            settingsProvider = ::effectiveGenerationSettings,
            auditExecutorOverrideProvider = { testAuditExecutorOverride },
            asyncRequestLifecycle = asyncRequestLifecycle,
            logger = logger,
        )
    }

    /** 源码跳转与设置页流程。 */
    private val sourceNavigationWorkflow by lazy(LazyThreadSafetyMode.NONE) {
        SourceNavigationWorkflow(
            project = project,
            session = editorSession,
            sourceNavigationServiceProvider = { project.getService(SourceNavigationService::class.java) },
            navigationNodeFinder = ::findNavigationNode,
            showSettingsDialog = {
                computeOnIdeThread {
                    testOpenSettingsOverride?.invoke() ?: ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, LinkGraphSettingsConfigurable::class.java)
                }
            },
            logger = logger,
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

    /** 在同步会话中批量修改编辑器状态。 */
    private fun <T> mutateEditorStateBatch(
        syncBrowser: Boolean = true,
        block: GraphEditorStateSyncSession.() -> T,
    ): T {
        return editorSession.mutateBatch(syncBrowser = syncBrowser, block = block)
    }

    /** 切换分析展示模式。 */
    fun requestAnalysisDisplayMode(displayMode: AnalysisDisplayMode) {
        subjectGraphWorkflow.requestAnalysisDisplayMode(displayMode)
    }

    /** 异步加载当前方法链路。 */
    fun loadCurrentMethodGraphAsync() {
        subjectGraphWorkflow.loadCurrentMethodGraphAsync()
    }

    /** 异步加载当前编辑器上下文链路。 */
    fun loadCurrentEditorContextGraphAsync() {
        subjectGraphWorkflow.loadCurrentEditorContextGraphAsync()
    }

    /** 直接加载指定图文档到编辑器状态。 */
    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        subjectGraphWorkflow.clearLastAnalysisCache()
        invalidateAuditRequests()
        graphWorkspaceWorkflow.loadGraph(graph, source)
    }

    /** 处理前端主动上报的图结构变更。 */
    fun handleFrontendGraphChanged(graph: GraphDocument) {
        subjectGraphWorkflow.clearLastAnalysisCache()
        invalidateAuditRequests()
        graphWorkspaceWorkflow.handleFrontendGraphChanged(graph)
    }

    /** 处理前端主动上报的布局变更。 */
    fun handleFrontendLayoutChanged(positions: Map<String, GraphLayoutPosition>) {
        graphWorkspaceWorkflow.handleFrontendLayoutChanged(positions)
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

    /** 异步生成实现计划，并把请求状态同步到前端。 */
    fun requestGenerationPlanAsync() {
        generationWorkflow.requestGenerationPlanAsync()
    }

    /** 基于当前规划上下文生成代码草稿。 */
    fun requestCodeDrafts() {
        generationWorkflow.requestCodeDrafts()
    }

    /** 异步生成代码草稿，并把请求状态同步到前端。 */
    fun requestCodeDraftsAsync() {
        generationWorkflow.requestCodeDraftsAsync()
    }

    /** 把当前全部代码草稿批量写入项目目录。 */
    fun applyCodeDrafts() {
        generationWorkflow.applyCodeDrafts()
    }

    /** 仅写入单个指定代码草稿。 */
    fun applySingleCodeDraft(draftId: String) {
        generationWorkflow.applySingleCodeDraft(draftId)
    }

    /** 请求跳转到草稿文件路径。 */
    fun requestDraftNavigation(targetPath: String) {
        generationWorkflow.requestDraftNavigation(targetPath)
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

    /** 基于当前工作图发起同步审计。 */
    fun requestAudit(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
    ): GraphPatchResult {
        return reviewWorkflow.requestAudit(question, selectedNodeIds)
    }

    /** 异步发起链路审计，并把结果和补丁预览回写到前端。 */
    fun requestAuditAsync(
        question: String,
        selectedNodeIds: List<String> = emptyList(),
    ) {
        reviewWorkflow.requestAuditAsync(question, selectedNodeIds)
    }

    /** 基于代码事实图和设计基线发起同步差异问答。 */
    fun requestDiffReview(
        question: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ): GraphPatchResult? {
        return reviewWorkflow.requestDiffReview(question, selectedDiffItemIds)
    }

    /** 异步发起差异问答，并把修订草稿回写到前端。 */
    fun requestDiffReviewAsync(
        question: String,
        selectedDiffItemIds: List<String> = emptyList(),
    ) {
        reviewWorkflow.requestDiffReviewAsync(question, selectedDiffItemIds)
    }

    /** 生成当前链路图的讲解与润色说明。 */
    fun requestGraphBeautification(
        goal: String = "",
        preferredStyle: String? = null,
        explanationFocus: String? = null,
    ): GraphBeautificationResult {
        return reviewWorkflow.requestGraphBeautification(goal, preferredStyle, explanationFocus)
    }

    /** 异步生成链路讲解，并把请求状态同步到前端。 */
    fun requestGraphBeautificationAsync(
        goal: String = "",
        preferredStyle: String? = null,
        explanationFocus: String? = null,
    ) {
        reviewWorkflow.requestGraphBeautificationAsync(goal, preferredStyle, explanationFocus)
    }

    /** 展开摘要节点，重新以更宽的预算提取当前方法链路。 */
    fun requestExpandOverflowNode(nodeId: String) {
        subjectGraphWorkflow.requestExpandOverflowNode(nodeId)
    }

    /**
     * 按节点位置尝试打开源码，并把结果明确回写到前端状态。
     * 对缺失位置、文件不存在等情况都给出提示，避免前端看起来“没反应”。
     */
    fun requestSourceNavigation(nodeId: String): SourceNavigationService.NavigationTarget? {
        return sourceNavigationWorkflow.requestSourceNavigation(nodeId)
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

    /** 在 IDEA 线程中安全执行读操作。 */
    private fun <T> computeOnIdeThread(action: () -> T): T {
        /** 全局应用对象。 */
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            return WriteIntentReadAction.compute<T, RuntimeException>(action)
        }

        /** 是否已经在 IDEA 线程成功完成执行。 */
        val completed = AtomicBoolean(false)
        /** 执行结果。 */
        val result = AtomicReference<T>()
        /** 执行过程中捕获的异常。 */
        val error = AtomicReference<Throwable?>()
        application.invokeAndWait(
            {
                try {
                    result.set(WriteIntentReadAction.compute<T, RuntimeException>(action))
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

    /** 把最新状态同步到浏览器面板。 */
    /** 在调试模式下按环境变量预设启动后的展示模式。 */
    @JvmName("prepareDebugRequestedAnalysisDisplayModeIfPresent")
    internal fun prepareDebugRequestedAnalysisDisplayModeIfPresent() {
        val displayMode = resolveDebugRequestedAnalysisDisplayMode() ?: return
        if (!subjectGraphWorkflow.prepareRequestedAnalysisDisplayMode(displayMode)) {
            return
        }
        logger.info(
            "检测到调试展示模式环境变量 $DEBUG_ANALYSIS_DISPLAY_MODE_ENV=$displayMode，将在自动载图时优先展示该模式",
        )
    }

    /** 解析调试用的展示模式环境变量。 */
    private fun resolveDebugRequestedAnalysisDisplayMode(): AnalysisDisplayMode? {
        val rawValue = System.getenv(DEBUG_ANALYSIS_DISPLAY_MODE_ENV)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        return runCatching {
            AnalysisDisplayMode.valueOf(rawValue.uppercase())
        }.getOrElse { error ->
            logger.warn("无效的调试展示模式环境变量: $DEBUG_ANALYSIS_DISPLAY_MODE_ENV=$rawValue", error)
            null
        }
    }

    /** 按调试模式注入预置链路图。 */
    @JvmName("loadDebugGraph")
    internal fun loadDebugGraph(mode: String) {
        /** 调试图定义。 */
        val debugGraph = debugGraphFactory.create(mode)
        if (debugGraph == null) {
            logger.warn("未知的调试自动载图模式: $mode")
            return
        }
        logger.info("开始注入调试链路图: mode=$mode, summary=${debugGraph.summary}")
        invalidateAuditRequests()
        mutateEditorStateBatch {
            apply {
                loadGraphProjection(
                    visibleGraph = debugGraph.graph,
                    fullGraph = debugGraph.graph,
                    source = "debug:$mode",
                    selectedMethodSignature = debugGraph.anchorSignature,
                )
            }
            apply {
                markOperationFeedback(
                    OperationFeedbackLevel.INFO,
                    "已自动载入诊断链路图：${debugGraph.summary}",
                )
            }
        }
    }

    /** 按方法签名异步载入真实方法链路图。 */
    @JvmName("loadDebugMethodGraphBySignatureAsync")
    internal fun loadDebugMethodGraphBySignatureAsync(signature: String) {
        subjectGraphWorkflow.loadDebugMethodGraphBySignatureAsync(signature)
    }

    /** 输出链路图的诊断摘要，便于排查数据问题。 */
    private fun logGraphDiagnostics(
        reason: String,
        graph: GraphDocument?,
    ) {
        if (graph == null) {
            logger.info("链路图诊断[$reason]: graph=null")
            return
        }
        /** 图中所有节点 ID。 */
        val nodeIds = graph.nodes.map { it.id }
        /** 节点 ID 去重后的集合。 */
        val nodeIdSet = nodeIds.toSet()
        /** 重复节点 ID 样本。 */
        val duplicateNodeIds = nodeIds.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .take(6)
        /** 图中所有边 ID。 */
        val edgeIds = graph.edges.map { it.id }
        /** 重复边 ID 样本。 */
        val duplicateEdgeIds = edgeIds.groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
            .take(6)
        /** 悬空边样本。 */
        val danglingEdges = graph.edges
            .filter { edge -> edge.fromNodeId !in nodeIdSet || edge.toNodeId !in nodeIdSet }
            .take(6)
            .map { edge -> "${edge.id}(${edge.fromNodeId}->${edge.toNodeId})" }
        /** 具备画布坐标的节点位置集合。 */
        val positionedNodes = graph.nodes.mapNotNull { node ->
            val x = node.metadata[UI_X_KEY]?.toDoubleOrNull()
            val y = node.metadata[UI_Y_KEY]?.toDoubleOrNull()
            if (x != null && y != null) {
                x to y
            } else {
                null
            }
        }
        /** 节点 X 坐标范围。 */
        val xRange = if (positionedNodes.isEmpty()) {
            "n/a"
        } else {
            "${positionedNodes.minOf { it.first }.toInt()}..${positionedNodes.maxOf { it.first }.toInt()}"
        }
        /** 节点 Y 坐标范围。 */
        val yRange = if (positionedNodes.isEmpty()) {
            "n/a"
        } else {
            "${positionedNodes.minOf { it.second }.toInt()}..${positionedNodes.maxOf { it.second }.toInt()}"
        }
        /** 最大出度。 */
        val maxOutDegree = graph.edges.groupingBy { it.fromNodeId }.eachCount().values.maxOrNull() ?: 0
        /** 最大入度。 */
        val maxInDegree = graph.edges.groupingBy { it.toNodeId }.eachCount().values.maxOrNull() ?: 0
        /** 节点类型分布摘要。 */
        val typeSummary = graph.nodes.groupingBy { it.type.name }.eachCount().entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.key}:${it.value}" }
        logger.info(
            "链路图诊断[$reason]: nodes=${graph.nodes.size}, edges=${graph.edges.size}, " +
                "duplicateNodeIds=$duplicateNodeIds, duplicateEdgeIds=$duplicateEdgeIds, danglingEdges=$danglingEdges, " +
                "positioned=${positionedNodes.size}, xRange=$xRange, yRange=$yRange, maxOutDegree=$maxOutDegree, maxInDegree=$maxInDegree, " +
                "sampleNodes=${graph.nodes.take(6).map { it.id }}, nodeTypes=[$typeSummary]",
        )
    }

    /** 获取图编辑器状态服务。 */
    private fun stateService(): GraphEditorStateService {
        return project.getService(GraphEditorStateService::class.java)
    }

    /** 返回当前真正生效的生成设置。 */
    private fun effectiveGenerationSettings() = testEffectiveGenerationSettingsOverride
        ?: ApplicationManager.getApplication()
            .getService(LinkGraphSettingsService::class.java)
            .snapshot()

    companion object {
        /** 画布 X 坐标 metadata 键。 */
        private const val UI_X_KEY = "ui.x"
        /** 画布 Y 坐标 metadata 键。 */
        private const val UI_Y_KEY = "ui.y"
        /** 调试启动时指定展示模式的环境变量。 */
        private const val DEBUG_ANALYSIS_DISPLAY_MODE_ENV = "LINKGRAPH_DEBUG_ANALYSIS_DISPLAY_MODE"
        /** 服务日志记录器。 */
        private val logger = Logger.getInstance(LinkGraphProjectService::class.java)
    }

    private data class DebugGraphDefinition(
        /** 调试图本体。 */
        val graph: GraphDocument,
        /** 调试图锚点方法签名。 */
        val anchorSignature: String,
        /** 调试图摘要说明。 */
        val summary: String,
    )

}

/** 按优先级从多个图快照里查找指定节点。 */
internal fun findNavigationNode(
    snapshot: GraphEditorStateService.Snapshot,
    nodeId: String,
): GraphNode? {
    return sequenceOf(
        snapshot.visibleGraph,
        snapshot.workingGraph,
        snapshot.referenceFactGraph,
        snapshot.designBaselineGraph,
    )
        .filterNotNull()
        .flatMap { graph -> graph.nodes.asSequence() }
        .firstOrNull { node -> node.id == nodeId }
}
