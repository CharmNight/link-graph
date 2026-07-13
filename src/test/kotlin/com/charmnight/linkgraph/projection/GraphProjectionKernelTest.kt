package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphProjectionKernelTest {
    @Test
    fun directlyConstructedPolicyCannotBypassProjectionBudget() {
        val graph = graphWithNodes(501)

        val result = GraphProjectionKernel().projectWindow(
            graph = graph,
            policy = GraphProjectionPolicy(
                maxVisibleNodes = Int.MAX_VALUE,
                maxVisibleEdges = Int.MAX_VALUE,
            ),
        )

        assertEquals(500, result.visibleGraph.nodes.size)
        assertEquals(1, result.hiddenNodeCount)
        assertTrue(result.truncated)
    }

    @Test
    fun directlyConstructedKernelCannotBypassInteractiveProjectionBudget() {
        val result = GraphProjectionKernel(
            maxVisibleNodes = Int.MAX_VALUE,
            maxVisibleEdges = Int.MAX_VALUE,
        ).projectInteractive(graphWithNodes(501))

        assertTrue(result.visibleGraph.nodes.size <= 500)
        assertTrue(result.hiddenNodeCount > 0)
        assertTrue(result.truncated)
    }

    private fun graphWithNodes(count: Int): GraphDocument =
        GraphDocument(
            nodes = List(count) { index ->
                GraphNode(
                    id = "node-$index",
                    type = NodeType.METHOD,
                    title = "Node $index",
                )
            },
        )
}
