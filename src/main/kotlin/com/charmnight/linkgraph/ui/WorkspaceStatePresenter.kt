package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.sync.SyncPreviewItem

class WorkspaceStatePresenter(
    private val stateService: GraphEditorStateService,
    private val requestBrowserSync: () -> Unit = {},
) {
    fun presentGraphLoaded(
        graph: GraphDocument,
        source: String,
    ) {
        stateService.graph.loadGraph(graph, source)
        requestBrowserSync()
    }

    fun presentGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String?,
        preserveDraftPatchUndo: Boolean,
        workingGraphDirty: Boolean,
        graphEditTransaction: GraphEditTransaction? = null,
    ) {
        stateService.graph.markGraphChanged(
            graph = graph,
            selectedMethodSignature = selectedMethodSignature,
            preserveDraftPatchUndo = preserveDraftPatchUndo,
            workingGraphDirty = workingGraphDirty,
            graphEditTransaction = graphEditTransaction,
        )
        requestBrowserSync()
    }

    fun presentLayoutChanged(positions: Map<String, GraphLayoutPosition>) {
        stateService.graph.markLayoutChanged(positions)
    }

    fun presentMermaidImported(
        mermaid: String,
        graph: GraphDocument,
        issues: List<MermaidIssue>,
    ) {
        stateService.graph.importMermaid(mermaid, graph, issues)
        requestBrowserSync()
    }

    fun presentMermaidExported(
        exported: String,
        copiedToClipboard: Boolean,
    ) {
        stateService.graph.markMermaidExported(exported)
        stateService.workbench.markOperationFeedback(
            OperationFeedbackLevel.SUCCESS,
            if (copiedToClipboard) {
                "已导出 Mermaid，并复制到剪贴板。"
            } else {
                "已导出 Mermaid。"
            },
        )
        requestBrowserSync()
    }

    fun presentDiffMode(
        graph: GraphDocument,
        diff: GraphDiff,
    ) {
        stateService.graph.showDiffMode(graph, diff)
        requestBrowserSync()
    }

    fun presentSyncPreview(items: List<SyncPreviewItem>) {
        stateService.workbench.requestSyncPreview(items)
        requestBrowserSync()
    }
}
