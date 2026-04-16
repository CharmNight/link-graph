package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.diff.GraphDifferResult
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.GraphLayoutPosition

/**
 * 管理工作图、设计基线图、Mermaid 与 diff 的工作区流程。
 */
internal class GraphWorkspaceWorkflow(
    /** 项目级编辑器状态会话。 */
    private val session: ProjectEditorSession,
    /** Mermaid 导入器。 */
    private val mermaidImporter: MermaidImporter,
    /** Mermaid 校验器。 */
    private val mermaidValidator: MermaidValidator,
    /** Mermaid 导出器。 */
    private val mermaidExporter: MermaidExporter,
    /** 图 diff 比较器。 */
    private val graphDiffer: GraphDiffer,
    /** 同步预览规划器。 */
    private val syncPreviewPlanner: SyncPreviewPlanner,
    /** 剪贴板写入函数。 */
    private val copyToClipboard: (String) -> Boolean,
) {
    /**
     * 直接加载指定图文档到编辑器状态。
     */
    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        session.mutate {
            loadGraph(graph, source)
        }
    }

    /**
     * 处理前端主动上报的图结构变更。
     */
    fun handleFrontendGraphChanged(graph: GraphDocument) {
        session.markViewGraphChanged(
            graph = graph,
            displayMode = session.snapshot().analysisDisplayMode,
            syncBrowser = false,
        )
    }

    /**
     * 处理前端主动上报的布局变更。
     */
    fun handleFrontendLayoutChanged(positions: Map<String, GraphLayoutPosition>) {
        session.mutate(syncBrowser = false) {
            markLayoutChanged(positions)
        }
    }

    /**
     * 导入 Mermaid 文本并刷新编辑器状态。
     */
    fun importMermaid(mermaid: String): GraphDocument {
        val parseResult = mermaidImporter.import(mermaid)
        val issues = mermaidValidator.validate(parseResult.document, parseResult.issues)
        session.mutate {
            importMermaid(mermaid, parseResult.document, issues)
        }
        return parseResult.document
    }

    /**
     * 导出当前可见图或设计基线为 Mermaid 文本。
     */
    fun exportMermaid(): String {
        val snapshot = session.snapshot()
        val document = snapshot.designBaselineGraph
            ?: if (snapshot.analysisDisplayMode == AnalysisDisplayMode.FLOWCHART) {
                snapshot.flowchartView?.fullGraph?.takeIf { graph -> graph.nodes.isNotEmpty() || graph.edges.isNotEmpty() }
            } else {
                null
            }
            ?: currentVisibleGraph(snapshot)
        return mermaidExporter.export(document).also { exported ->
            session.mutateBatch {
                apply {
                    markMermaidExported(exported)
                }
                val copiedToClipboard = copyToClipboard(exported)
                apply {
                    markOperationFeedback(
                        GraphEditorStateService.OperationFeedbackLevel.SUCCESS,
                        if (copiedToClipboard) {
                            "已导出 Mermaid，并复制到剪贴板。"
                        } else {
                            "已导出 Mermaid。"
                        },
                    )
                }
            }
        }
    }

    /**
     * 进入代码事实图与设计基线的差异模式。
     */
    fun showDiffMode(): GraphDifferResult? {
        val snapshot = session.snapshot()
        val codeGraph = snapshot.referenceFactGraph ?: return null
        val designGraph = snapshot.designBaselineGraph ?: return null
        return graphDiffer.diff(codeGraph, designGraph).also { result ->
            session.mutate {
                showDiffMode(result.graph, result.diff)
            }
        }
    }

    /**
     * 生成当前图的同步预览列表。
     */
    fun requestSyncPreview(): List<SyncPreviewItem> {
        val snapshot = session.snapshot()
        val previewContext = when {
            snapshot.visibleGraph != null && snapshot.diff != null -> snapshot.visibleGraph to snapshot.diff
            snapshot.referenceFactGraph != null && snapshot.designBaselineGraph != null -> {
                val result = graphDiffer.diff(snapshot.referenceFactGraph, snapshot.designBaselineGraph)
                result.graph to result.diff
            }

            else -> null
        }
        val previewItems = previewContext?.let { (graph, diff) ->
            graph?.let { syncPreviewPlanner.plan(it, diff) }
        }.orEmpty()
        session.mutate {
            requestSyncPreview(previewItems)
        }
        return previewItems
    }

}
