package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.semantic.outcome.AnalysisOutcome
import com.charmnight.linkgraph.sync.GraphPatchApplyService

internal class GraphEditorGraphStateSupport(
    private val mutate: ((GraphEditorStateSnapshot) -> GraphEditorStateSnapshot) -> Unit,
    private val graphPatchApplyService: GraphPatchApplyService,
) {
    fun markFrontendLoaded(entryUrl: String) {
        mutate {
            it.copy(
                frontendEntryUrl = entryUrl,
                lastMessageType = "frontendLoaded",
                snapshotRevision = it.snapshotRevision + 1,
            )
        }
    }

    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        mutate { currentState -> currentState.withLoadedGraph(graph, source) }
    }

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

    fun loadAnalysisOutcome(
        outcome: AnalysisOutcome,
        source: String,
    ) {
        mutate { currentState -> currentState.withLoadedAnalysisOutcome(outcome, source, graphPatchApplyService) }
    }

    fun importMermaid(
        mermaid: String,
        graph: GraphDocument? = null,
        mermaidIssues: List<com.charmnight.linkgraph.mermaid.MermaidIssue> = emptyList(),
    ) {
        mutate { currentState -> currentState.withImportedMermaid(mermaid, graph, mermaidIssues) }
    }

    fun markMermaidExported(mermaid: String) {
        mutate {
            it.copy(
                exportedMermaid = mermaid,
                lastMessageType = "exportMermaid",
                snapshotRevision = it.snapshotRevision + 1,
            )
        }
    }

    fun showDiffMode(
        graph: com.charmnight.linkgraph.model.GraphDocument,
        diff: com.charmnight.linkgraph.model.GraphDiff,
    ) {
        mutate { currentState -> currentState.withShownDiffMode(graph, diff) }
    }

    fun pushSelectedMethod(signature: String) {
        mutate { currentState -> currentState.withSelectedMethod(signature) }
    }

    fun selectNode(nodeId: String) {
        mutate { currentState -> currentState.withSelectedNode(nodeId) }
    }

    fun switchAnalysisDisplayMode(displayMode: AnalysisDisplayMode) {
        mutate { currentState -> currentState.withSwitchedAnalysisDisplayMode(displayMode) }
    }

    fun markGraphChanged(
        graph: GraphDocument,
        selectedMethodSignature: String? = null,
        preserveDraftPatchUndo: Boolean = false,
        workingGraphDirty: Boolean = true,
    ) {
        mutate { currentState ->
            currentState.withWorkspaceGraphChanged(
                graph = graph,
                selectedMethodSignatureOverride = selectedMethodSignature,
                preserveDraftPatchUndo = preserveDraftPatchUndo,
                workingGraphDirtyOverride = workingGraphDirty,
            )
        }
    }

    fun markLayoutChanged(positions: Map<String, GraphLayoutPosition>) {
        mutate { currentState -> currentState.withLayoutChanged(positions) }
    }

    fun requestSourceNavigation(nodeId: String) {
        mutate { currentState -> currentState.withRequestedSourceNavigation(nodeId) }
    }

    fun markSourceNavigationOpened(
        nodeId: String,
        targetPath: String,
        line: Int?,
        column: Int?,
    ) {
        mutate { currentState -> currentState.withOpenedSourceNavigation(nodeId, targetPath, line, column) }
    }

    fun markSourceNavigationNotFound(nodeId: String) {
        mutate { currentState -> currentState.withMissingSourceNavigation(nodeId) }
    }

    fun markSourceNavigationFailed(
        nodeId: String,
        message: String,
    ) {
        mutate { currentState -> currentState.withFailedSourceNavigation(nodeId, message) }
    }

    fun markToolWindowOpened() {
        mutate {
            it.copy(
                toolWindowOpenRequested = true,
                lastMessageType = "toolWindowOpened",
                snapshotRevision = it.snapshotRevision + 1,
            )
        }
    }

    fun markLastMessageType(messageType: String) {
        mutate {
            it.copy(
                lastMessageType = messageType,
                snapshotRevision = it.snapshotRevision + 1,
            )
        }
    }
}
