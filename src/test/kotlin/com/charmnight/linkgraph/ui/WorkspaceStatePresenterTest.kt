package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceStatePresenterTest {
    @Test
    fun mapsGraphLoadImportExportAndSyncPreviewState() {
        val stateService = GraphEditorStateService()
        val presenter = WorkspaceStatePresenter(stateService)
        val graph = GraphDocument(nodes = listOf(GraphNode(id = "node-1", type = NodeType.METHOD, title = "n")))

        presenter.presentGraphLoaded(graph, "test")
        presenter.presentMermaidImported("graph TD\nA-->B", graph, emptyList())
        presenter.presentMermaidExported("graph TD\nA-->B", copiedToClipboard = true)

        val snapshot = stateService.snapshot()
        assertEquals(graph, snapshot.workspaceGraph)
        assertEquals("graph TD\nA-->B", snapshot.importedMermaid)
        assertEquals("graph TD\nA-->B", snapshot.exportedMermaid)
        assertEquals(OperationFeedbackLevel.SUCCESS, snapshot.operationFeedback?.level)
    }
}
