package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphAuditScopeResolverTest {
    @Test
    fun wholeChainScopePrefersCurrentDraftGraphOverStaleFactGraph() {
        val staleFactNode = GraphNode(
            id = "method:stale-fact",
            type = NodeType.METHOD,
            title = "FallbackGuard.handle",
            sourceTag = GraphSourceTag.FACT,
        )
        val currentDraftNode = GraphNode(
            id = "doc:manual-note",
            type = NodeType.DOC_PAGE,
            title = "人工补充说明",
            sourceTag = GraphSourceTag.DRAFT_MANUAL,
        )
        val context = GraphAuditContext(
            factGraph = GraphDocument(
                nodes = listOf(staleFactNode),
                edges = listOf(
                    GraphEdge(
                        id = "edge:stale-loop",
                        type = EdgeType.CALL,
                        fromNodeId = staleFactNode.id,
                        toNodeId = staleFactNode.id,
                        sourceTag = GraphSourceTag.FACT,
                    ),
                ),
            ),
            editableGraph = GraphDocument(
                nodes = listOf(currentDraftNode),
                edges = emptyList(),
            ),
            selectedNodeIds = emptyList(),
        )

        val scopeNodes = GraphAuditScopeResolver.resolveScopeNodes(context)
        val scopeEdges = GraphAuditScopeResolver.resolveScopeEdges(context, scopeNodes)

        assertEquals(listOf(currentDraftNode.id), scopeNodes.map(GraphNode::id))
        assertEquals(emptyList(), scopeEdges.map(GraphEdge::id))
    }

    @Test
    fun selectedScopeIncludesDirectNeighborsButKeepsReturnedEdgesClosed() {
        val selectedNode = GraphNode(
            id = "method:submit-order",
            type = NodeType.METHOD,
            title = "OrderController.submit",
            sourceTag = GraphSourceTag.FACT,
        )
        val directNeighbor = GraphNode(
            id = "method:submit-service",
            type = NodeType.METHOD,
            title = "OrderService.submit",
            sourceTag = GraphSourceTag.FACT,
        )
        val manualNote = GraphNode(
            id = "doc:manual-note",
            type = NodeType.DOC_PAGE,
            title = "人工核查说明",
            sourceTag = GraphSourceTag.DRAFT_MANUAL,
        )
        val secondHopNode = GraphNode(
            id = "method:compensate",
            type = NodeType.METHOD,
            title = "OrderService.compensate",
            sourceTag = GraphSourceTag.DRAFT_MANUAL,
        )
        val context = GraphAuditContext(
            factGraph = GraphDocument(nodes = listOf(selectedNode, directNeighbor)),
            editableGraph = GraphDocument(
                nodes = listOf(selectedNode, directNeighbor, manualNote, secondHopNode),
                edges = listOf(
                    GraphEdge(
                        id = "edge:selected->neighbor",
                        type = EdgeType.CALL,
                        fromNodeId = selectedNode.id,
                        toNodeId = directNeighbor.id,
                        sourceTag = GraphSourceTag.FACT,
                    ),
                    GraphEdge(
                        id = "edge:selected->manual-note",
                        type = EdgeType.LINKS_DOC,
                        fromNodeId = selectedNode.id,
                        toNodeId = manualNote.id,
                        sourceTag = GraphSourceTag.DRAFT_MANUAL,
                    ),
                    GraphEdge(
                        id = "edge:neighbor->second-hop",
                        type = EdgeType.CALL,
                        fromNodeId = directNeighbor.id,
                        toNodeId = secondHopNode.id,
                        sourceTag = GraphSourceTag.DRAFT_MANUAL,
                    ),
                ),
            ),
            selectedNodeIds = listOf(selectedNode.id),
        )

        val scopeNodes = GraphAuditScopeResolver.resolveScopeNodes(context)
        val scopeEdges = GraphAuditScopeResolver.resolveScopeEdges(context, scopeNodes)

        assertEquals(
            setOf(selectedNode.id, directNeighbor.id, manualNote.id),
            scopeNodes.map(GraphNode::id).toSet(),
        )
        assertEquals(
            setOf("edge:selected->neighbor", "edge:selected->manual-note"),
            scopeEdges.map(GraphEdge::id).toSet(),
        )
    }
}
