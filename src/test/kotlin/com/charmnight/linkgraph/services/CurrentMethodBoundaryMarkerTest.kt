package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.extract.ExtractionBoundary
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurrentMethodBoundaryMarkerTest {
    @Test
    fun appendsExplicitBoundaryNodeWhenCurrentMethodGraphOnlyHasAnchorNode() {
        val methodSignature = "com.example.order.OrderFacade.submit(java.lang.String):void"
        val anchorNode = GraphNode(
            id = GraphNode.stableId(NodeType.METHOD, methodSignature),
            type = NodeType.METHOD,
            title = "OrderFacade.submit",
            signature = methodSignature,
        )

        val result = CurrentMethodBoundaryMarker.appendIfNeeded(
            graph = GraphDocument(nodes = listOf(anchorNode)),
            methodSignature = methodSignature,
            methodDisplayName = "OrderFacade.submit",
            boundary = ExtractionBoundary(
                title = "Kotlin 静态提取边界",
                reason = "当前方法来自 Kotlin PSI，静态调用链提取暂未完整覆盖，已保留当前方法节点供人工核查。",
                kind = "KOTLIN_PSI_BOUNDARY",
            ),
        )

        assertEquals(2, result.nodes.size)
        assertEquals(1, result.edges.size)

        val boundaryNode = result.nodes.firstOrNull { it.type == NodeType.UNCERTAIN_LINK }
        assertNotNull(boundaryNode)
        assertEquals("Kotlin 静态提取边界", boundaryNode.title)
        assertEquals(Certainty.RULE_INFERRED, boundaryNode.certainty)
        assertEquals(BindingStatus.PARTIALLY_SYNCED, boundaryNode.bindingStatus)
        assertEquals(GraphSourceTag.UNCERTAIN_FACT, boundaryNode.sourceTag)
        assertTrue(boundaryNode.signature?.contains("Kotlin PSI") == true)
        assertTrue(boundaryNode.doc?.contains("继续问答") == true)
        assertEquals("KOTLIN_PSI_BOUNDARY", boundaryNode.metadata["linkGraph.boundary.kind"])

        assertTrue(
            result.edges.any { edge ->
                edge.type == EdgeType.CALL &&
                    edge.fromNodeId == anchorNode.id &&
                    edge.toNodeId == boundaryNode.id &&
                    edge.certainty == Certainty.RULE_INFERRED
            },
        )
    }

    @Test
    fun keepsGraphUnchangedWhenCurrentMethodAlreadyHasExpandedLinks() {
        val methodSignature = "com.example.order.OrderFacade.submit(java.lang.String):void"
        val anchorNode = GraphNode(
            id = GraphNode.stableId(NodeType.METHOD, methodSignature),
            type = NodeType.METHOD,
            title = "OrderFacade.submit",
            signature = methodSignature,
        )
        val downstreamNode = GraphNode(
            id = "method:order-service",
            type = NodeType.METHOD,
            title = "OrderService.place",
            signature = "com.example.order.OrderService.place(java.lang.String):void",
        )
        val graph = GraphDocument(
            nodes = listOf(anchorNode, downstreamNode),
            edges = listOf(
                com.charmnight.linkgraph.model.GraphEdge(
                    id = "call:submit->place",
                    type = EdgeType.CALL,
                    fromNodeId = anchorNode.id,
                    toNodeId = downstreamNode.id,
                ),
            ),
        )

        val result = CurrentMethodBoundaryMarker.appendIfNeeded(
            graph = graph,
            methodSignature = methodSignature,
            methodDisplayName = "OrderFacade.submit",
            boundary = ExtractionBoundary(
                title = "Kotlin 静态提取边界",
                reason = "当前方法来自 Kotlin PSI，静态调用链提取暂未完整覆盖，已保留当前方法节点供人工核查。",
                kind = "KOTLIN_PSI_BOUNDARY",
            ),
        )

        assertEquals(graph, result)
    }
}
