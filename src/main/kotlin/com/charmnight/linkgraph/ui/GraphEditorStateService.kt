package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.GraphPatchApplyService
import com.intellij.openapi.components.Service

/**
 * JCEF 图编辑器的项目级状态总入口。
 * 它只负责持有快照真值与提供分域 support，对外不再暴露大批 mutation facade。
 */
@Service(Service.Level.PROJECT)
class GraphEditorStateService {
    private val graphPatchApplyService = GraphPatchApplyService()
    private val lock = Any()
    private var state = GraphEditorStateSnapshot()

    internal val graph: GraphEditorGraphStateSupport = GraphEditorGraphStateSupport(::mutate, graphPatchApplyService)
    internal val asyncRequests: GraphEditorAsyncRequestStateSupport = GraphEditorAsyncRequestStateSupport(::mutate)
    internal val workbench: GraphEditorWorkbenchStateSupport = GraphEditorWorkbenchStateSupport(::mutate)

    fun snapshot(): GraphEditorStateSnapshot = synchronized(lock) { state.copy() }

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
    ) = graph.loadGraphProjection(visibleGraph, fullGraph, source, selectedMethodSignature)

    fun loadAnalysisOutcome(
        outcome: AnalysisOutcome,
        source: String,
    ) = graph.loadAnalysisOutcome(outcome, source)

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

    fun markWorkingGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
    ) = this.graph.markWorkingGraphChanged(graph, selectedMethodSignature, preserveDraftPatchUndo, workingGraphDirty)

    fun markViewGraphChanged(
        graph: GraphDocument,
        displayMode: AnalysisDisplayMode,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
    ) = this.graph.markViewGraphChanged(graph, displayMode, selectedMethodSignature, preserveDraftPatchUndo, workingGraphDirty)

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

    internal fun newDraftMutationContext(
        baseState: GraphEditorStateSnapshot = snapshot(),
    ): DraftGraphEditorStateMutationContext = DraftGraphEditorStateMutationContext(baseState, graphPatchApplyService)

    internal fun replaceSnapshot(nextState: GraphEditorStateSnapshot) {
        synchronized(lock) {
            val currentState = state
            state = when {
                nextState == currentState -> currentState
                nextState.snapshotRevision != currentState.snapshotRevision -> nextState
                else -> nextState.copy(snapshotRevision = currentState.snapshotRevision + 1)
            }
        }
    }

    private fun mutate(transform: (GraphEditorStateSnapshot) -> GraphEditorStateSnapshot) {
        synchronized(lock) {
            val currentState = state
            replaceSnapshot(transform(currentState))
        }
    }
}
