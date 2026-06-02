package com.charmnight.linkgraph.model

import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
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
            sourceTag = GraphSourceTag.DRAFT_AI,
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
            sourceTag = GraphSourceTag.DESIGN_BASELINE,
            diff = GraphDiff(status = DiffStatus.MODIFIED),
        )
        val original = GraphDocument(
            nodes = listOf(node),
            edges = listOf(edge),
            patch = GraphPatch(
                summary = "apply ai suggestions",
                operations = listOf(
                    GraphPatchOperation(
                        id = "patch-op-1",
                        action = GraphPatchAction.ADD_NODE,
                        elementKind = GraphDiffElementKind.NODE,
                        elementId = node.id,
                        title = "新增草稿节点",
                        summary = "把 AI 建议节点加入草稿层",
                        node = node,
                    ),
                ),
                addedNodeIds = listOf(node.id),
            ),
        )

        val roundTrip = GraphJson.fromJson(GraphJson.toJson(original))

        assertEquals(DiffStatus.ONLY_IN_MERMAID, roundTrip.nodes.single().diff.status)
        assertEquals(DiffStatus.MODIFIED, roundTrip.edges.single().diff.status)
        assertEquals(Certainty.RULE_INFERRED, roundTrip.edges.single().certainty)
        assertEquals(BindingStatus.PARTIALLY_SYNCED, roundTrip.edges.single().bindingStatus)
        assertEquals("ACTIVE", roundTrip.edges.single().status)
        assertEquals(GraphSourceTag.DRAFT_AI, roundTrip.nodes.single().sourceTag)
        assertEquals(GraphSourceTag.DESIGN_BASELINE, roundTrip.edges.single().sourceTag)
        assertNotNull(roundTrip.patch)
        assertEquals("apply ai suggestions", roundTrip.patch.summary)
        assertEquals(GraphPatchAction.ADD_NODE, roundTrip.patch.operations.single().action)
        assertEquals(node.id, roundTrip.patch.operations.single().elementId)
    }

    @Test
    fun flowScopeAndContainmentEdgeRoundTripThroughJson() {
        val flowScopeNode = GraphNode(
            id = GraphNode.stableId(NodeType.FLOW_SCOPE, "if (line.isActive())", "com.example.OrderService.process():void"),
            type = NodeType.FLOW_SCOPE,
            title = "if (line.isActive())",
            location = "src/main/java/com/example/OrderService.java:18:9",
            signature = "branch body · Line",
            doc = "只处理有效订单行。",
            sourceKind = "JAVA_FLOW_SCOPE",
            metadata = mapOf(
                "flow.kind" to "IF",
                "flow.parentNodeId" to "method:com-example-orderservice-process",
            ),
        )
        val containmentEdge = GraphEdge(
            id = GraphEdge.stableId(
                EdgeType.CONTAINS_FLOW,
                "method:com-example-orderservice-process",
                flowScopeNode.id,
            ),
            type = EdgeType.CONTAINS_FLOW,
            fromNodeId = "method:com-example-orderservice-process",
            toNodeId = flowScopeNode.id,
            metadata = mapOf("callOrder" to "1"),
        )

        val roundTrip = GraphJson.fromJson(
            GraphJson.toJson(
                GraphDocument(
                    nodes = listOf(flowScopeNode),
                    edges = listOf(containmentEdge),
                ),
            ),
        )

        assertEquals(NodeType.FLOW_SCOPE, roundTrip.nodes.single().type)
        assertEquals("IF", roundTrip.nodes.single().metadata["flow.kind"])
        assertEquals("JAVA_FLOW_SCOPE", roundTrip.nodes.single().sourceKind)
        assertEquals(EdgeType.CONTAINS_FLOW, roundTrip.edges.single().type)
        assertEquals("1", roundTrip.edges.single().metadata["callOrder"])
    }

    @Test
    fun flowActionRoundTripPreservesSourceMappingMetadata() {
        val actionNode = GraphNode(
            id = "flow-action:copy-bean",
            type = NodeType.FLOW_ACTION,
            title = "BeanUtils.copyBeanProp(user, obj)",
            metadata = mapOf(
                "source.filePath" to "/tmp/ShiroUtils.java",
                "source.startOffset" to "100",
                "source.endOffset" to "132",
                "flow.anchorMethod" to "com.ruoyi.common.utils.ShiroUtils.getSysUser():SysUser",
            ),
        )

        val roundTrip = GraphJson.fromJson(
            GraphJson.toJson(
                GraphDocument(nodes = listOf(actionNode)),
            ),
        )

        assertEquals(NodeType.FLOW_ACTION, roundTrip.nodes.single().type)
        assertEquals("/tmp/ShiroUtils.java", roundTrip.nodes.single().metadata["source.filePath"])
        assertEquals("100", roundTrip.nodes.single().metadata["source.startOffset"])
        assertEquals("132", roundTrip.nodes.single().metadata["source.endOffset"])
        assertEquals(
            "com.ruoyi.common.utils.ShiroUtils.getSysUser():SysUser",
            roundTrip.nodes.single().metadata["flow.anchorMethod"],
        )
    }

    @Test
    fun graphJsonRejectsTrailingGarbageAfterRootObject() {
        assertFailsWith<IllegalStateException> {
            GraphJson.fromJson("""{"nodes":[],"edges":[]} trailing""")
        }
    }

    @Test
    fun graphJsonEscapesAllLowControlCharacters() {
        val controlCharacters = (0..0x1f)
            .map(Int::toChar)
            .joinToString("")
        val json = GraphJson.toJson(
            GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:control-characters",
                        type = NodeType.METHOD,
                        title = "prefix${controlCharacters}suffix",
                    ),
                ),
            ),
        )

        (0..0x1f).map(Int::toChar).forEach { char ->
            assertFalse(json.contains(char), "JSON output must escape control char U+${char.code.toString(16).padStart(4, '0')}")
        }
        assertTrue(json.contains("\\u0000"))
        assertTrue(json.contains("\\u0008"))
        assertTrue(json.contains("\\u001f"))
    }

    @Test
    fun flowchartMetadataRoundTripPreservesExplicitControlFlowRoles() {
        val loopNode = GraphNode(
            id = "scope:foreach",
            type = NodeType.FLOW_SCOPE,
            title = "for (file : files)",
            metadata = mapOf(
                "flow.kind" to "FOREACH",
                "flow.scopeKind" to "FOREACH",
                "flow.scopeCategory" to "LOOP_PRE_TEST",
                "flow.incomplete" to "false",
            ),
        )
        val loopBodyEdge = GraphEdge(
            id = "control:loop-body",
            type = EdgeType.CONTROL_FLOW,
            fromNodeId = loopNode.id,
            toNodeId = "action:upload",
            label = "TRUE",
            metadata = mapOf(
                "flow.edgeRole" to "LOOP_BODY",
                "flow.synthetic" to "false",
                "flow.provenance" to "SEMANTIC_ANALYSIS",
            ),
        )

        val roundTrip = GraphJson.fromJson(
            GraphJson.toJson(
                GraphDocument(
                    nodes = listOf(loopNode),
                    edges = listOf(loopBodyEdge),
                ),
            ),
        )

        assertEquals("LOOP_PRE_TEST", roundTrip.nodes.single().metadata["flow.scopeCategory"])
        assertEquals("FOREACH", roundTrip.nodes.single().metadata["flow.scopeKind"])
        assertEquals("false", roundTrip.nodes.single().metadata["flow.incomplete"])
        assertEquals("LOOP_BODY", roundTrip.edges.single().metadata["flow.edgeRole"])
        assertEquals("false", roundTrip.edges.single().metadata["flow.synthetic"])
        assertEquals("SEMANTIC_ANALYSIS", roundTrip.edges.single().metadata["flow.provenance"])
    }

    @Test
    fun approvedEnumSurfaceExists() {
        assertEquals(
            setOf(
                "METHOD",
                "FLOW_SCOPE",
                "FLOW_ACTION",
                "TERMINAL",
                "MERGE",
                "CLASS",
                "MODULE",
                "PACKAGE",
                "INTERFACE",
                "ENUM",
                "ANNOTATION",
                "RECORD",
                "OBJECT",
                "EXTERNAL_CLASS",
                "LIBRARY",
                "SERVICE",
                "COMPONENT",
                "LAYER",
                "RESOURCE",
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
                "CONTAINS_FLOW",
                "CONTROL_FLOW",
                "IMPLEMENTS",
                "EXTENDS",
                "USES_TYPE",
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
                "TESTS",
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

        assertEquals(
            setOf("FACT", "DESIGN_BASELINE", "DRAFT_MANUAL", "DRAFT_AI", "UNCERTAIN_FACT"),
            GraphSourceTag.entries.map { it.name }.toSet(),
        )

        assertEquals(
            setOf(
                "ADD_NODE",
                "UPDATE_NODE",
                "DELETE_NODE",
                "ADD_EDGE",
                "UPDATE_EDGE",
                "DELETE_EDGE",
                "ADD_ANNOTATION",
                "MARK_UNCERTAIN",
            ),
            GraphPatchAction.entries.map { it.name }.toSet(),
        )
    }
}
