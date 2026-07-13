package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.review.ReviewGraphResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.GraphPatchApplyService

/**
 * 图谱编辑器状态变更的外部入口集合。
 * 所有状态变更都通过统一的 mutate 回调提交，确保 UI 状态变更经过单一通道，便于审计与追踪。
 */
internal class GraphEditorGraphStateSupport(
    /** 提交状态变更的回调，所有变更通过它应用到当前快照。 */
    private val mutate: ((GraphEditorStateSnapshot) -> GraphEditorStateSnapshot) -> Unit,
    /** 用于应用补丁的服务，加载分析结果时需要它来落地补丁。 */
    private val graphPatchApplyService: GraphPatchApplyService,
    /** 可选的运行时跟踪回调，在加载分析结果时记录关键步骤说明。 */
    private val runtimeTrace: ((() -> String) -> Unit)? = null,
) {
    /** 标记前端已加载完成，并记录入口 URL 以便后续与前端通信。 */
    fun markFrontendLoaded(entryUrl: String) {
        mutate {
            it.copy(
                frontendEntryUrl = entryUrl,
                lastMessageType = "frontendLoaded",
                snapshotRevision = it.snapshotRevision + 1,
            )
        }
    }

    /** 加载一份完整图谱作为编辑器当前展示的内容，来源描述便于追踪。 */
    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        mutate { currentState -> currentState.withLoadedGraph(graph, source) }
    }

    /**
     * 加载一份"可见 + 全量"两份图谱，用于投影场景。
     * fullGraph 用于后台分析，visibleGraph 是当前实际渲染的子集。
     */
    fun loadGraphProjection(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        source: String,
        selectedMethodSignature: String? = null,
    ) {
        mutate { currentState ->
            currentState.withLoadedGraphProjection(
                visibleGraph = visibleGraph,
                fullGraph = fullGraph,
                source = source,
                selectedMethodSignatureOverride = selectedMethodSignature,
            )
        }
    }

    /** 将一次分析产出加载到编辑器：会通过补丁服务落地改动并可能产生运行时跟踪输出。 */
    fun loadAnalysisOutcome(
        outcome: AnalysisOutcome,
        source: String,
    ) {
        mutate { currentState ->
            currentState.withLoadedAnalysisOutcome(
                outcome = outcome,
                source = source,
                graphPatchApplyService = graphPatchApplyService,
                runtimeTrace = runtimeTrace,
            )
        }
    }

    /** 切换到架构图视图，并将请求状态置为已成功。 */
    fun loadArchitectureGraphView(view: ArchitectureGraphResult) {
        mutate { currentState ->
            currentState.withLoadedArchitectureGraphView(
                view = view,
                requestState = AsyncRequestState.succeeded(
                    scene = IndexedGraphView.ARCHITECTURE.name,
                    statusMessage = "已加载项目结构。",
                ),
                statusMessage = "已加载项目结构。",
            )
        }
    }

    /** 切换到类图视图，并将请求状态置为已成功。 */
    fun loadClassDiagramView(view: ClassDiagramResult) {
        mutate { currentState ->
            currentState.withLoadedClassDiagramView(
                view = view,
                requestState = AsyncRequestState.succeeded(
                    scene = IndexedGraphView.CLASS_DIAGRAM.name,
                    statusMessage = "已加载类图。",
                ),
                statusMessage = "已加载类图。",
            )
        }
    }

    /** 切换到 Review Graph 视图，并将请求状态置为已成功。 */
    fun loadReviewGraphView(view: ReviewGraphResult) {
        mutate { currentState ->
            currentState.withLoadedReviewGraphView(
                view = view,
                requestState = AsyncRequestState.succeeded(
                    scene = IndexedGraphView.REVIEW.name,
                    statusMessage = "已加载 Review Graph。",
                ),
                statusMessage = "已加载 Review Graph。",
            )
        }
    }

    /** 导入一段 Mermaid 文本及其对应解析后的图谱与发现的问题，进入编辑器。 */
    fun importMermaid(
        mermaid: String,
        graph: GraphDocument? = null,
        mermaidIssues: List<com.charmnight.linkgraph.mermaid.MermaidIssue> = emptyList(),
    ) {
        mutate { currentState -> currentState.withImportedMermaid(mermaid, graph, mermaidIssues) }
    }

    /** 标记一次 Mermaid 导出已完成，并保存导出文本供前端回取。 */
    fun markMermaidExported(mermaid: String) {
        mutate {
            it.copy(
                exportedMermaid = mermaid,
                lastMessageType = "exportMermaid",
                snapshotRevision = it.snapshotRevision + 1,
            )
        }
    }

    /** 进入 diff 视图，给定当前图谱和与基线之间的差异。 */
    fun showDiffMode(
        graph: com.charmnight.linkgraph.model.GraphDocument,
        diff: com.charmnight.linkgraph.model.GraphDiff,
    ) {
        mutate { currentState -> currentState.withShownDiffMode(graph, diff) }
    }

    /** 设置当前选中方法（按方法签名），用于驱动右侧详情和节点高亮。 */
    fun pushSelectedMethod(signature: String) {
        mutate { currentState -> currentState.withSelectedMethod(signature) }
    }

    /** 切换当前选中的节点，用于驱动节点详情和源码导航。 */
    fun selectNode(nodeId: String) {
        mutate { currentState -> currentState.withSelectedNode(nodeId) }
    }

    fun collapseInvocationExpansion(expansionId: String) {
        mutate { currentState -> currentState.withCollapsedInvocationExpansion(expansionId) }
    }

    fun openInvocationExpansion(expansionId: String) {
        mutate { currentState -> currentState.withOpenedInvocationExpansion(expansionId) }
    }

    /** 切换分析结果的展示模式（例如仅展示关键节点或全量节点）。 */
    fun switchAnalysisDisplayMode(displayMode: AnalysisDisplayMode) {
        mutate { currentState -> currentState.withSwitchedAnalysisDisplayMode(displayMode) }
    }

    /**
     * 通知编辑器工作区图谱已发生变更。
     * 可选传入当前方法签名、是否保留草稿撤销链、是否将工作区标记为脏、以及相应的编辑事务。
     */
    fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
        graphEditTransaction: GraphEditTransaction? = null,
    ) {
        mutate { currentState ->
            currentState.withWorkspaceGraphChanged(
                graph = graph,
                selectedMethodSignatureOverride = selectedMethodSignature,
                preserveDraftPatchUndo = preserveDraftPatchUndo,
                workingGraphDirtyOverride = workingGraphDirty,
                graphEditTransaction = graphEditTransaction,
            )
        }
    }

    /** 提交一次节点布局位置变更，用于持久化用户对节点位置的拖拽结果。 */
    fun markLayoutChanged(positions: Map<String, GraphLayoutPosition>) {
        mutate { currentState -> currentState.withLayoutChanged(positions) }
    }

    /** 发起一次源码跳转请求，等待实际跳转结果回报。 */
    fun requestSourceNavigation(nodeId: String) {
        mutate { currentState -> currentState.withRequestedSourceNavigation(nodeId) }
    }

    /** 标记源码跳转已成功打开，并记录目标文件与具体行列位置。 */
    fun markSourceNavigationOpened(
        nodeId: String,
        targetPath: String,
        line: Int?,
        column: Int?,
    ) {
        mutate { currentState -> currentState.withOpenedSourceNavigation(nodeId, targetPath, line, column) }
    }

    /** 标记源码跳转无法定位到目标（例如节点没有对应的源码位置）。 */
    fun markSourceNavigationNotFound(nodeId: String) {
        mutate { currentState -> currentState.withMissingSourceNavigation(nodeId) }
    }

    /** 标记源码跳转过程发生错误，附带人类可读的失败原因。 */
    fun markSourceNavigationFailed(
        nodeId: String,
        message: String,
    ) {
        mutate { currentState -> currentState.withFailedSourceNavigation(nodeId, message) }
    }

    /** 标记 ToolWindow 被请求打开（例如用户从外部动作触发展示）。 */
    fun markToolWindowOpened() {
        mutate {
            it.copy(
                toolWindowOpenRequested = true,
                lastMessageType = "toolWindowOpened",
                snapshotRevision = it.snapshotRevision + 1,
            )
        }
    }

    /** 通用的最后消息类型记录，用于状态变更触发或调试跟踪。 */
    fun markLastMessageType(messageType: String) {
        mutate {
            it.copy(
                lastMessageType = messageType,
                snapshotRevision = it.snapshotRevision + 1,
            )
        }
    }
}
