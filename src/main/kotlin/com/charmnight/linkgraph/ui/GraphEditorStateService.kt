package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.intellij.openapi.components.Service

@Service(Service.Level.PROJECT)
class GraphEditorStateService {
    private val lock = Any()
    private var state = Snapshot()

    fun snapshot(): Snapshot = synchronized(lock) { state.copy() }

    fun markFrontendLoaded(entryUrl: String) {
        mutate {
            it.copy(
                frontendEntryUrl = entryUrl,
                lastMessageType = "frontendLoaded",
            )
        }
    }

    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        mutate {
            it.copy(
                graph = graph,
                codeGraph = graph,
                designGraph = null,
                diff = null,
                diffMode = false,
                importedMermaid = null,
                exportedMermaid = null,
                lastGraphSource = source,
                lastMessageType = "loadGraph",
            )
        }
    }

    fun importMermaid(
        mermaid: String,
        graph: GraphDocument? = null,
    ) {
        mutate { currentState: Snapshot ->
            currentState.copy(
                graph = graph ?: currentState.graph,
                designGraph = graph ?: currentState.designGraph,
                diff = null,
                diffMode = false,
                importedMermaid = mermaid,
                lastMessageType = "importMermaid",
            )
        }
    }

    fun markMermaidExported(mermaid: String) {
        mutate {
            it.copy(
                exportedMermaid = mermaid,
                lastMessageType = "exportMermaid",
            )
        }
    }

    fun showDiffMode(
        graph: GraphDocument,
        diff: GraphDiff,
    ) {
        mutate {
            it.copy(
                graph = graph,
                diff = diff,
                diffMode = true,
                lastMessageType = "showDiffMode",
            )
        }
    }

    fun pushSelectedMethod(signature: String) {
        mutate {
            it.copy(
                selectedMethodSignature = signature,
                lastMessageType = "selectedMethod",
            )
        }
    }

    fun selectNode(nodeId: String) {
        mutate {
            it.copy(
                selectedNodeId = nodeId,
                lastMessageType = "nodeSelected",
            )
        }
    }

    fun markGraphChanged(graph: GraphDocument) {
        mutate { currentState: Snapshot ->
            currentState.copy(
                graph = graph,
                designGraph = if (currentState.diffMode) currentState.designGraph else graph,
                lastMessageType = "graphChanged",
            )
        }
    }

    fun requestSourceNavigation(nodeId: String) {
        mutate {
            it.copy(
                sourceNavigationNodeId = nodeId,
                lastMessageType = "requestSourceNavigation",
            )
        }
    }

    fun requestSyncPreview() {
        mutate {
            it.copy(
                syncPreviewRequested = true,
                lastMessageType = "requestSyncPreview",
            )
        }
    }

    fun markToolWindowOpened() {
        mutate {
            it.copy(
                toolWindowOpenRequested = true,
                lastMessageType = "toolWindowOpened",
            )
        }
    }

    private fun mutate(transform: (Snapshot) -> Snapshot) {
        synchronized(lock) {
            state = transform(state)
        }
    }

    data class Snapshot(
        val graph: GraphDocument? = null,
        val codeGraph: GraphDocument? = null,
        val designGraph: GraphDocument? = null,
        val diff: GraphDiff? = null,
        val diffMode: Boolean = false,
        val lastGraphSource: String? = null,
        val frontendEntryUrl: String? = null,
        val selectedMethodSignature: String? = null,
        val selectedNodeId: String? = null,
        val importedMermaid: String? = null,
        val exportedMermaid: String? = null,
        val sourceNavigationNodeId: String? = null,
        val syncPreviewRequested: Boolean = false,
        val toolWindowOpenRequested: Boolean = false,
        val lastMessageType: String? = null,
    )
}
