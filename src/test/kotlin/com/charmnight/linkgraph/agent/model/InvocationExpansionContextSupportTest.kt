package com.charmnight.linkgraph.agent.model

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvocationExpansionContextSupportTest {
    @Test
    fun activePathWithOnlyNestedLeafKeepsParentExpansionAsFullEvidence() {
        val parentExpansionId = "invocation:parent"
        val childExpansionId = "invocation:child"
        val caller = node("method:caller", NodeType.METHOD)
        val outerInvocation = node(
            id = "invoke:outer",
            type = NodeType.FLOW_ACTION,
            metadata = mapOf("flow.kind" to "INVOCATION"),
        )
        val parentMethod = node(
            id = "method:parent",
            type = NodeType.METHOD,
            metadata = expansionMetadata(parentExpansionId, outerInvocation.id),
        )
        val innerInvocation = node(
            id = "invoke:inner",
            type = NodeType.FLOW_ACTION,
            metadata = expansionMetadata(parentExpansionId, outerInvocation.id) +
                mapOf("flow.kind" to "INVOCATION"),
        )
        val childMethod = node(
            id = "method:child",
            type = NodeType.METHOD,
            metadata = expansionMetadata(childExpansionId, innerInvocation.id),
        )
        val childAction = node(
            id = "action:child",
            type = NodeType.FLOW_ACTION,
            metadata = expansionMetadata(childExpansionId, innerInvocation.id),
        )
        val visibleGraph = GraphDocument(
            nodes = listOf(caller, outerInvocation),
            edges = listOf(edge("control:caller-to-outer", EdgeType.CONTROL_FLOW, caller.id, outerInvocation.id)),
        )
        val fullGraph = GraphDocument(
            nodes = listOf(caller, outerInvocation, parentMethod, innerInvocation, childMethod, childAction),
            edges = visibleGraph.edges + listOf(
                edge(
                    id = "call:outer-to-parent",
                    type = EdgeType.CALL,
                    from = outerInvocation.id,
                    to = parentMethod.id,
                    metadata = expansionMetadata(parentExpansionId, outerInvocation.id),
                ),
                edge(
                    id = "control:parent-to-inner",
                    type = EdgeType.CONTROL_FLOW,
                    from = parentMethod.id,
                    to = innerInvocation.id,
                    metadata = expansionMetadata(parentExpansionId, outerInvocation.id),
                ),
                edge(
                    id = "call:inner-to-child",
                    type = EdgeType.CALL,
                    from = innerInvocation.id,
                    to = childMethod.id,
                    metadata = expansionMetadata(childExpansionId, innerInvocation.id),
                ),
                edge(
                    id = "control:child-to-action",
                    type = EdgeType.CONTROL_FLOW,
                    from = childMethod.id,
                    to = childAction.id,
                    metadata = expansionMetadata(childExpansionId, innerInvocation.id),
                ),
            ),
        )

        val scopedGraph = buildInvocationExpansionActiveChainScope(
            visibleGraph = visibleGraph,
            fullGraph = fullGraph,
            sceneState = InvocationExpansionSceneState(
                activeExpansionId = childExpansionId,
                activeExpansionPath = listOf(childExpansionId),
            ),
            anchorNodeId = caller.id,
        )

        val result = requireNotNull(scopedGraph)
        assertEquals(listOf(parentExpansionId, childExpansionId), result.context.activeExpansionPath)
        assertEquals(listOf(parentExpansionId, childExpansionId), result.context.fullExpansionIds)
        assertTrue(result.graph.nodes.any { node -> node.id == parentMethod.id })
        assertTrue(result.graph.nodes.any { node -> node.id == innerInvocation.id })
        assertTrue(result.graph.nodes.any { node -> node.id == childAction.id })
    }

    private fun node(
        id: String,
        type: NodeType,
        metadata: Map<String, String> = emptyMap(),
    ): GraphNode =
        GraphNode(
            id = id,
            type = type,
            title = id,
            metadata = metadata,
        )

    private fun edge(
        id: String,
        type: EdgeType,
        from: String,
        to: String,
        metadata: Map<String, String> = emptyMap(),
    ): GraphEdge =
        GraphEdge(
            id = id,
            type = type,
            fromNodeId = from,
            toNodeId = to,
            metadata = metadata,
        )

    private fun expansionMetadata(
        expansionId: String,
        sourceInvocationNodeId: String,
    ): Map<String, String> =
        mapOf(
            "linkGraph.expansion.id" to expansionId,
            "linkGraph.expansion.sourceInvocationNodeId" to sourceInvocationNodeId,
        )
}
