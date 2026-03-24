package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.intellij.openapi.project.Project

class GraphEditorBridge(private val project: Project) {
    private val stateService: GraphEditorStateService = project.getService(GraphEditorStateService::class.java)

    fun onFrontendLoaded(entryUrl: String) {
        stateService.markFrontendLoaded(entryUrl)
    }

    fun dispatch(message: GraphEditorMessage) {
        when (message) {
            is GraphEditorMessage.LoadGraph -> stateService.loadGraph(message.graph, message.source)
            is GraphEditorMessage.ImportMermaid -> stateService.importMermaid(message.mermaid)
            is GraphEditorMessage.ExportMermaid -> Unit
            is GraphEditorMessage.NodeSelected -> stateService.selectNode(message.nodeId)
            is GraphEditorMessage.GraphChanged -> stateService.markGraphChanged(message.graph)
            is GraphEditorMessage.RequestSourceNavigation -> stateService.requestSourceNavigation(message.nodeId)
            is GraphEditorMessage.RequestSyncPreview -> stateService.requestSyncPreview()
        }
    }

    fun loadGraph(
        graph: GraphDocument,
        source: String,
    ) {
        dispatch(GraphEditorMessage.LoadGraph(graph, source))
    }

    fun currentState(): GraphEditorStateService.Snapshot = stateService.snapshot()
}
