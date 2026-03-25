package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphEditorPageRendererTest {
    @Test
    fun rendersBootstrapStateIntoFrontendHtml() {
        val renderer = GraphEditorPageRenderer()
        val html = """
            <html>
              <head><title>Link Graph</title></head>
              <body><div id="root"></div></body>
            </html>
        """.trimIndent()
        val snapshot = GraphEditorStateService.Snapshot(
            graph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit-order",
                        type = NodeType.METHOD,
                        title = "OrderController.submit",
                        signature = "com.example.OrderController.submit():void",
                        doc = "Submit order entry.",
                    ),
                ),
                edges = listOf(
                    GraphEdge(
                        id = "call:submit-order->draft-dto",
                        type = com.charmnight.linkgraph.model.EdgeType.CALL,
                        fromNodeId = "method:submit-order",
                        toNodeId = "class:order-draft-dto",
                    ),
                ),
            ),
            selectedNodeId = "method:submit-order",
            diff = GraphDiff(
                entries = listOf(
                    GraphDiffEntry(
                        elementKind = GraphDiffElementKind.NODE,
                        elementId = "class:order-draft-dto",
                        status = DiffStatus.ONLY_IN_MERMAID,
                        message = "Design node is missing from code.",
                    ),
                ),
            ),
        )

        val rendered = renderer.render(html, snapshot)

        assertTrue(rendered.contains("window.linkGraphBootstrap"))
        assertTrue(rendered.contains("OrderController.submit"))
        assertTrue(rendered.contains("ONLY_IN_MERMAID"))
        assertTrue(rendered.contains("method:submit-order"))
    }
}
