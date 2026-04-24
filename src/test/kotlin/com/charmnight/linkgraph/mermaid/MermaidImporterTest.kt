package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.testing.*

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
            %% LG_NODE M1|nodeType=METHOD|title=OrderService.place(java.lang.String):void|signature=OrderService.place(java.lang.String):void|location=src/main/java/com/example/OrderService.java:12|inputs=java.lang.String,com.example.OrderRequest|outputs=com.example.OrderResult|doc=Places an order.
            %% LG_NODE H1|nodeType=HTTP_ENDPOINT|title=POST /api/orders|path=/api/orders
            M1["OrderService.place"]
            H1["POST /api/orders"]
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
        assertEquals("src/main/java/com/example/OrderService.java:12", method.location)
        assertEquals(listOf("java.lang.String", "com.example.OrderRequest"), method.inputs)
        assertEquals(listOf("com.example.OrderResult"), method.outputs)
        assertEquals("Places an order.", method.doc)

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
                    location = "src/main/java/com/example/OrderService.java:12",
                    signature = "OrderService.place(java.lang.String):void",
                    inputs = listOf("java.lang.String", "com.example.OrderRequest"),
                    outputs = listOf("com.example.OrderResult"),
                    doc = "Places an order.",
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
            %% LG_NODE N1|nodeId=A1|nodeType=METHOD|title=OrderService.place%28java.lang.String%29:void|signature=OrderService.place%28java.lang.String%29:void|location=src/main/java/com/example/OrderService.java:12|inputs=java.lang.String,com.example.OrderRequest|outputs=com.example.OrderResult|doc=Places an order.
            %% LG_NODE N2|nodeId=Z9|nodeType=HTTP_ENDPOINT|title=POST /api/orders|path=/api/orders
            N1["Places an order.<br/>OrderService<br/>OrderResult place（String, OrderRequest）"]
            N2["HTTP 接口<br/>POST /api/orders"]
            %% LG_EDGE N1|to=N2|edgeType=ROUTES_TO
            N1 -- 路由 --> N2
            """.trimIndent(),
            exported,
        )
    }

    @Test
    fun importsExporterCommentMetadataAndRestoresOriginalNodeIds() {
        val mermaid = """
            graph TD
            %% LG_NODE N1|nodeId=method:order-service-place|nodeType=METHOD|title=OrderService.place|signature=OrderService.place%28java.lang.String%29:void|location=src/main/java/com/example/OrderService.java:12|inputs=java.lang.String,com.example.OrderRequest|outputs=com.example.OrderResult|doc=Places an order.
            %% LG_NODE N2|nodeId=http:endpoint-orders|nodeType=HTTP_ENDPOINT|title=POST /api/orders|path=/api/orders
            N1["Places an order.<br/>OrderService<br/>OrderResult place（String, OrderRequest）"]
            N2["HTTP 接口<br/>POST /api/orders"]
            %% LG_EDGE N1|to=N2|edgeType=ROUTES_TO
            N1 -- 路由 --> N2
        """.trimIndent()

        val result = MermaidImporter().import(mermaid)

        assertTrue(result.issues.isEmpty(), "Unexpected issues: ${result.issues}")
        assertEquals(2, result.document.nodes.size)
        assertEquals(1, result.document.edges.size)
        val method = result.document.nodes.single { it.id == "method:order-service-place" }
        assertEquals(NodeType.METHOD, method.type)
        assertEquals("OrderService.place", method.title)
        assertEquals("OrderService.place(java.lang.String):void", method.signature)
        assertEquals(listOf("java.lang.String", "com.example.OrderRequest"), method.inputs)
        assertEquals(listOf("com.example.OrderResult"), method.outputs)
        assertEquals("Places an order.", method.doc)
        assertEquals("/api/orders", result.document.nodes.single { it.id == "http:endpoint-orders" }.metadata["path"])
        val edge = result.document.edges.single()
        assertEquals("method:order-service-place", edge.fromNodeId)
        assertEquals("http:endpoint-orders", edge.toNodeId)
    }

    @Test
    fun rejectsTypePrefixedNodeBodiesWithoutCommentMetadata() {
        val mermaid = """
            graph TD
            M1["METHOD|OrderService.place"]
        """.trimIndent()

        val result = MermaidImporter().import(mermaid)

        assertEquals(1, result.issues.size)
        assertEquals("invalid-node-body", result.issues.single().code)
        val method = result.document.nodes.single()
        assertEquals(NodeType.UNCERTAIN_LINK, method.type)
        assertEquals("M1", method.title)
    }

    @Test
    fun acceptsFlowchartHeaderAsGraphDeclaration() {
        val mermaid = """
            flowchart TD
            %% LG_NODE M1|nodeType=METHOD|title=OrderService.place
            %% LG_NODE H1|nodeType=HTTP_ENDPOINT|title=POST /api/orders|path=/api/orders
            M1["OrderService.place"]
            H1["POST /api/orders"]
            M1 -- ROUTES_TO --> H1
        """.trimIndent()

        val result = MermaidImporter().import(mermaid)

        assertTrue(result.issues.isEmpty(), "Unexpected issues: ${result.issues}")
        assertEquals(2, result.document.nodes.size)
        assertEquals(1, result.document.edges.size)
        assertEquals(NodeType.METHOD, result.document.nodes.single { it.id == "M1" }.type)
        assertEquals(NodeType.HTTP_ENDPOINT, result.document.nodes.single { it.id == "H1" }.type)
        assertEquals(EdgeType.ROUTES_TO, result.document.edges.single().type)
    }

    @Test
    fun ignoresSubgraphWrappersProducedByReadableExporter() {
        val mermaid = """
            graph TD
            subgraph 上游
            %% LG_NODE N1|nodeId=caller|nodeType=METHOD|title=OrderFacade.submit|signature=com.example.OrderFacade.submit%28%29:void|layout.direction=UPSTREAM|layout.depth=1
            N1["OrderFacade<br/>void submit()"]
            end
            subgraph 当前
            %% LG_NODE N2|nodeId=anchor|nodeType=METHOD|title=OrderService.place|signature=com.example.OrderService.place%28%29:void|layout.direction=CURRENT|layout.depth=0
            N2["OrderService<br/>void place()"]
            end
            subgraph 下游
            %% LG_NODE N3|nodeId=callee|nodeType=SQL|title=insert into orders|layout.direction=DOWNSTREAM|layout.depth=1
            N3["SQL<br/>insert into orders"]
            end
            %% LG_EDGE N1|to=N2|edgeType=CALL
            N1 -- 调用 --> N2
            %% LG_EDGE N2|to=N3|edgeType=MAPS_TO_SQL
            N2 -- 映射到 SQL --> N3
        """.trimIndent()

        val result = MermaidImporter().import(mermaid)

        assertTrue(result.issues.isEmpty(), "Unexpected issues: ${result.issues}")
        assertEquals(3, result.document.nodes.size)
        assertEquals(2, result.document.edges.size)
        assertEquals("UPSTREAM", result.document.nodes.single { it.id == "caller" }.metadata["layout.direction"])
        assertEquals("CURRENT", result.document.nodes.single { it.id == "anchor" }.metadata["layout.direction"])
        assertEquals("DOWNSTREAM", result.document.nodes.single { it.id == "callee" }.metadata["layout.direction"])
    }

    @Test
    fun importsFlowScopeNodesAndContainmentEdgesFromExporterMetadata() {
        val source = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-process",
                    type = NodeType.METHOD,
                    title = "OrderService.process",
                    signature = "com.example.OrderService.process():void",
                ),
                GraphNode(
                    id = "flow:if-line-active",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (line.isActive())",
                    signature = "branch body · Line",
                    doc = "仅处理激活行。",
                    metadata = mapOf(
                        "flow.kind" to "IF",
                        "layout.direction" to "DOWNSTREAM",
                        "layout.depth" to "1",
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "contains:process->if",
                    type = EdgeType.CONTAINS_FLOW,
                    fromNodeId = "method:order-service-process",
                    toNodeId = "flow:if-line-active",
                    metadata = mapOf("callOrder" to "1"),
                ),
            ),
        )

        val result = MermaidImporter().import(MermaidExporter().export(source))

        assertTrue(result.issues.isEmpty(), "Unexpected issues: ${result.issues}")
        val flowScope = result.document.nodes.single { it.id == "flow:if-line-active" }
        assertEquals(NodeType.FLOW_SCOPE, flowScope.type)
        assertEquals("IF", flowScope.metadata["flow.kind"])
        assertEquals("DOWNSTREAM", flowScope.metadata["layout.direction"])
        val edge = result.document.edges.single()
        assertEquals(EdgeType.CONTAINS_FLOW, edge.type)
        assertEquals("1", edge.metadata["callOrder"])
        assertEquals("method:order-service-process", edge.fromNodeId)
        assertEquals("flow:if-line-active", edge.toNodeId)
    }
}
