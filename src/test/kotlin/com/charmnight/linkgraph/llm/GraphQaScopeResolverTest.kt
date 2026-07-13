package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphProvenance
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphQaScopeResolverTest {
    @Test
    fun wholeChainScopePrefersCurrentDraftGraphOverStaleFactGraph() {
        val staleFactNode = GraphNode(
            id = "method:stale-fact",
            type = NodeType.METHOD,
            title = "FallbackGuard.handle",
            provenance = GraphProvenance.CODE_ANALYSIS,
        )
        val currentDraftNode = GraphNode(
            id = "doc:manual-note",
            type = NodeType.DOC_PAGE,
            title = "人工补充说明",
            provenance = GraphProvenance.USER_DRAFT,
        )
        val context = GraphQaContext(
            factGraph = GraphDocument(
                nodes = listOf(staleFactNode),
                edges = listOf(
                    GraphEdge(
                        id = "edge:stale-loop",
                        type = EdgeType.CALL,
                        fromNodeId = staleFactNode.id,
                        toNodeId = staleFactNode.id,
                        provenance = GraphProvenance.CODE_ANALYSIS,
                    ),
                ),
            ),
            editableGraph = GraphDocument(
                nodes = listOf(currentDraftNode),
                edges = emptyList(),
            ),
            selectedNodeIds = emptyList(),
        )

        val scopeNodes = GraphQaScopeResolver.resolveScopeNodes(context)
        val scopeEdges = GraphQaScopeResolver.resolveScopeEdges(context, scopeNodes)

        assertEquals(listOf(currentDraftNode.id), scopeNodes.map(GraphNode::id))
        assertEquals(emptyList(), scopeEdges.map(GraphEdge::id))
    }

    @Test
    fun selectedScopeIncludesDirectNeighborsButKeepsReturnedEdgesClosed() {
        val selectedNode = GraphNode(
            id = "method:submit-order",
            type = NodeType.METHOD,
            title = "OrderController.submit",
            provenance = GraphProvenance.CODE_ANALYSIS,
        )
        val directNeighbor = GraphNode(
            id = "method:submit-service",
            type = NodeType.METHOD,
            title = "OrderService.submit",
            provenance = GraphProvenance.CODE_ANALYSIS,
        )
        val manualNote = GraphNode(
            id = "doc:manual-note",
            type = NodeType.DOC_PAGE,
            title = "人工核查说明",
            provenance = GraphProvenance.USER_DRAFT,
        )
        val secondHopNode = GraphNode(
            id = "method:compensate",
            type = NodeType.METHOD,
            title = "OrderService.compensate",
            provenance = GraphProvenance.USER_DRAFT,
        )
        val context = GraphQaContext(
            factGraph = GraphDocument(nodes = listOf(selectedNode, directNeighbor)),
            editableGraph = GraphDocument(
                nodes = listOf(selectedNode, directNeighbor, manualNote, secondHopNode),
                edges = listOf(
                    GraphEdge(
                        id = "edge:selected->neighbor",
                        type = EdgeType.CALL,
                        fromNodeId = selectedNode.id,
                        toNodeId = directNeighbor.id,
                        provenance = GraphProvenance.CODE_ANALYSIS,
                    ),
                    GraphEdge(
                        id = "edge:selected->manual-note",
                        type = EdgeType.LINKS_DOC,
                        fromNodeId = selectedNode.id,
                        toNodeId = manualNote.id,
                        provenance = GraphProvenance.USER_DRAFT,
                    ),
                    GraphEdge(
                        id = "edge:neighbor->second-hop",
                        type = EdgeType.CALL,
                        fromNodeId = directNeighbor.id,
                        toNodeId = secondHopNode.id,
                        provenance = GraphProvenance.USER_DRAFT,
                    ),
                ),
            ),
            selectedNodeIds = listOf(selectedNode.id),
        )

        val scopeNodes = GraphQaScopeResolver.resolveScopeNodes(context)
        val scopeEdges = GraphQaScopeResolver.resolveScopeEdges(context, scopeNodes)

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
