package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument

sealed interface GraphEditorMessage {
    data class LoadGraph(
        val graph: GraphDocument,
        val source: String,
    ) : GraphEditorMessage

    data class ImportMermaid(
        val mermaid: String,
    ) : GraphEditorMessage

    data object ExportMermaid : GraphEditorMessage

    data class NodeSelected(
        val nodeId: String,
    ) : GraphEditorMessage

    data class GraphChanged(
        val graph: GraphDocument,
    ) : GraphEditorMessage

    data class RequestSourceNavigation(
        val nodeId: String,
    ) : GraphEditorMessage

    data object RequestSyncPreview : GraphEditorMessage
}
