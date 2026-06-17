package com.charmnight.linkgraph.application.edit

import com.charmnight.linkgraph.application.model.ApplicationGraphView
import com.charmnight.linkgraph.application.model.GraphEditCommandKind
import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditRequestSource
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.application.model.GraphProjectionNodeMapping
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphEditPermissionPolicyTest {
    private val policy = GraphEditPermissionPolicy()

    @Test
    fun allowsExactEditableProjectionUpdateAndResolvesCanonicalNodeId() {
        val decision = policy.evaluate(
            snapshot = snapshotWithFactProjection(
                GraphProjectionIndex(
                    nodeMappings = mapOf(
                        "projected-a" to GraphProjectionNodeMapping(
                            projectedNodeId = "projected-a",
                            mappingKind = GraphProjectionMappingKind.EXACT,
                            canonicalNodeIds = listOf("node-a"),
                            editableCommandKinds = setOf(GraphEditCommandKind.UPDATE_NODE),
                        ),
                    ),
                ),
            ),
            request = request(GraphEditOperation.UpsertNode(node("projected-a"))),
        )

        assertTrue(decision.issues.isEmpty(), decision.issues.toString())
        assertEquals("node-a", decision.resolution.nodeTargetId("projected-a"))
    }

    @Test
    fun rejectsReadonlyProjectionNodeWithStructuredIssue() {
        val decision = policy.evaluate(
            snapshot = snapshotWithFactProjection(
                GraphProjectionIndex(
                    nodeMappings = mapOf(
                        "readonly-a" to GraphProjectionNodeMapping(
                            projectedNodeId = "readonly-a",
                            mappingKind = GraphProjectionMappingKind.INDEXED_READONLY,
                            canonicalNodeIds = listOf("node-a"),
                            editableCommandKinds = emptySet(),
                        ),
                    ),
                ),
            ),
            request = request(GraphEditOperation.UpsertNode(node("readonly-a"))),
        )

        val issue = decision.issues.single()
        assertEquals(GraphEditIssueCode.READONLY_PROJECTION_NODE, issue.code)
        assertEquals(0, issue.operationIndex)
        assertEquals("readonly-a", issue.targetId)
    }

    @Test
    fun resolvesMergedAliasDeletionToAllCanonicalNodesWhenDeleteIsAllowed() {
        val decision = policy.evaluate(
            snapshot = snapshotWithFactProjection(
                GraphProjectionIndex(
                    nodeMappings = mapOf(
                        "merged" to GraphProjectionNodeMapping(
                            projectedNodeId = "merged",
                            mappingKind = GraphProjectionMappingKind.MERGED_ALIAS,
                            canonicalNodeIds = listOf("node-a", "node-b"),
                            editableCommandKinds = setOf(GraphEditCommandKind.DELETE_NODE),
                        ),
                    ),
                ),
            ),
            request = request(GraphEditOperation.RemoveNode("merged")),
        )

        assertTrue(decision.issues.isEmpty(), decision.issues.toString())
        assertEquals(setOf("node-a", "node-b"), decision.resolution.nodeRemovalIds("merged"))
    }

    @Test
    fun rejectsExistingEdgeUpsertWhenEdgeIsNotMappedInCurrentProjection() {
        val decision = policy.evaluate(
            snapshot = WorkflowEditorSnapshot(
                workspaceGraph = GraphDocument(
                    nodes = listOf(node("node-a"), node("node-b")),
                    edges = listOf(edge("edge-ab", "node-a", "node-b", "old")),
                ),
                factGraphView = ApplicationGraphView(
                    projectionIndex = GraphProjectionIndex(
                        nodeMappings = mapOf(
                            "node-a" to editableNodeMapping("node-a", GraphEditCommandKind.CONNECT_NODES),
                            "node-b" to editableNodeMapping("node-b", GraphEditCommandKind.CONNECT_NODES),
                        ),
                    ),
                ),
            ),
            request = request(
                GraphEditOperation.UpsertEdge(edge("edge-ab", "node-a", "node-b", "changed")),
            ),
        )

        val issue = decision.issues.single()
        assertEquals(GraphEditIssueCode.READONLY_PROJECTION_EDGE, issue.code)
        assertEquals("edge-ab", issue.targetId)
    }

    private fun snapshotWithFactProjection(index: GraphProjectionIndex) =
        WorkflowEditorSnapshot(
            workspaceGraph = GraphDocument(nodes = listOf(node("node-a"), node("node-b"))),
            factGraphView = ApplicationGraphView(projectionIndex = index),
        )

    private fun request(vararg operations: GraphEditOperation) =
        GraphEditRequest(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 1,
            operations = operations.toList(),
            source = GraphEditRequestSource.FRONTEND,
        )

    private fun node(id: String): GraphNode =
        GraphNode(id = id, type = NodeType.METHOD, title = id)

    private fun edge(id: String, from: String, to: String, label: String): GraphEdge =
        GraphEdge(id = id, type = EdgeType.CALL, fromNodeId = from, toNodeId = to, label = label)

    private fun editableNodeMapping(
        nodeId: String,
        vararg commands: GraphEditCommandKind,
    ): GraphProjectionNodeMapping =
        GraphProjectionNodeMapping(
            projectedNodeId = nodeId,
            mappingKind = GraphProjectionMappingKind.EXACT,
            canonicalNodeIds = listOf(nodeId),
            editableCommandKinds = commands.toSet(),
        )
}
