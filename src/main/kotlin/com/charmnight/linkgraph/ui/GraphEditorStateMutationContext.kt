package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.GraphPatchApplyService

internal interface GraphEditorStateMutationContext {
    val graph: GraphEditorGraphStateSupport
    val asyncRequests: GraphEditorAsyncRequestStateSupport
    val workbench: GraphEditorWorkbenchStateSupport

    fun snapshot(): GraphEditorStateSnapshot

    fun markFrontendLoaded(entryUrl: String)

    fun loadGraph(
        graph: GraphDocument,
        source: String,
    )

    fun loadGraphProjection(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        source: String,
        selectedMethodSignature: String? = null,
    )

    fun loadAnalysisOutcome(
        outcome: AnalysisOutcome,
        source: String,
    )

    fun importMermaid(
        mermaid: String,
        graph: GraphDocument? = null,
        mermaidIssues: List<MermaidIssue> = emptyList(),
    )

    fun markMermaidExported(mermaid: String)

    fun showDiffMode(
        graph: GraphDocument,
        diff: GraphDiff,
    )

    fun pushSelectedMethod(signature: String)

    fun selectNode(nodeId: String)

    fun switchAnalysisDisplayMode(displayMode: AnalysisDisplayMode)

    fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
    )

    fun markWorkingGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
    )

    fun markViewGraphChanged(
        graph: GraphDocument,
        displayMode: AnalysisDisplayMode,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
    )

    fun markLayoutChanged(positions: Map<String, GraphLayoutPosition>)

    fun requestSourceNavigation(nodeId: String)

    fun markSourceNavigationOpened(
        nodeId: String,
        targetPath: String,
        line: Int?,
        column: Int?,
    )

    fun markSourceNavigationNotFound(nodeId: String)

    fun markSourceNavigationFailed(
        nodeId: String,
        message: String,
    )

    fun markToolWindowOpened()

    fun markLastMessageType(messageType: String)
}

internal class DraftGraphEditorStateMutationContext(
    initialState: GraphEditorStateSnapshot,
    graphPatchApplyService: GraphPatchApplyService,
) : GraphEditorStateMutationContext {
    private var draftState: GraphEditorStateSnapshot = initialState

    override val graph: GraphEditorGraphStateSupport = GraphEditorGraphStateSupport(::mutate, graphPatchApplyService)
    override val asyncRequests: GraphEditorAsyncRequestStateSupport = GraphEditorAsyncRequestStateSupport(::mutate)
    override val workbench: GraphEditorWorkbenchStateSupport = GraphEditorWorkbenchStateSupport(::mutate)

    override fun snapshot(): GraphEditorStateSnapshot = draftState.copy()

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

    override fun markWorkingGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String?,
        preserveDraftPatchUndo: Boolean,
        workingGraphDirty: Boolean,
    ) = this.graph.markWorkingGraphChanged(graph, selectedMethodSignature, preserveDraftPatchUndo, workingGraphDirty)

    override fun markViewGraphChanged(
        graph: GraphDocument,
        displayMode: AnalysisDisplayMode,
        selectedMethodSignature: String?,
        preserveDraftPatchUndo: Boolean,
        workingGraphDirty: Boolean,
    ) = this.graph.markViewGraphChanged(
        graph,
        displayMode,
        selectedMethodSignature,
        preserveDraftPatchUndo,
        workingGraphDirty,
    )

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

    fun committedState(): GraphEditorStateSnapshot = draftState

    private fun mutate(transform: (GraphEditorStateSnapshot) -> GraphEditorStateSnapshot) {
        val currentState = draftState
        val nextState = transform(currentState)
        draftState = nextState
    }
}
