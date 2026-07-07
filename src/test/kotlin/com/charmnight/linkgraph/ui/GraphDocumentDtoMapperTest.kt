package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphDocumentDtoMapperTest {
    @Test
    fun graphDocumentDtoIncludesAuthoritativeInvocationExpansionRegistry() {
        val document = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "invoke:load",
                    type = NodeType.FLOW_ACTION,
                    title = "load()",
                    metadata = mapOf("flow.kind" to "INVOCATION"),
                ),
                GraphNode(
                    id = "method:load",
                    type = NodeType.METHOD,
                    title = "load",
                    metadata = mapOf(
                        "linkGraph.expansion.id" to "invocation:load",
                        "linkGraph.expansion.sourceInvocationNodeId" to "invoke:load",
                        "linkGraph.expansion.rootNodeId" to "method:load",
                        "linkGraph.expansion.targetSignature" to "com.example.Loader.load():void",
                        "linkGraph.expansion.createdAt" to "2026-07-01T00:00:00Z",
                    ),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "call:load",
                    type = EdgeType.CALL,
                    fromNodeId = "invoke:load",
                    toNodeId = "method:load",
                    metadata = mapOf("linkGraph.expansion.id" to "invocation:load"),
                ),
            ),
        )

        val dto = graphDocumentToDto(
            document = document,
            includeFullContent = true,
            maxSecondaryNodes = 50,
            maxSecondaryEdges = 50,
        )

        assertEquals(
            listOf("invocation:load"),
            dto.invocationExpansionRegistry.entries.map { entry -> entry.expansionId },
        )
        val entry = dto.invocationExpansionRegistry.entries.single()
        assertEquals("invoke:load", entry.sourceInvocationNodeId)
        assertEquals("method:load", entry.rootNodeId)
        assertEquals("com.example.Loader.load():void", entry.targetSignature)
        assertEquals("2026-07-01T00:00:00Z", entry.createdAt)
        assertEquals(1, entry.depth)
        assertEquals(listOf("method:load"), entry.ownedNodeIds)
        assertEquals(listOf("call:load"), entry.callEdgeIds)
    }

    @Test
    fun invocationExpansionRegistryBreaksCyclicParentsBeforeSerialization() {
        val document = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:a",
                    type = NodeType.METHOD,
                    title = "A.run",
                    metadata = expansionMetadata("invocation:a", "invoke:a", "method:a"),
                ),
                GraphNode(
                    id = "invoke:b",
                    type = NodeType.FLOW_ACTION,
                    title = "b()",
                    metadata = expansionMetadata("invocation:a", "invoke:a", "method:a") + ("flow.kind" to "INVOCATION"),
                ),
                GraphNode(
                    id = "method:b",
                    type = NodeType.METHOD,
                    title = "B.run",
                    metadata = expansionMetadata("invocation:b", "invoke:b", "method:b"),
                ),
                GraphNode(
                    id = "invoke:a",
                    type = NodeType.FLOW_ACTION,
                    title = "a()",
                    metadata = expansionMetadata("invocation:b", "invoke:b", "method:b") + ("flow.kind" to "INVOCATION"),
                ),
            ),
            edges = emptyList(),
        )

        val dto = graphDocumentToDto(
            document = document,
            includeFullContent = true,
            maxSecondaryNodes = 50,
            maxSecondaryEdges = 50,
        )

        val entriesById = dto.invocationExpansionRegistry.entries.associateBy { entry -> entry.expansionId }
        assertEquals(setOf("invocation:a", "invocation:b"), entriesById.keys)
        entriesById.values.forEach { entry ->
            assertEquals(null, entry.parentExpansionId)
            assertEquals(1, entry.depth)
            assertEquals(emptyList(), entry.childExpansionIds)
            assertEquals(listOf("cycle-parent"), entry.warnings)
        }
    }

    private fun expansionMetadata(
        expansionId: String,
        sourceInvocationNodeId: String,
        rootNodeId: String,
    ): Map<String, String> = mapOf(
        "linkGraph.expansion.id" to expansionId,
        "linkGraph.expansion.sourceInvocationNodeId" to sourceInvocationNodeId,
        "linkGraph.expansion.rootNodeId" to rootNodeId,
    )
}
