package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkGraphRenderTraceTest {
    @Test
    fun traceDoesNotEvaluateMessageWhenDisabled() {
        var evaluated = false
        var loggedMessage: String? = null

        LinkGraphRenderTrace.trace(
            enabled = false,
            log = { loggedMessage = it },
        ) {
            evaluated = true
            "expensive"
        }

        assertFalse(evaluated)
        assertEquals(null, loggedMessage)
    }

    @Test
    fun traceFormatsPipelineStageDurationAndGraphSizes() {
        var loggedMessage: String? = null
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(id = "method:a", type = NodeType.METHOD, title = "A"),
                GraphNode(id = "method:b", type = NodeType.METHOD, title = "B"),
            ),
            edges = listOf(
                GraphEdge(id = "edge:a-b", type = EdgeType.CALL, fromNodeId = "method:a", toNodeId = "method:b"),
            ),
        )

        LinkGraphRenderTrace.stage(
            enabled = true,
            log = { loggedMessage = it },
            stage = "transport.payload.current",
            startedAtNanos = 1_000_000L,
            finishedAtNanos = 3_500_000L,
        ) {
            listOf("workspace=${LinkGraphRenderTrace.graphSummary(graph)}", "scriptChars=512")
        }

        val message = requireNotNull(loggedMessage)
        assertTrue(message.startsWith("渲染链路 trace: stage=transport.payload.current, durationMs=2.50"))
        assertTrue(message.contains("workspace=nodes=2, edges=1"))
        assertTrue(message.contains("scriptChars=512"))
    }
}
