package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditRequestSource
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphEditApplierTest {
    private val applier = GraphEditApplier()

    @Test
    fun updatesExistingNodesWithoutChangingNodePositionAndAppendsNewNodesInOperationOrder() {
        val oldA = node("node-a", "A")
        val oldB = node("node-b", "B")
        val applied = applier.apply(
            snapshot = WorkflowEditorSnapshot(workspaceGraph = GraphDocument(nodes = listOf(oldA, oldB))),
            request = request(
                GraphEditOperation.UpsertNode(oldB.copy(title = "B2")),
                GraphEditOperation.UpsertNode(node("node-c", "C")),
                GraphEditOperation.UpsertNode(node("node-aa", "AA")),
            ),
            resolution = GraphEditResolution.identity(),
        )

        assertEquals(listOf("node-a", "node-b", "node-c", "node-aa"), applied.nodes.map { it.id })
        assertEquals("B2", applied.nodes.first { it.id == "node-b" }.title)
    }

    @Test
    fun updatesExistingEdgesWithoutChangingEdgePositionAndAppendsNewEdgesInOperationOrder() {
        val nodeA = node("node-a")
        val nodeB = node("node-b")
        val nodeC = node("node-c")
        val edgeAB = edge("edge-ab", "node-a", "node-b", label = "old")
        val edgeBC = edge("edge-bc", "node-b", "node-c", label = "old")
        val applied = applier.apply(
            snapshot = WorkflowEditorSnapshot(
                workspaceGraph = GraphDocument(nodes = listOf(nodeA, nodeB, nodeC), edges = listOf(edgeAB, edgeBC)),
            ),
            request = request(
                GraphEditOperation.UpsertEdge(edgeBC.copy(label = "updated")),
                GraphEditOperation.UpsertEdge(edge("edge-ac", "node-a", "node-c")),
                GraphEditOperation.UpsertEdge(edge("edge-aa", "node-a", "node-a")),
            ),
            resolution = GraphEditResolution.identity(),
        )

        assertEquals(listOf("edge-ab", "edge-bc", "edge-ac", "edge-aa"), applied.edges.map { it.id })
        assertEquals("updated", applied.edges.first { it.id == "edge-bc" }.label)
    }

    @Test
    fun deletingNodeCascadesIncidentEdgesAndPreservesPatchField() {
        val patch = com.charmnight.linkgraph.model.GraphPatch(operations = emptyList())
        val applied = applier.apply(
            snapshot = WorkflowEditorSnapshot(
                workspaceGraph = GraphDocument(
                    nodes = listOf(node("node-a"), node("node-b"), node("node-c")),
                    edges = listOf(
                        edge("edge-ab", "node-a", "node-b"),
                        edge("edge-bc", "node-b", "node-c"),
                        edge("edge-ac", "node-a", "node-c"),
                    ),
                    patch = patch,
                ),
            ),
            request = request(GraphEditOperation.RemoveNode("node-b")),
            resolution = GraphEditResolution.identity(),
        )

        assertEquals(listOf("node-a", "node-c"), applied.nodes.map { it.id })
        assertEquals(listOf("edge-ac"), applied.edges.map { it.id })
        assertEquals(patch, applied.patch)
    }

    @Test
    fun stripsNavigationFieldsFromNewNodes() {
        val applied = applier.apply(
            snapshot = WorkflowEditorSnapshot(),
            request = request(
                GraphEditOperation.UpsertNode(
                    node("node-new").copy(
                        location = "/tmp/escape.java:1:1",
                        signature = "java.lang.System.exit(int):void",
                    ),
                ),
            ),
            resolution = GraphEditResolution.identity(),
        )

        val newNode = applied.nodes.single()
        assertNull(newNode.location)
        assertNull(newNode.signature)
    }

    private fun request(vararg operations: GraphEditOperation) =
        GraphEditRequest(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 1,
            operations = operations.toList(),
            source = GraphEditRequestSource.FRONTEND,
        )

    private fun node(id: String, title: String = id): GraphNode =
        GraphNode(id = id, type = NodeType.METHOD, title = title)

    private fun edge(id: String, from: String, to: String, label: String? = null): GraphEdge =
        GraphEdge(id = id, type = EdgeType.CALL, fromNodeId = from, toNodeId = to, label = label)
}
