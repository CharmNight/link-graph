package com.charmnight.linkgraph.sync

import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPreviewPlannerTest {
    @Test
    fun buildsDeterministicPreviewItemsFromGraphDiff() {
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place(java.lang.String):void",
                ),
                GraphNode(
                    id = "class:orderdraftdto",
                    type = NodeType.CLASS,
                    title = "OrderDraftDto",
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "call:method-order-service-place->class-orderdraftdto",
                    type = EdgeType.CALL,
                    fromNodeId = "method:order-service-place",
                    toNodeId = "class:orderdraftdto",
                ),
            ),
        )
        val diff = GraphDiff(
            entries = listOf(
                GraphDiffEntry(
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = "class:orderdraftdto",
                    status = DiffStatus.ONLY_IN_MERMAID,
                    message = "Mermaid 中存在，但代码中缺失。",
                ),
                GraphDiffEntry(
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = "call:method-order-service-place->class-orderdraftdto",
                    status = DiffStatus.ONLY_IN_MERMAID,
                    message = "边在 Mermaid 中存在，但代码中缺失。",
                ),
                GraphDiffEntry(
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = "method:order-service-place",
                    status = DiffStatus.MODIFIED,
                    fields = listOf("doc", "outputs"),
                ),
            ),
        )

        val items = SyncPreviewPlanner().plan(graph, diff)

        assertEquals(3, items.size)
        assertEquals("新增 OrderDraftDto", items[0].title)
        assertEquals(SyncPreviewRisk.LOW, items[0].risk)
        assertTrue(items[0].description.contains("OrderDraftDto"))
        assertEquals("补充 OrderService.place -> OrderDraftDto", items[1].title)
        assertEquals(SyncPreviewRisk.MEDIUM, items[1].risk)
        assertTrue(items[1].description.contains("调用连线"))
        assertEquals("对齐 OrderService.place", items[2].title)
        assertEquals(SyncPreviewRisk.MEDIUM, items[2].risk)
        assertTrue(items[2].description.contains("doc"))
        assertTrue(items[2].description.contains("outputs"))
    }
}
