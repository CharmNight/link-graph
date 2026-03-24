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
    fun ownerContextSensitiveNodeIdIsStable() {
        val withoutOwner = GraphNode.stableId(
            type = NodeType.METHOD,
            rawKey = " com.example.Service::load() ",
        )
        val withOwner1 = GraphNode.stableId(
            type = NodeType.METHOD,
            rawKey = " com.example.Service::load() ",
            ownerContext = "module:A",
        )
        val withOwner2 = GraphNode.stableId(
            type = NodeType.METHOD,
            rawKey = "com.example.Service::load()",
            ownerContext = " module-a ",
        )

        assertEquals("method:com-example-service-load", withoutOwner)
        assertEquals("method:module-a/com-example-service-load", withOwner1)
        assertEquals(withOwner1, withOwner2)
    }

    @Test
    fun edgeIdNormalization() {
        val edgeId = GraphEdge.stableId(
            type = EdgeType.REFLECTS_TO,
            fromNodeId = " METHOD:Com.Example::Caller() ",
            toNodeId = "method:com.example::Target()",
        )

        assertEquals("reflects-to:method-com-example-caller->method-com-example-target", edgeId)
    }

    @Test
    fun uncertainNodeSerialization() {
        val uncertainNode = GraphNode(
            id = GraphNode.stableId(NodeType.METHOD, "com.example::missing()"),
            type = NodeType.METHOD,
            title = "missing",
            location = "src/main/java/com/example/Service.java:42",
            signature = "missing(java.lang.String):void",
            inputs = listOf("java.lang.String"),
            outputs = listOf("void"),
            doc = "Generated from bytecode",
            sourceKind = "JAVA_METHOD",
            status = "DISCOVERED",
            certainty = Certainty.LLM_SUGGESTED,
            bindingStatus = BindingStatus.DESIGN_ONLY,
            uncertainty = GraphUncertainty(
                reason = "symbol unresolved",
                confidence = 0.35,
            ),
            evidence = listOf(GraphEvidence(source = "extractor", detail = "signature-only")),
            diff = GraphDiff(status = DiffStatus.ONLY_IN_CODE),
            metadata = mapOf("owner" to "module-a"),
        )
        val document = GraphDocument(nodes = listOf(uncertainNode))

        val json = GraphJson.toJson(document)
        assertTrue(json.contains("\"uncertainty\""))
        assertTrue(json.contains("\"reason\":\"symbol unresolved\""))
        assertTrue(json.contains("\"title\":\"missing\""))
        assertTrue(json.contains("\"sourceKind\":\"JAVA_METHOD\""))
        assertTrue(json.contains("\"status\":\"DISCOVERED\""))

        val parsed = GraphJson.fromJson(json)
        val parsedNode = parsed.nodes.single()
        assertEquals(Certainty.LLM_SUGGESTED, parsedNode.certainty)
        assertEquals("missing", parsedNode.title)
        assertEquals("src/main/java/com/example/Service.java:42", parsedNode.location)
        assertEquals("missing(java.lang.String):void", parsedNode.signature)
        assertEquals(listOf("java.lang.String"), parsedNode.inputs)
        assertEquals(listOf("void"), parsedNode.outputs)
        assertEquals("Generated from bytecode", parsedNode.doc)
        assertEquals("JAVA_METHOD", parsedNode.sourceKind)
        assertEquals("DISCOVERED", parsedNode.status)
        assertEquals(BindingStatus.DESIGN_ONLY, parsedNode.bindingStatus)
        assertEquals(DiffStatus.ONLY_IN_CODE, parsedNode.diff.status)
        assertNotNull(parsedNode.uncertainty)
        assertEquals("symbol unresolved", parsedNode.uncertainty.reason)
    }

    @Test
    fun diffStatusRoundTrip() {
        val node = GraphNode(
            id = GraphNode.stableId(NodeType.CLASS, "com.example.Service"),
            type = NodeType.CLASS,
            title = "Service",
            diff = GraphDiff(status = DiffStatus.ONLY_IN_MERMAID),
        )
        val edge = GraphEdge(
            id = GraphEdge.stableId(
                type = EdgeType.CALL,
                fromNodeId = GraphNode.stableId(NodeType.CLASS, "com.example.Caller"),
                toNodeId = node.id,
            ),
            type = EdgeType.CALL,
            fromNodeId = GraphNode.stableId(NodeType.CLASS, "com.example.Caller"),
            toNodeId = node.id,
            label = "calls",
            certainty = Certainty.RULE_INFERRED,
            bindingStatus = BindingStatus.PARTIALLY_SYNCED,
            status = "ACTIVE",
            diff = GraphDiff(status = DiffStatus.MODIFIED),
        )
        val original = GraphDocument(nodes = listOf(node), edges = listOf(edge))

        val roundTrip = GraphJson.fromJson(GraphJson.toJson(original))

        assertEquals(DiffStatus.ONLY_IN_MERMAID, roundTrip.nodes.single().diff.status)
        assertEquals(DiffStatus.MODIFIED, roundTrip.edges.single().diff.status)
        assertEquals(Certainty.RULE_INFERRED, roundTrip.edges.single().certainty)
        assertEquals(BindingStatus.PARTIALLY_SYNCED, roundTrip.edges.single().bindingStatus)
        assertEquals("ACTIVE", roundTrip.edges.single().status)
    }

    @Test
    fun approvedEnumSurfaceExists() {
        assertEquals(
            setOf(
                "METHOD",
                "CLASS",
                "SQL",
                "HTTP_ENDPOINT",
                "FEIGN_CLIENT",
                "DUBBO_SERVICE",
                "MQ_TOPIC",
                "MQ_CONSUMER",
                "CONFIG_ITEM",
                "XML_RESOURCE",
                "DOC_PAGE",
                "UNCERTAIN_LINK",
            ),
            NodeType.entries.map { it.name }.toSet(),
        )

        assertEquals(
            setOf(
                "CALL",
                "IMPLEMENTS",
                "INJECT",
                "ROUTES_TO",
                "MAPS_TO_SQL",
                "PUBLISHES_TO",
                "CONSUMES_FROM",
                "BINDS_CONFIG",
                "LINKS_DOC",
                "USES_PROXY",
                "REFLECTS_TO",
                "SPI_RESOLVES_TO",
                "GENERATES",
            ),
            EdgeType.entries.map { it.name }.toSet(),
        )

        assertEquals(
            setOf("PROVEN", "RULE_INFERRED", "LLM_SUGGESTED"),
            Certainty.entries.map { it.name }.toSet(),
        )

        assertEquals(
            setOf("BOUND", "DESIGN_ONLY", "GENERATABLE", "PARTIALLY_SYNCED", "CONFLICTED"),
            BindingStatus.entries.map { it.name }.toSet(),
        )

        assertEquals(
            setOf("MATCHED", "ONLY_IN_CODE", "ONLY_IN_MERMAID", "MODIFIED"),
            DiffStatus.entries.map { it.name }.toSet(),
        )
    }
}
