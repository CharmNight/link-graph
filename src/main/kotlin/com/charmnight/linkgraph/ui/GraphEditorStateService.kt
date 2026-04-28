package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.intellij.openapi.components.Service

@Service(Service.Level.PROJECT)
class GraphEditorStateService {
    private val graphPatchApplyService = GraphPatchApplyService()
    private val store = GraphEditorStateStore()

    internal val graph: GraphEditorGraphStateSupport = GraphEditorGraphStateSupport(::mutate, graphPatchApplyService)
    internal val asyncRequests: GraphEditorAsyncRequestStateSupport = GraphEditorAsyncRequestStateSupport(::mutate)
    internal val workbench: GraphEditorWorkbenchStateSupport = GraphEditorWorkbenchStateSupport(::mutate)

    fun snapshot(): GraphEditorStateSnapshot = store.snapshot()

    fun markFrontendLoaded(entryUrl: String) = graph.markFrontendLoaded(entryUrl)

    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) = this.graph.loadGraph(graph, source)

    fun loadGraphProjection(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        source: String,
        selectedMethodSignature: String? = null,
    ) = this.graph.loadGraphProjection(visibleGraph, fullGraph, source, selectedMethodSignature)

    fun loadAnalysisOutcome(
        outcome: AnalysisOutcome,
        source: String,
    ) = this.graph.loadAnalysisOutcome(outcome, source)

    fun importMermaid(
        mermaid: String,
        graph: GraphDocument? = null,
        mermaidIssues: List<MermaidIssue> = emptyList(),
    ) = this.graph.importMermaid(mermaid, graph, mermaidIssues)

    fun markMermaidExported(mermaid: String) = graph.markMermaidExported(mermaid)

    fun showDiffMode(
        graph: GraphDocument,
        diff: GraphDiff,
    ) = this.graph.showDiffMode(graph, diff)

    fun pushSelectedMethod(signature: String) = graph.pushSelectedMethod(signature)

    fun selectNode(nodeId: String) = graph.selectNode(nodeId)

    fun switchAnalysisDisplayMode(displayMode: AnalysisDisplayMode) = graph.switchAnalysisDisplayMode(displayMode)

    fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
    ) = this.graph.markGraphChanged(graph, selectedMethodSignature, preserveDraftPatchUndo, workingGraphDirty)

    fun markLayoutChanged(positions: Map<String, GraphLayoutPosition>) = graph.markLayoutChanged(positions)

    fun requestSourceNavigation(nodeId: String) = graph.requestSourceNavigation(nodeId)

    fun markSourceNavigationOpened(
        nodeId: String,
        targetPath: String,
        line: Int?,
        column: Int?,
    ) = graph.markSourceNavigationOpened(nodeId, targetPath, line, column)

    fun markSourceNavigationNotFound(nodeId: String) = graph.markSourceNavigationNotFound(nodeId)

    fun markSourceNavigationFailed(
        nodeId: String,
        message: String,
    ) = graph.markSourceNavigationFailed(nodeId, message)

    fun markToolWindowOpened() = graph.markToolWindowOpened()

    fun markLastMessageType(messageType: String) = graph.markLastMessageType(messageType)

    internal fun mutate(transform: (GraphEditorStateSnapshot) -> GraphEditorStateSnapshot): GraphEditorStateSnapshot {
        return store.mutate(transform)
    }

    internal fun tryCommit(
        expectedRevision: Long,
        transform: (GraphEditorStateSnapshot) -> GraphEditorStateSnapshot,
    ): GraphEditorStateCommitResult {
        return store.tryCommit(expectedRevision, transform)
    }
}
