package com.charmnight.linkgraph.sync

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphProvenance
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
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
            ),
        )
        val noteNode = GraphNode(
            id = "note:manual-fallback",
            type = NodeType.CLASS,
            title = "ManualFallback",
            doc = "待补充业务兜底逻辑。",
            provenance = GraphProvenance.AI_DRAFT,
        )
        val edge = GraphEdge(
            id = "call:method-order-service-place->note-manual-fallback",
            type = EdgeType.CALL,
            fromNodeId = "method:order-service-place",
            toNodeId = "note:manual-fallback",
            label = "候选兜底",
            provenance = GraphProvenance.AI_DRAFT,
        )
        val patch = GraphPatch(
            summary = "apply qa suggestions",
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
        assertEquals(GraphProvenance.AI_DRAFT, updatedNote.provenance)
    }

    @Test
    fun deletingNodeAlsoRemovesIncidentEdges() {
        val noteNode = GraphNode(
            id = "note:manual-fallback",
            type = NodeType.CLASS,
            title = "ManualFallback",
            provenance = GraphProvenance.USER_DRAFT,
        )
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:order-service-place",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
                noteNode,
            ),
            edges = listOf(
                GraphEdge(
                    id = "call:method-order-service-place->note-manual-fallback",
                    type = EdgeType.CALL,
                    fromNodeId = "method:order-service-place",
                    toNodeId = noteNode.id,
                    provenance = GraphProvenance.USER_DRAFT,
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

    @Test
    fun preservesExistingGraphModelOrderWhenApplyingDraftPatch() {
        val baseGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:file-download",
                    type = NodeType.METHOD,
                    title = "CommonController.fileDownload",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
                GraphNode(
                    id = "scope:allow-download",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (!FileUtils.checkAllowDownload(fileName))",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
                GraphNode(
                    id = "scope:delete-file",
                    type = NodeType.FLOW_SCOPE,
                    title = "if (delete)",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
                GraphNode(
                    id = "terminal:return",
                    type = NodeType.TERMINAL,
                    title = "return",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "edge:entry-allow",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:file-download",
                    toNodeId = "scope:allow-download",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
                GraphEdge(
                    id = "edge:allow-delete",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "scope:allow-download",
                    toNodeId = "scope:delete-file",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
                GraphEdge(
                    id = "edge:delete-return",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "scope:delete-file",
                    toNodeId = "terminal:return",
                    provenance = GraphProvenance.CODE_ANALYSIS,
                ),
            ),
        )
        val patch = GraphPatch(
            operations = listOf(
                GraphPatchOperation(
                    id = "update-delete-guard",
                    action = GraphPatchAction.UPDATE_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = "scope:delete-file",
                    node = baseGraph.nodes[2].copy(
                        title = "if (Boolean.TRUE.equals(delete))",
                        provenance = GraphProvenance.AI_DRAFT,
                    ),
                ),
                GraphPatchOperation(
                    id = "add-earlier-sorting-node",
                    action = GraphPatchAction.ADD_NODE,
                    elementKind = GraphDiffElementKind.NODE,
                    elementId = "draft:aaa-check",
                    node = GraphNode(
                        id = "draft:aaa-check",
                        type = NodeType.FLOW_ACTION,
                        title = "Files.exists(Path.of(filePath))",
                        provenance = GraphProvenance.AI_DRAFT,
                    ),
                ),
                GraphPatchOperation(
                    id = "add-edge-delete-check",
                    action = GraphPatchAction.ADD_EDGE,
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = "edge:delete-check",
                    edge = GraphEdge(
                        id = "edge:delete-check",
                        type = EdgeType.CONTROL_FLOW,
                        fromNodeId = "scope:delete-file",
                        toNodeId = "draft:aaa-check",
                        provenance = GraphProvenance.AI_DRAFT,
                    ),
                ),
                GraphPatchOperation(
                    id = "add-edge-check-return",
                    action = GraphPatchAction.ADD_EDGE,
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = "edge:check-return",
                    edge = GraphEdge(
                        id = "edge:check-return",
                        type = EdgeType.CONTROL_FLOW,
                        fromNodeId = "draft:aaa-check",
                        toNodeId = "terminal:return",
                        provenance = GraphProvenance.AI_DRAFT,
                    ),
                ),
            ),
        )

        val applied = GraphPatchApplyService().apply(baseGraph, patch)

        assertEquals(
            listOf(
                "method:file-download",
                "scope:allow-download",
                "scope:delete-file",
                "terminal:return",
                "draft:aaa-check",
            ),
            applied.nodes.map { it.id },
        )
        assertEquals(
            listOf(
                "edge:entry-allow",
                "edge:allow-delete",
                "edge:delete-return",
                "edge:delete-check",
                "edge:check-return",
            ),
            applied.edges.map { it.id },
        )
    }
}
