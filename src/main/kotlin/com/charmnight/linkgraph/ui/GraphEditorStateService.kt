package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.review.ReviewGraphResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.intellij.openapi.components.Service

@Service(Service.Level.PROJECT)
class GraphEditorStateService {
    // 用于将图编辑补丁应用到工作图的辅助服务。
    private val graphPatchApplyService = GraphPatchApplyService()
    // 持有当前编辑器状态快照、负责原子化提交的本地存储。
    private val store = GraphEditorStateStore()
    // 由调试环境控制是否开启运行时跟踪日志，便于本地排查问题。
    private val debugTracingEnabled: Boolean =
        LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_TRACE")

    /**
     * 图谱相关的状态支持对象。
     *
     * 委托处理加载、变更、布局、源码导航等围绕图谱视图本身的状态变更，
     * 内部通过本服务的 mutate 进行原子化提交。
     */
    internal val graph: GraphEditorGraphStateSupport = GraphEditorGraphStateSupport(
        ::mutate,
        graphPatchApplyService,
        runtimeTrace = { message ->
            if (debugTracingEnabled) {
                com.intellij.openapi.diagnostic.Logger.getInstance(GraphEditorStateService::class.java).warn(message())
            }
        },
    )
    /** 异步请求相关的状态支持，处理从后台到前端的请求排队与回调结果。 */
    internal val asyncRequests: GraphEditorAsyncRequestStateSupport = GraphEditorAsyncRequestStateSupport(::mutate)
    /** 索引图谱相关的状态支持，管理多个被索引的图谱视图数据。 */
    internal val indexedGraphs: GraphEditorIndexedGraphStateSupport = GraphEditorIndexedGraphStateSupport(::mutate)
    /** 工作台面板的状态支持，负责工具窗和面板可见性等界面状态。 */
    internal val workbench: GraphEditorWorkbenchStateSupport = GraphEditorWorkbenchStateSupport(::mutate)

    /** 返回当前编辑器状态的不可变快照，供外部读取。 */
    fun snapshot(): GraphEditorStateSnapshot = store.snapshot()

    /** 标记前端入口已经加载完毕，通常用于通知工具窗可以开始接收交互。 */
    fun markFrontendLoaded(entryUrl: String) = graph.markFrontendLoaded(entryUrl)

