package com.charmnight.linkgraph.ui

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
                lastGraphSource = source,
                lastMessageType = "loadGraph",
            )
        }
    }

    fun importMermaid(mermaid: String) {
        mutate {
            it.copy(
                importedMermaid = mermaid,
                lastMessageType = "importMermaid",
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
        mutate {
            it.copy(
                graph = graph,
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
        val lastGraphSource: String? = null,
        val frontendEntryUrl: String? = null,
        val selectedMethodSignature: String? = null,
        val selectedNodeId: String? = null,
        val importedMermaid: String? = null,
        val sourceNavigationNodeId: String? = null,
        val syncPreviewRequested: Boolean = false,
        val toolWindowOpenRequested: Boolean = false,
        val lastMessageType: String? = null,
    )
}
