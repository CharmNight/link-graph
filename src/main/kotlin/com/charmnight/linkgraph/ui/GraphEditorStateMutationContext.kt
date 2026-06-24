package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome

/**
 * 图谱编辑器状态变更的统一抽象，对外暴露一组高阶语义方法，
 * 由具体实现（如实时状态服务）转发到底层各个状态支持对象。
 */
internal interface GraphEditorStateMutationContext {
    /** 图谱相关状态（节点、布局、模式等）的支持器 */
    val graph: GraphEditorGraphStateSupport
    /** 异步请求状态（加载中、失败、状态消息等）的支持器 */
    val asyncRequests: GraphEditorAsyncRequestStateSupport
    /** 工作台相关状态（前端加载、最近消息等）的支持器 */
    val workbench: GraphEditorWorkbenchStateSupport

    /** 返回当前编辑器状态的只读快照 */
    fun snapshot(): GraphEditorStateSnapshot

    /** 标记前端入口页面已成功加载到指定地址 */
    fun markFrontendLoaded(entryUrl: String)

    /** 加载新的图谱数据，并记录加载来源 */
    fun loadGraph(
        graph: GraphDocument,
        source: String,
    )

    /** 同时加载可见子图与完整图，并可选地同步当前选中方法签名 */
    fun loadGraphProjection(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        source: String,
        selectedMethodSignature: String? = null,
    )

    /** 加载分析产物，并记录加载来源 */
    fun loadAnalysisOutcome(
        outcome: AnalysisOutcome,
        source: String,
    )

    /** 加载架构图谱视图 */
    fun loadArchitectureGraphView(view: ArchitectureGraphResult)

    /** 加载类图视图 */
    fun loadClassDiagramView(view: ClassDiagramResult)

    /** 导入 Mermaid 文本，可附带解析后的图谱与解析过程中的问题清单 */
    fun importMermaid(
        mermaid: String,
        graph: GraphDocument? = null,
        mermaidIssues: List<MermaidIssue> = emptyList(),
    )

    /** 标记 Mermaid 文本已导出（例如已复制到剪贴板） */
    fun markMermaidExported(mermaid: String)

    /** 进入差异展示模式，携带基础图谱与差异结构 */
    fun showDiffMode(
        graph: GraphDocument,
        diff: GraphDiff,
    )

    /** 更新当前选中的方法签名，并压入方法历史栈 */
    fun pushSelectedMethod(signature: String)

    /** 选中新节点 */
    fun selectNode(nodeId: String)

    /** 切换分析结果的展示模式 */
    fun switchAnalysisDisplayMode(displayMode: AnalysisDisplayMode)

    /** 标记图谱发生变更，记录选中方法签名以及是否保留草稿撤销栈等关键选项 */
    fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
    )

    /** 标记节点布局位置发生变化 */
    fun markLayoutChanged(positions: Map<String, GraphLayoutPosition>)

    /** 发起对指定节点的源码跳转请求 */
    fun requestSourceNavigation(nodeId: String)

    /** 标记源码跳转已成功打开目标文件位置 */
    fun markSourceNavigationOpened(
        nodeId: String,
        targetPath: String,
        line: Int?,
        column: Int?,
    )

    /** 标记源码跳转未能找到对应目标 */
    fun markSourceNavigationNotFound(nodeId: String)

    /** 标记源码跳转发生失败 */
    fun markSourceNavigationFailed(
        nodeId: String,
        message: String,
    )

    /** 标记图谱工具窗口已打开 */
    fun markToolWindowOpened()

    /** 记录最近一次反馈消息的类型，便于前端按类型渲染 */
    fun markLastMessageType(messageType: String)
}

/**
 * 基于实时 [GraphEditorStateService] 的变更上下文实现。
 * 通过 provider 持有对状态服务的引用，可支持懒加载或测试替身场景。
 */
internal class LiveGraphEditorStateMutationContext(
    private val stateServiceProvider: () -> GraphEditorStateService,
) : GraphEditorStateMutationContext {
    /** 直接传入状态服务实例的便捷构造器，内部将其包装为 provider */
    constructor(stateService: GraphEditorStateService) : this({ stateService })

    // 通过 provider 获取实际状态服务，避免在构造期绑定具体实例
    private val stateService: GraphEditorStateService
        get() = stateServiceProvider()

    override val graph: GraphEditorGraphStateSupport
        get() = stateService.graph
    override val asyncRequests: GraphEditorAsyncRequestStateSupport
        get() = stateService.asyncRequests
    override val workbench: GraphEditorWorkbenchStateSupport
        get() = stateService.workbench

    override fun snapshot(): GraphEditorStateSnapshot = stateService.snapshot()

    override fun markFrontendLoaded(entryUrl: String) = graph.markFrontendLoaded(entryUrl)

    override fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) = this.graph.loadGraph(graph, source)

    override fun loadGraphProjection(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        source: String,
        selectedMethodSignature: String?,
    ) = graph.loadGraphProjection(visibleGraph, fullGraph, source, selectedMethodSignature)

    override fun loadAnalysisOutcome(
        outcome: AnalysisOutcome,
        source: String,
    ) = graph.loadAnalysisOutcome(outcome, source)

    override fun loadArchitectureGraphView(view: ArchitectureGraphResult) =
        graph.loadArchitectureGraphView(view)

    override fun loadClassDiagramView(view: ClassDiagramResult) =
        graph.loadClassDiagramView(view)

    override fun importMermaid(
        mermaid: String,
        graph: GraphDocument?,
        mermaidIssues: List<MermaidIssue>,
    ) = this.graph.importMermaid(mermaid, graph, mermaidIssues)

    override fun markMermaidExported(mermaid: String) = graph.markMermaidExported(mermaid)

    override fun showDiffMode(
        graph: GraphDocument,
        diff: GraphDiff,
    ) = this.graph.showDiffMode(graph, diff)

    override fun pushSelectedMethod(signature: String) = graph.pushSelectedMethod(signature)

    override fun selectNode(nodeId: String) = graph.selectNode(nodeId)

    override fun switchAnalysisDisplayMode(displayMode: AnalysisDisplayMode) = graph.switchAnalysisDisplayMode(displayMode)

    override fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String?,
        preserveDraftPatchUndo: Boolean,
        workingGraphDirty: Boolean,
    ) = this.graph.markGraphChanged(graph, selectedMethodSignature, preserveDraftPatchUndo, workingGraphDirty)

    override fun markLayoutChanged(positions: Map<String, GraphLayoutPosition>) = graph.markLayoutChanged(positions)

    override fun requestSourceNavigation(nodeId: String) = graph.requestSourceNavigation(nodeId)

    override fun markSourceNavigationOpened(
        nodeId: String,
        targetPath: String,
        line: Int?,
        column: Int?,
    ) = graph.markSourceNavigationOpened(nodeId, targetPath, line, column)

    override fun markSourceNavigationNotFound(nodeId: String) = graph.markSourceNavigationNotFound(nodeId)

    override fun markSourceNavigationFailed(
        nodeId: String,
        message: String,
    ) = graph.markSourceNavigationFailed(nodeId, message)

    override fun markToolWindowOpened() = graph.markToolWindowOpened()

    override fun markLastMessageType(messageType: String) = graph.markLastMessageType(messageType)
}