    /** 载入一张完整的图谱文档并指定来源标识，触发图谱视图重建。 */
    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) = this.graph.loadGraph(graph, source)

    /**
     * 载入带有投影关系的图谱。
     *
     * visibleGraph 是用户当前可见的子集，fullGraph 是完整底图，
     * 通过组合两者支持节点展开/折叠等交互场景。
     */
    fun loadGraphProjection(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        source: String,
        selectedMethodSignature: String? = null,
    ) = this.graph.loadGraphProjection(visibleGraph, fullGraph, source, selectedMethodSignature)

    /** 载入分析结果对象，将其映射为可在图谱视图中展示的状态。 */
    fun loadAnalysisOutcome(
        outcome: AnalysisOutcome,
        source: String,
    ) = this.graph.loadAnalysisOutcome(outcome, source)

    /** 载入架构图谱视图结果，用于显示整体架构层级关系。 */
    fun loadArchitectureGraphView(view: ArchitectureGraphResult) = graph.loadArchitectureGraphView(view)

    /** 载入类图视图结果，用于显示类与类之间的关系。 */
    fun loadClassDiagramView(view: ClassDiagramResult) = graph.loadClassDiagramView(view)

    /** 载入审阅视图结果，用于显示审阅阶段产生的图谱。 */
    fun loadReviewGraphView(view: ReviewGraphResult) = graph.loadReviewGraphView(view)

    /**
     * 从 Mermaid 文本导入图谱。
     *
     * 可选地传入已有的图谱以及解析过程中遇到的问题列表，
     * 以便前端在导入时一并展示校验信息。
     */
    fun importMermaid(
        mermaid: String,
        graph: GraphDocument? = null,
        mermaidIssues: List<MermaidIssue> = emptyList(),
    ) = this.graph.importMermaid(mermaid, graph, mermaidIssues)

    /** 标记 Mermaid 文本已经导出，便于工具栏同步导出状态。 */
    fun markMermaidExported(mermaid: String) = graph.markMermaidExported(mermaid)

    /** 进入差异展示模式，将指定的差异结果叠加到图谱视图上。 */
    fun showDiffMode(
        graph: GraphDocument,
        diff: GraphDiff,
    ) = this.graph.showDiffMode(graph, diff)

    /** 将某个方法签名推入选择栈，前端据此高亮并联动详情面板。 */
    fun pushSelectedMethod(signature: String) = graph.pushSelectedMethod(signature)

    /** 选中指定节点，触发节点级别的视图高亮与详情展示。 */
    fun selectNode(nodeId: String) = graph.selectNode(nodeId)

    fun collapseInvocationExpansion(expansionId: String) = graph.collapseInvocationExpansion(expansionId)

    fun openInvocationExpansion(expansionId: String) = graph.openInvocationExpansion(expansionId)

    /** 切换分析结果的展示模式，例如在概览和详情之间切换。 */
    fun switchAnalysisDisplayMode(displayMode: AnalysisDisplayMode) = graph.switchAnalysisDisplayMode(displayMode)

    /**
     * 标记图谱发生变更。
     *
     * 用于将外部产生的编辑结果同步到工作图，可选保留草稿撤销栈，
     * 标记工作图脏状态，并可附带一个编辑事务用于历史记录。
     */
    fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
        graphEditTransaction: GraphEditTransaction? = null,
    ) = this.graph.markGraphChanged(
        graph,
        selectedMethodSignature,
        preserveDraftPatchUndo,
        workingGraphDirty,
        graphEditTransaction,
    )

    /** 标记节点布局已变更，传入最新的节点位置映射。 */
    fun markLayoutChanged(positions: Map<String, GraphLayoutPosition>) = graph.markLayoutChanged(positions)

    /** 发起针对某节点的源码导航请求，触发后端的代码定位流程。 */
    fun requestSourceNavigation(nodeId: String) = graph.requestSourceNavigation(nodeId)

    /** 标记源码导航成功打开，记录目标文件路径、行列号等信息。 */
    fun markSourceNavigationOpened(
        nodeId: String,
        targetPath: String,
        line: Int?,
        column: Int?,
    ) = graph.markSourceNavigationOpened(nodeId, targetPath, line, column)

    /** 标记指定节点的源码定位未找到对应目标。 */
    fun markSourceNavigationNotFound(nodeId: String) = graph.markSourceNavigationNotFound(nodeId)

    /** 标记指定节点的源码导航失败，并附带失败原因。 */
    fun markSourceNavigationFailed(
        nodeId: String,
        message: String,
    ) = graph.markSourceNavigationFailed(nodeId, message)

    /** 标记工具窗已经打开，用于初始化或恢复交互状态。 */
    fun markToolWindowOpened() = graph.markToolWindowOpened()

    /** 标记最近一次给用户的消息类型，用于状态栏等提示位置。 */
    fun markLastMessageType(messageType: String) = graph.markLastMessageType(messageType)

    /**
     * 以原子方式执行一次状态变更。
     *
     * 接收一个把旧快照转换为新快照的函数，由底层 store 保证提交的原子性，
     * 内部状态支持对象均通过该入口完成状态写入。
     */
    internal fun mutate(transform: (GraphEditorStateSnapshot) -> GraphEditorStateSnapshot): GraphEditorStateSnapshot {
        return store.mutate(transform)
    }

    /**
     * 在预期版本号匹配时尝试提交一次状态变更。
     *
     * 用于乐观并发场景，调用方先读取版本号，构造变更后通过此方法提交；
     * 若期间版本号已变更则提交失败，调用方需重新尝试。
     */
    internal fun tryCommit(
        expectedRevision: Long,
        transform: (GraphEditorStateSnapshot) -> GraphEditorStateSnapshot,
    ): GraphEditorStateCommitResult {
        return store.tryCommit(expectedRevision, transform)
    }
}
