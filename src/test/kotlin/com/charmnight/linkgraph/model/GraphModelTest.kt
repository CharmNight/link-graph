package com.charmnight.linkgraph.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphModelTest {
    @Test
    fun stableNodeIdNormalization() {
        val stableId = GraphNode.stableId(
            type = NodeType.METHOD,
            rawKey = "  com.Example.Service::Foo Bar()  ",
        )

        assertEquals("method:com-example-service-foo-bar", stableId)
    }

    @Test
    fun edgeIdNormalization() {
        val edgeId = GraphEdge.stableId(
            type = EdgeType.UNCERTAIN_LINK,
            fromNodeId = " METHOD:Com.Example::Caller() ",
            toNodeId = "method:com.example::Target()",
        )

        assertEquals("uncertain-link:method-com-example-caller->method-com-example-target", edgeId)
    }

    @Test
    fun uncertainNodeSerialization() {
        val uncertainNode = GraphNode(
            id = GraphNode.stableId(NodeType.METHOD, "com.example::missing()"),
            type = NodeType.METHOD,
            label = "missing",
            certainty = Certainty.UNCERTAIN,
            bindingStatus = BindingStatus.UNBOUND,
            uncertainty = GraphUncertainty(
                reason = "symbol unresolved",
                confidence = 0.35,
            ),
            evidence = listOf(GraphEvidence(source = "extractor", detail = "signature-only")),
        )
        val document = GraphDocument(nodes = listOf(uncertainNode))

        val json = GraphJson.toJson(document)
        assertTrue(json.contains("\"uncertainty\""))
        assertTrue(json.contains("\"reason\":\"symbol unresolved\""))

        val parsed = GraphJson.fromJson(json)
        val parsedNode = parsed.nodes.single()
        assertEquals(Certainty.UNCERTAIN, parsedNode.certainty)
        assertNotNull(parsedNode.uncertainty)
        assertEquals("symbol unresolved", parsedNode.uncertainty.reason)
    }

    @Test
    fun diffStatusRoundTrip() {
        val node = GraphNode(
            id = GraphNode.stableId(NodeType.CLASS, "com.example.Service"),
            type = NodeType.CLASS,
            label = "Service",
            diff = GraphDiff(status = DiffStatus.ADDED),
        )
        val edge = GraphEdge(
            id = GraphEdge.stableId(
                type = EdgeType.CALLS,
                fromNodeId = GraphNode.stableId(NodeType.CLASS, "com.example.Caller"),
                toNodeId = node.id,
            ),
            type = EdgeType.CALLS,
            fromNodeId = GraphNode.stableId(NodeType.CLASS, "com.example.Caller"),
            toNodeId = node.id,
            diff = GraphDiff(status = DiffStatus.REMOVED),
        )
        val original = GraphDocument(nodes = listOf(node), edges = listOf(edge))

        val roundTrip = GraphJson.fromJson(GraphJson.toJson(original))

        assertEquals(DiffStatus.ADDED, roundTrip.nodes.single().diff.status)
        assertEquals(DiffStatus.REMOVED, roundTrip.edges.single().diff.status)
    }
}
