package com.charmnight.linkgraph.sync

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphPatchApplyServiceTest {
    @Test
    fun appliesAddAndUpdateOperationsToDraftGraph() {
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
            ),
        )
        val noteNode = GraphNode(
            id = "note:manual-fallback",
            type = NodeType.CLASS,
            title = "ManualFallback",
            doc = "待补充业务兜底逻辑。",
            sourceTag = GraphSourceTag.DRAFT_AI,
        )
        val edge = GraphEdge(
            id = "call:method-order-service-place->note-manual-fallback",
            type = EdgeType.CALL,
            fromNodeId = "method:order-service-place",
            toNodeId = "note:manual-fallback",
            label = "候选兜底",
            sourceTag = GraphSourceTag.DRAFT_AI,
        )
        val patch = GraphPatch(
            summary = "apply audit suggestions",
            operations = listOf(
                GraphPatchOperation(
                    id = "add-note",
                    action = GraphPatchAction.ADD_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = noteNode.id,
                    node = noteNode,
                ),
                GraphPatchOperation(
                    id = "add-edge",
                    action = GraphPatchAction.ADD_EDGE,
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = edge.id,
                    edge = edge,
                ),
                GraphPatchOperation(
                    id = "update-note",
                    action = GraphPatchAction.UPDATE_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = noteNode.id,
                    node = noteNode.copy(doc = "已确认需要人工补充默认兜底逻辑。"),
                ),
            ),
        )

        val applied = GraphPatchApplyService().apply(baseGraph, patch)

        assertEquals(2, applied.nodes.size)
        assertEquals(1, applied.edges.size)
        val updatedNote = applied.nodes.single { it.id == noteNode.id }
        assertEquals("已确认需要人工补充默认兜底逻辑。", updatedNote.doc)
        assertEquals(GraphSourceTag.DRAFT_AI, updatedNote.sourceTag)
    }

    @Test
    fun deletingNodeAlsoRemovesIncidentEdges() {
        val noteNode = GraphNode(
            id = "note:manual-fallback",
            type = NodeType.CLASS,
            title = "ManualFallback",
            sourceTag = GraphSourceTag.DRAFT_MANUAL,
        )
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    sourceTag = GraphSourceTag.FACT,
                ),
                noteNode,
            ),
            edges = listOf(
                GraphEdge(
                    id = "call:method-order-service-place->note-manual-fallback",
                    type = EdgeType.CALL,
                    fromNodeId = "method:order-service-place",
                    toNodeId = noteNode.id,
                    sourceTag = GraphSourceTag.DRAFT_MANUAL,
                ),
            ),
        )
        val patch = GraphPatch(
            operations = listOf(
                GraphPatchOperation(
                    id = "delete-note",
                    action = GraphPatchAction.DELETE_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = noteNode.id,
                ),
            ),
        )

        val applied = GraphPatchApplyService().apply(baseGraph, patch)

        assertEquals(1, applied.nodes.size)
        assertTrue(applied.nodes.none { it.id == noteNode.id })
        assertTrue(applied.edges.isEmpty())
    }
}
