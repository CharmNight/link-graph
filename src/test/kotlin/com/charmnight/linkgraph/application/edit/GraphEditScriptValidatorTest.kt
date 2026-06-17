package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditRequestSource
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphEditScriptValidatorTest {
    private val validator = GraphEditScriptValidator()

    @Test
    fun rejectsEmptyOperations() {
        val issues = validator.validate(GraphDocument(), request(operations = emptyList()))

        assertEquals(GraphEditIssueCode.EMPTY_OPERATIONS, issues.single().code)
        assertEquals(null, issues.single().operationIndex)
        assertEquals(false, issues.single().retryable)
    }

    @Test
    fun rejectsDuplicateNodeIdsInSameRequest() {
        val issues = validator.validate(
            GraphDocument(),
            request(
                operations = listOf(
                    GraphEditOperation.UpsertNode(node("node-a", "A")),
                    GraphEditOperation.UpsertNode(node("node-a", "A2")),
                ),
            ),
        )

        assertEquals(GraphEditIssueCode.DUPLICATE_NODE_ID, issues.single().code)
        assertEquals(1, issues.single().operationIndex)
        assertEquals("node-a", issues.single().targetId)
    }

    @Test
    fun rejectsDuplicateEdgeIdsInSameRequest() {
        val issues = validator.validate(
            GraphDocument(nodes = listOf(node("node-a"), node("node-b"))),
            request(
                operations = listOf(
                    GraphEditOperation.UpsertEdge(edge("edge-ab", "node-a", "node-b")),
                    GraphEditOperation.UpsertEdge(edge("edge-ab", "node-a", "node-b")),
                ),
            ),
        )

        assertEquals(GraphEditIssueCode.DUPLICATE_EDGE_ID, issues.single().code)
        assertEquals(1, issues.single().operationIndex)
        assertEquals("edge-ab", issues.single().targetId)
    }

    @Test
    fun rejectsMissingNodeAndMissingEdgeRemovals() {
        val issues = validator.validate(
            GraphDocument(nodes = listOf(node("node-a"))),
            request(
                operations = listOf(
                    GraphEditOperation.RemoveNode("node-missing"),
                    GraphEditOperation.RemoveEdge("edge-missing"),
                ),
            ),
        )

        assertEquals(
            listOf(GraphEditIssueCode.MISSING_NODE, GraphEditIssueCode.MISSING_EDGE),
            issues.map { it.code },
        )
        assertEquals(listOf("node-missing", "edge-missing"), issues.map { it.targetId })
    }

    @Test
    fun acceptsEdgeEndpointCreatedEarlierInSameRequest() {
        val issues = validator.validate(
            GraphDocument(nodes = listOf(node("node-a"))),
            request(
                operations = listOf(
                    GraphEditOperation.UpsertNode(node("node-b")),
                    GraphEditOperation.UpsertEdge(edge("edge-ab", "node-a", "node-b")),
                ),
            ),
        )

        assertTrue(issues.isEmpty(), issues.toString())
    }

    @Test
    fun rejectsInvalidEdgeEndpoint() {
        val issues = validator.validate(
            GraphDocument(nodes = listOf(node("node-a"))),
            request(operations = listOf(GraphEditOperation.UpsertEdge(edge("edge-ab", "node-a", "node-missing")))),
        )

        assertEquals(GraphEditIssueCode.INVALID_EDGE_ENDPOINT, issues.single().code)
        assertEquals(0, issues.single().operationIndex)
        assertEquals("edge-ab", issues.single().targetId)
    }

    private fun request(operations: List<GraphEditOperation>) =
        GraphEditRequest(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 1,
            operations = operations,
            source = GraphEditRequestSource.FRONTEND,
        )

    private fun node(id: String, title: String = id): GraphNode =
        GraphNode(id = id, type = NodeType.METHOD, title = title)

    private fun edge(id: String, from: String, to: String): GraphEdge =
        GraphEdge(id = id, type = EdgeType.CALL, fromNodeId = from, toNodeId = to)
}
