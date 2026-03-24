package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MermaidImporterTest {
    @Test
    fun importsMethodAndNonMethodNodesWithTypedEdges() {
        val mermaid = """
            graph TD
            M1["METHOD|OrderService.place(java.lang.String):void|signature=OrderService.place(java.lang.String):void"]
            H1["HTTP_ENDPOINT|POST /api/orders|path=/api/orders"]
            M1 -- ROUTES_TO --> H1
        """.trimIndent()

        val result = MermaidImporter().import(mermaid)

        assertTrue(result.issues.isEmpty(), "Unexpected issues: ${result.issues}")
        val document = result.document
        assertEquals(2, document.nodes.size)
        assertEquals(1, document.edges.size)

        val method = document.nodes.single { it.id == "M1" }
        assertEquals(NodeType.METHOD, method.type)
        assertEquals("OrderService.place(java.lang.String):void", method.signature)

        val endpoint = document.nodes.single { it.id == "H1" }
        assertEquals(NodeType.HTTP_ENDPOINT, endpoint.type)
        assertEquals("/api/orders", endpoint.metadata["path"])

        val edge = document.edges.single()
        assertEquals(EdgeType.ROUTES_TO, edge.type)
        assertEquals("M1", edge.fromNodeId)
        assertEquals("H1", edge.toNodeId)
    }

    @Test
    fun exporterUsesDeterministicOrderAndTypedEdges() {
        val document = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "Z9",
                    type = NodeType.HTTP_ENDPOINT,
                    title = "POST /api/orders",
                    metadata = mapOf("path" to "/api/orders"),
                ),
                GraphNode(
                    id = "A1",
                    type = NodeType.METHOD,
                    title = "OrderService.place(java.lang.String):void",
                    signature = "OrderService.place(java.lang.String):void",
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "E1",
                    type = EdgeType.ROUTES_TO,
                    fromNodeId = "A1",
                    toNodeId = "Z9",
                ),
            ),
        )

        val exported = MermaidExporter().export(document)

        assertEquals(
            """
            graph TD
            A1["METHOD|OrderService.place(java.lang.String):void|signature=OrderService.place(java.lang.String):void"]
            Z9["HTTP_ENDPOINT|POST /api/orders|path=/api/orders"]
            A1 -- ROUTES_TO --> Z9
            """.trimIndent(),
            exported,
        )
    }
}
