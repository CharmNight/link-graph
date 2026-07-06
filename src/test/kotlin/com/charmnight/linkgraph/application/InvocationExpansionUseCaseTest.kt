package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.usecase.InvocationExpansionUseCase
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvocationExpansionUseCaseTest {
    private var nextId = 1
    private val useCase = InvocationExpansionUseCase(
        idGenerator = { "expansion-${nextId++}" },
        clock = { "2026-05-13T00:00:00Z" },
    )

    @Test
    fun `rejects non invocation node`() {
        val node = GraphNode(
            id = "action:plain",
            type = NodeType.FLOW_ACTION,
            title = "validate input",
            metadata = mapOf("flow.kind" to "ACTION"),
        )

        val result = useCase.validateInvocationNode(node)

        assertEquals(InvocationExpansionUseCase.ValidationResult.NOT_INVOCATION, result)
    }

    @Test
    fun `rejects invocation node without signature`() {
        val node = invocationNode(signature = null)

        val result = useCase.validateInvocationNode(node)

        assertEquals(InvocationExpansionUseCase.ValidationResult.MISSING_SIGNATURE, result)
    }

    @Test
    fun `accepts invocation node with signature`() {
        val node = invocationNode(signature = CREATE_INFO_SIGNATURE)

        val result = useCase.validateInvocationNode(node)

        assertEquals(InvocationExpansionUseCase.ValidationResult.READY, result)
    }

    @Test
    fun `merges target graph as expansion batch and connects invocation to target entry`() {
        val workspace = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:caller",
                    type = NodeType.METHOD,
                    title = "Caller.run",
                    signature = "com.example.Caller.run():void",
                ),
                invocationNode(signature = CREATE_INFO_SIGNATURE),
            ),
            edges = listOf(
                GraphEdge(
                    id = "control:caller-to-invoke",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:caller",
                    toNodeId = "invoke:create-info",
                ),
            ),
        )
        val targetGraph = GraphDocument(
            nodes = listOf(
                createInfoMethodNode(),
                GraphNode(
                    id = "action:save-info",
                    type = NodeType.FLOW_ACTION,
                    title = "saveInfo()",
                    metadata = mapOf("flow.kind" to "ACTION"),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "control:create-to-save",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:create-info",
                    toNodeId = "action:save-info",
                ),
            ),
        )

        val result = useCase.mergeExpansion(
            workspace = workspace,
            sourceInvocationNode = workspace.nodes.single { it.id == "invoke:create-info" },
            targetGraph = targetGraph,
            targetEntryNodeId = "method:create-info",
            targetSignature = CREATE_INFO_SIGNATURE,
        )

        val expansionId = result.expansionId
        assertTrue(result.graph.nodes.any { it.id == "method:create-info" })
        assertTrue(result.graph.nodes.any { it.id == "action:save-info" })
        assertEquals(expansionId, result.graph.nodes.single { it.id == "method:create-info" }.metadata[InvocationExpansionUseCase.EXPANSION_ID])
        assertEquals(expansionId, result.graph.nodes.single { it.id == "action:save-info" }.metadata[InvocationExpansionUseCase.EXPANSION_ID])
        assertEquals(expansionId, result.graph.edges.single { it.id == "control:create-to-save" }.metadata[InvocationExpansionUseCase.EXPANSION_ID])
        assertTrue(result.graph.edges.any { edge ->
            edge.fromNodeId == "invoke:create-info" &&
                edge.toNodeId == "method:create-info" &&
                edge.metadata[InvocationExpansionUseCase.EXPANSION_ID] == expansionId
        })
    }

    @Test
    fun `does not mark preexisting reused target entry with expansion id`() {
        val existingTarget = createInfoMethodNode()
        val workspace = GraphDocument(
            nodes = listOf(
                invocationNode(signature = CREATE_INFO_SIGNATURE),
                existingTarget,
            ),
            edges = emptyList(),
        )
        val targetGraph = GraphDocument(
            nodes = listOf(
                existingTarget,
                GraphNode(
                    id = "action:save-info",
                    type = NodeType.FLOW_ACTION,
                    title = "saveInfo()",
                    metadata = mapOf("flow.kind" to "ACTION"),
                ),
            ),
            edges = emptyList(),
        )

        val result = useCase.mergeExpansion(
            workspace = workspace,
            sourceInvocationNode = workspace.nodes.single { it.id == "invoke:create-info" },
            targetGraph = targetGraph,
            targetEntryNodeId = "method:create-info",
            targetSignature = CREATE_INFO_SIGNATURE,
        )

        assertFalse(result.graph.nodes.single { it.id == "method:create-info" }.metadata.containsKey(InvocationExpansionUseCase.EXPANSION_ID))
        assertEquals(result.expansionId, result.graph.nodes.single { it.id == "action:save-info" }.metadata[InvocationExpansionUseCase.EXPANSION_ID])
    }

    @Test
    fun `removes expansion nodes even after manual edits but keeps reused existing nodes`() {
        val expansionId = "expansion-1"
        val graph = GraphDocument(
            nodes = listOf(
                invocationNode(signature = CREATE_INFO_SIGNATURE),
                createInfoMethodNode(),
                GraphNode(
                    id = "action:save-info",
                    type = NodeType.FLOW_ACTION,
                    title = "saveInfo() changed by user",
                    metadata = mapOf(
                        InvocationExpansionUseCase.EXPANSION_ID to expansionId,
                        "linkGraph.manual" to "true",
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "edge:expanded-connect",
                    type = EdgeType.CALL,
                    fromNodeId = "invoke:create-info",
                    toNodeId = "method:create-info",
                    metadata = mapOf(InvocationExpansionUseCase.EXPANSION_ID to expansionId),
                ),
                GraphEdge(
                    id = "edge:expanded-internal",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:create-info",
                    toNodeId = "action:save-info",
                    metadata = mapOf(InvocationExpansionUseCase.EXPANSION_ID to expansionId),
                ),
            ),
        )

        val result = useCase.removeExpansion(graph, expansionId)

        assertTrue(result.removed)
        assertTrue(result.graph.nodes.any { it.id == "method:create-info" })
        assertFalse(result.graph.nodes.any { it.id == "action:save-info" })
        assertFalse(result.graph.edges.any { it.id == "edge:expanded-connect" })
        assertFalse(result.graph.edges.any { it.id == "edge:expanded-internal" })
    }

    @Test
    fun `remove expansion cascades through nested child expansion batches`() {
        val parentExpansionId = "invocation:parent"
        val childExpansionId = "invocation:child"
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(id = "invoke:parent", type = NodeType.FLOW_ACTION, title = "call parent", metadata = mapOf("flow.kind" to "INVOCATION")),
                GraphNode(
                    id = "method:parent",
                    type = NodeType.METHOD,
                    title = "Parent.run",
                    metadata = expansionMetadata(parentExpansionId, "invoke:parent", "method:parent"),
                ),
                GraphNode(
                    id = "invoke:child",
                    type = NodeType.FLOW_ACTION,
                    title = "call child",
                    metadata = expansionMetadata(parentExpansionId, "invoke:parent", "method:parent") + ("flow.kind" to "INVOCATION"),
                ),
                GraphNode(
                    id = "method:child",
                    type = NodeType.METHOD,
                    title = "Child.run",
                    metadata = expansionMetadata(childExpansionId, "invoke:child", "method:child"),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "call:parent",
                    type = EdgeType.CALL,
                    fromNodeId = "invoke:parent",
                    toNodeId = "method:parent",
                    metadata = expansionMetadata(parentExpansionId, "invoke:parent", "method:parent"),
                ),
                GraphEdge(
                    id = "parent-to-child-invoke",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:parent",
                    toNodeId = "invoke:child",
                    metadata = expansionMetadata(parentExpansionId, "invoke:parent", "method:parent"),
                ),
                GraphEdge(
                    id = "call:child",
                    type = EdgeType.CALL,
                    fromNodeId = "invoke:child",
                    toNodeId = "method:child",
                    metadata = expansionMetadata(childExpansionId, "invoke:child", "method:child"),
                ),
            ),
        )

        val result = useCase.removeExpansion(graph, parentExpansionId)

        assertTrue(result.removed)
        assertEquals(listOf("invoke:parent"), result.graph.nodes.map(GraphNode::id))
        assertTrue(result.graph.edges.isEmpty())
    }

    @Test
    fun `remove expansion keeps borrowed root used by nested expansion`() {
        val parentExpansionId = "invocation:parent"
        val childExpansionId = "invocation:child"
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(id = "invoke:parent", type = NodeType.FLOW_ACTION, title = "call parent", metadata = mapOf("flow.kind" to "INVOCATION")),
                GraphNode(id = "method:parent", type = NodeType.METHOD, title = "Parent.run"),
                GraphNode(
                    id = "invoke:child",
                    type = NodeType.FLOW_ACTION,
                    title = "call child",
                    metadata = expansionMetadata(parentExpansionId, "invoke:parent", "method:parent") + ("flow.kind" to "INVOCATION"),
                ),
                GraphNode(
                    id = "method:child",
                    type = NodeType.METHOD,
                    title = "Child.run",
                    metadata = expansionMetadata(childExpansionId, "invoke:child", "method:child"),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "call:parent",
                    type = EdgeType.CALL,
                    fromNodeId = "invoke:parent",
                    toNodeId = "method:parent",
                    metadata = expansionMetadata(parentExpansionId, "invoke:parent", "method:parent"),
                ),
                GraphEdge(
                    id = "parent-to-child-invoke",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = "method:parent",
                    toNodeId = "invoke:child",
                    metadata = expansionMetadata(parentExpansionId, "invoke:parent", "method:parent"),
                ),
                GraphEdge(
                    id = "call:child",
                    type = EdgeType.CALL,
                    fromNodeId = "invoke:child",
                    toNodeId = "method:child",
                    metadata = expansionMetadata(childExpansionId, "invoke:child", "method:child"),
                ),
            ),
        )

        val result = useCase.removeExpansion(graph, parentExpansionId)

        assertTrue(result.removed)
        assertEquals(listOf("invoke:parent", "method:parent"), result.graph.nodes.map(GraphNode::id).sorted())
        assertTrue(result.graph.edges.isEmpty())
    }

    private fun invocationNode(signature: String?): GraphNode =
        GraphNode(
            id = "invoke:create-info",
            type = NodeType.FLOW_ACTION,
            title = "systemService.createInfo()",
            signature = signature,
            metadata = mapOf("flow.kind" to "INVOCATION"),
        )

    private fun createInfoMethodNode(): GraphNode =
        GraphNode(
            id = "method:create-info",
            type = NodeType.METHOD,
            title = "SystemService.createInfo",
            signature = CREATE_INFO_SIGNATURE,
        )

    private fun expansionMetadata(
        expansionId: String,
        sourceInvocationNodeId: String,
        rootNodeId: String,
    ): Map<String, String> = mapOf(
        InvocationExpansionUseCase.EXPANSION_ID to expansionId,
        InvocationExpansionUseCase.EXPANSION_SOURCE_INVOCATION_NODE_ID to sourceInvocationNodeId,
        InvocationExpansionUseCase.EXPANSION_ROOT_NODE_ID to rootNodeId,
    )

    private companion object {
        const val CREATE_INFO_SIGNATURE = "com.example.SystemService.createInfo():void"
    }
}
