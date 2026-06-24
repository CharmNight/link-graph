package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.application.model.GraphEditRejected
import com.charmnight.linkgraph.application.model.GraphEditResult
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditRequestParseResult
import com.charmnight.linkgraph.application.model.GraphLayoutPosition
import com.charmnight.linkgraph.application.result.ApplicationFeedbackLevel
import com.charmnight.linkgraph.application.port.EditorSnapshotProvider
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEvent
import com.charmnight.linkgraph.application.event.GraphEditorApplicationEventSink
import com.charmnight.linkgraph.application.port.WorkspaceGraphCommitter
import com.charmnight.linkgraph.application.usecase.WorkspaceGraphUseCase
import com.charmnight.linkgraph.application.usecase.WorkspaceGraphUseCaseResult
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewPlanner

/**
 * 图工作台工作流。
 *
 * 作为前端交互与底层用例之间的协调者：把快照提供、Mermaid 导入导出、Diff、同步预览等
 * 各类操作委托给 [WorkspaceGraphUseCase]，并把执行结果转换为应用层事件广播出去。
 */
internal class GraphWorkspaceWorkflow(
    private val snapshotProvider: EditorSnapshotProvider,
    private val workspaceGraphCommitter: WorkspaceGraphCommitter,
    private val eventSink: GraphEditorApplicationEventSink,
    private val mermaidImporter: MermaidImporter,
    private val mermaidValidator: MermaidValidator,
    private val mermaidExporter: MermaidExporter,
    private val graphDiffer: GraphDiffer,
    private val syncPreviewPlanner: SyncPreviewPlanner,
    private val copyToClipboard: (String) -> Boolean,
    private val frontendGraphMutationSanitizer: FrontendGraphMutationSanitizer = FrontendGraphMutationSanitizer(),
) {
    // 聚合 Mermaid、Diff、同步预览等能力的核心用例，由本工作流负责驱动
    private val useCase = WorkspaceGraphUseCase(
        mermaidImporter = mermaidImporter,
        mermaidValidator = mermaidValidator,
        mermaidExporter = mermaidExporter,
        graphDiffer = graphDiffer,
        syncPreviewPlanner = syncPreviewPlanner,
        frontendGraphMutationSanitizer = frontendGraphMutationSanitizer,
    )

    /**
     * 加载指定的图文档到工作台，并以"加载完成"事件对外广播。
     *
     * @param graph 待加载的图文档
     * @param source 标识图来源的描述文本（如某个主题、某次导入）
     */
    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        val result = useCase.loadGraph(graph, source)
        eventSink.emit(GraphEditorApplicationEvent.WorkspaceGraphLoaded(result.graph, result.source))
    }

    /**
     * 处理一次图编辑请求（解析后）。
     *
     * 当解析结果含 issues 时直接广播拒绝事件；否则交给用例执行权限/校验/应用链路。
     * 当用例判定为拒绝编辑时广播拒绝事件；当编辑被接受时通过 [WorkspaceGraphCommitter] 提交到工作台，
     * 并返回包含变更结果与事务信息的 [GraphEditResult]。
     *
     * @param parseResult 解析结果（含 request 与可能的 issues）
     * @return 编辑结果（已应用 / 已拒绝）
     */
    fun handleGraphEditRequest(parseResult: GraphEditRequestParseResult): GraphEditResult {
        if (parseResult.issues.isNotEmpty()) {
            val snapshot = snapshotProvider.snapshot()
            val rejection = GraphEditRejected(
                issues = parseResult.issues,
                currentWorkspaceRevision = snapshot.workspaceRevision,
            )
            eventSink.emit(GraphEditorApplicationEvent.GraphEditRejected(rejection))
            return GraphEditResult.Rejected(rejection)
        }
        val request = parseResult.request ?: error("parse result has issues but request is null")
        return when (val result = useCase.applyGraphEditRequest(snapshotProvider.snapshot(), request)) {
            is WorkspaceGraphUseCaseResult.EditRejected -> {
                // 编辑被拒绝：仅广播拒绝事件，不做提交
                eventSink.emit(GraphEditorApplicationEvent.GraphEditRejected(result.rejection))
                GraphEditResult.Rejected(result.rejection)
            }
            is WorkspaceGraphUseCaseResult.EditApplied -> {
                // 编辑被接受：把新图与事务一起提交到工作台，关闭同步浏览
                workspaceGraphCommitter.commitWorkspaceGraph(
                expectedSnapshotRevision = result.expectedSnapshotRevision,
                graph = result.graph,
                selectedMethodSignature = result.selectedMethodSignature,
                syncBrowser = false,
                    graphEditTransaction = result.transaction,
            )
                GraphEditResult.Applied(result.graph, result.transaction)
            }
            else -> error("unsupported graph edit use case result: ${result::class.simpleName}")
        }
    }

    /**
     * 处理前端上报的节点布局位置变更。
     *
     * 仅当存在有效布局变更时才广播布局变更事件，避免无意义的刷新。
     *
     * @param positions 节点 id 到布局位置的映射
     */
    fun handleFrontendLayoutChanged(positions: Map<String, GraphLayoutPosition>) {
        val result = useCase.changeLayout(snapshotProvider.snapshot(), positions)
        // 没有可用布局变更时直接跳过，减少无意义事件
        if (result.positions.isEmpty()) {
            return
        }
        eventSink.emit(GraphEditorApplicationEvent.WorkspaceLayoutChanged(result.positions))
    }

    /**
     * 从 Mermaid 文本导入并生成图文档。
     *
     * @param mermaid 待导入的 Mermaid 文本
     * @return 导入后的图文档；导入过程中的问题以事件形式广播
     */
    fun importMermaid(mermaid: String): GraphDocument {
        val result = useCase.importMermaid(mermaid)
        eventSink.emit(GraphEditorApplicationEvent.MermaidImported(result.mermaid, result.graph, result.issues))
        return result.graph
    }

    /**
     * 把当前快照导出为 Mermaid 文本。
     *
     * 导出成功后会尝试将文本复制到剪贴板，并通过事件告知前端导出与复制的结果。
     *
     * @return 导出的 Mermaid 文本
     */
    fun exportMermaid(): String {
        val result = useCase.exportMermaid(snapshotProvider.snapshot())
        return result.exported.also { exported ->
            eventSink.emit(GraphEditorApplicationEvent.MermaidExported(exported, copyToClipboard(exported)))
        }
    }

    /**
     * 进入代码对比（Diff）模式。
     *
     * 缺少代码图或设计基线时通过 WARNING 反馈并返回 null；正常进入对比模式后广播对比图与提示信息。
     *
     * @return 对比结果；前置条件不满足时返回 null
     */
    fun showDiffMode(): GraphDifferResult? {
        return when (val result = useCase.showDiffMode(snapshotProvider.snapshot())) {
            WorkspaceGraphUseCaseResult.MissingDiffInputs -> {
                // 缺少对比所需的输入：以 WARNING 反馈并放弃进入对比模式
                eventSink.emit(
                    GraphEditorApplicationEvent.Feedback(
                        level = ApplicationFeedbackLevel.WARNING,
                        message = "缺少代码图或设计基线，无法打开代码对比。",
                    ),
                )
                null
            }
            is WorkspaceGraphUseCaseResult.DiffShown -> result.result.also { diffResult ->
                eventSink.emit(GraphEditorApplicationEvent.DiffModeShown(diffResult.graph, diffResult.diff))
                eventSink.emit(
                    GraphEditorApplicationEvent.Feedback(
                        level = ApplicationFeedbackLevel.INFO,
                        message = "已打开代码对比。",
                    ),
                )
            }
            else -> null
        }
    }

    /**
     * 请求生成同步预览项列表。
     *
     * 根据是否存在待同步变更，分别给出"没有可同步的变更"或"已打开同步预览"两种提示。
     *
     * @return 同步预览项列表，可能为空
     */
    fun requestSyncPreview(): List<SyncPreviewItem> {
        val result = useCase.requestSyncPreview(snapshotProvider.snapshot())
        eventSink.emit(GraphEditorApplicationEvent.SyncPreviewReady(result.items))
        eventSink.emit(
            GraphEditorApplicationEvent.Feedback(
                level = ApplicationFeedbackLevel.INFO,
                message = if (result.items.isEmpty()) {
                    "没有可同步的变更。"
                } else {
                    "已打开同步预览。"
                },
            ),
        )
        return result.items
    }
}
