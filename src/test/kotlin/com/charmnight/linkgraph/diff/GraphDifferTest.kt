package com.charmnight.linkgraph.diff

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphUncertainty
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphDifferTest {
    @Test
    fun bindsMermaidNodesToCodeAndMarksExclusiveNodes() {
        val signature = "com.example.OrderService.place(java.lang.String):void"
        val codeEntry = methodNode(
            id = GraphNode.stableId(NodeType.METHOD, signature),
            signature = signature,
            title = "OrderService.place",
        )
        val codeOnly = GraphNode(
            id = GraphNode.stableId(NodeType.CLASS, "com.example.LegacyOrderGateway"),
            type = NodeType.CLASS,
            title = "LegacyOrderGateway",
        )
        val mermaidEntry = methodNode(
            id = "design-entry",
            signature = signature,
            title = "OrderService.place",
        )
        val mermaidOnly = GraphNode(
            id = "design-dto",
            type = NodeType.CLASS,
            title = "OrderDraftDto",
        )

        val result = GraphDiffer().diff(
            codeGraph = GraphDocument(nodes = listOf(codeEntry, codeOnly)),
            mermaidGraph = GraphDocument(nodes = listOf(mermaidEntry, mermaidOnly)),
        )

        val matchedNode = result.graph.nodes.single { it.id == codeEntry.id }
        assertEquals(DiffStatus.MATCHED, matchedNode.diff.status)

        val codeOnlyNode = result.graph.nodes.single { it.id == codeOnly.id }
        assertEquals(DiffStatus.ONLY_IN_CODE, codeOnlyNode.diff.status)

        val mermaidOnlyNode = result.graph.nodes.single { it.title == "OrderDraftDto" }
        assertEquals(DiffStatus.ONLY_IN_MERMAID, mermaidOnlyNode.diff.status)

        assertTrue(
            result.diff.entries.any { it.elementId == codeOnly.id && it.status == DiffStatus.ONLY_IN_CODE },
        )
        assertTrue(
            result.diff.entries.any { it.elementId == mermaidOnlyNode.id && it.status == DiffStatus.ONLY_IN_MERMAID },
        )
        assertTrue(result.diff.summary.orEmpty().contains("仅代码存在"))
        assertTrue(result.diff.summary.orEmpty().contains("仅 Mermaid 存在"))
    }

    @Test
    fun marksBoundNodesAsModifiedWhenAttributesDiffer() {
        val signature = "com.example.OrderService.place(java.lang.String):com.example.OrderReceipt"
        val codeNode = methodNode(
            id = GraphNode.stableId(NodeType.METHOD, signature),
            signature = signature,
            title = "OrderService.place",
            outputs = listOf("com.example.OrderReceipt"),
            doc = "Places an order.",
            metadata = mapOf("transactional" to "true"),
        )
        val mermaidNode = methodNode(
            id = "design-place-order",
            signature = signature,
            title = "OrderService.place",
            outputs = listOf("com.example.OrderView"),
            doc = "Places an order and returns the view model.",
            metadata = mapOf("transactional" to "false"),
        )

        val result = GraphDiffer().diff(
            codeGraph = GraphDocument(nodes = listOf(codeNode)),
            mermaidGraph = GraphDocument(nodes = listOf(mermaidNode)),
        )

        val diffedNode = result.graph.nodes.single()
        assertEquals(DiffStatus.MODIFIED, diffedNode.diff.status)
        assertTrue(diffedNode.diff.fields.contains("outputs"))
        assertTrue(diffedNode.diff.fields.contains("doc"))
        assertTrue(diffedNode.diff.fields.contains("metadata.transactional"))
    }

    @Test
    fun marksEdgeEndpointsAsModifiedWhenTopologyChanges() {
        val caller = methodNode(
            id = GraphNode.stableId(NodeType.METHOD, "com.example.OrderController.submit():void"),
            signature = "com.example.OrderController.submit():void",
            title = "OrderController.submit",
        )
        val submit = methodNode(
            id = GraphNode.stableId(NodeType.METHOD, "com.example.OrderService.submit():void"),
            signature = "com.example.OrderService.submit():void",
            title = "OrderService.submit",
        )
        val validate = methodNode(
            id = GraphNode.stableId(NodeType.METHOD, "com.example.OrderService.validate():void"),
            signature = "com.example.OrderService.validate():void",
            title = "OrderService.validate",
        )
        val codeEdge = GraphEdge(
            id = GraphEdge.stableId(EdgeType.CALL, caller.id, submit.id),
            type = EdgeType.CALL,
            fromNodeId = caller.id,
            toNodeId = submit.id,
        )

        val mermaidCaller = caller.copy(id = "design-caller")
        val mermaidValidate = validate.copy(id = "design-validate")
        val mermaidEdge = GraphEdge(
            id = GraphEdge.stableId(EdgeType.CALL, mermaidCaller.id, mermaidValidate.id),
            type = EdgeType.CALL,
            fromNodeId = mermaidCaller.id,
            toNodeId = mermaidValidate.id,
        )

        val result = GraphDiffer().diff(
            codeGraph = GraphDocument(nodes = listOf(caller, submit, validate), edges = listOf(codeEdge)),
            mermaidGraph = GraphDocument(nodes = listOf(mermaidCaller, mermaidValidate), edges = listOf(mermaidEdge)),
        )

        val diffedEdge = result.graph.edges.single()
        assertEquals(DiffStatus.MODIFIED, diffedEdge.diff.status)
        assertTrue(diffedEdge.diff.fields.contains("toNodeId"))
        assertTrue(
            result.diff.entries.any { it.elementId == diffedEdge.id && it.status == DiffStatus.MODIFIED },
        )
    }

    @Test
    fun marksUncertaintyDescriptionMismatchAsModified() {
        val codeNode = GraphNode(
            id = GraphNode.stableId(NodeType.UNCERTAIN_LINK, "SPI Provider Candidates"),
            type = NodeType.UNCERTAIN_LINK,
            title = "SPI Provider Candidates",
            uncertainty = GraphUncertainty(reason = "Providers discovered from META-INF/services"),
        )
        val mermaidNode = GraphNode(
            id = "spi-candidates",
            type = NodeType.UNCERTAIN_LINK,
            title = "SPI Provider Candidates",
            uncertainty = GraphUncertainty(reason = "Manual expectation from design review"),
        )

        val result = GraphDiffer().diff(
            codeGraph = GraphDocument(nodes = listOf(codeNode)),
            mermaidGraph = GraphDocument(nodes = listOf(mermaidNode)),
        )

        val diffedNode = result.graph.nodes.single()
        assertEquals(DiffStatus.MODIFIED, diffedNode.diff.status)
        assertTrue(diffedNode.diff.fields.contains("uncertainty.reason"))
    }

    private fun methodNode(
        id: String,
        signature: String,
        title: String,
        outputs: List<String> = listOf("void"),
        doc: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): GraphNode {
        return GraphNode(
            id = id,
            type = NodeType.METHOD,
            title = title,
            signature = signature,
            inputs = listOf("java.lang.String"),
            outputs = outputs,
            doc = doc,
            metadata = metadata,
        )
    }
}
