package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.model.ApplicationGraphView
import com.charmnight.linkgraph.application.model.GraphEditCommandKind
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditRequest
import com.charmnight.linkgraph.application.model.GraphEditRequestSource
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.application.model.GraphProjectionNodeMapping
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.usecase.WorkspaceGraphUseCase
import com.charmnight.linkgraph.application.usecase.WorkspaceGraphUseCaseResult
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkspaceGraphUseCaseTest {
    @Test
    fun rejectsGraphEditRequestWhenWorkspaceRevisionDoesNotMatch() {
        val snapshot = WorkflowEditorSnapshot(workspaceRevision = 7)
        val request = GraphEditRequest(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 6,
            operations = listOf(
                GraphEditOperation.UpsertNode(GraphNode(id = "node-new", type = NodeType.METHOD, title = "new")),
            ),
            source = GraphEditRequestSource.FRONTEND,
        )

        val result = useCase().applyGraphEditRequest(snapshot, request)

        val rejected = assertIs<WorkspaceGraphUseCaseResult.EditRejected>(result)
        assertEquals(7, rejected.rejection.currentWorkspaceRevision)
        assertEquals(GraphEditIssueCode.STALE_BASE_REVISION, rejected.rejection.issues.single().code)
        assertEquals(true, rejected.rejection.issues.single().retryable)
    }

    @Test
    fun appliesGraphEditRequestAsPureWorkspaceGraphResultAndPreservesModelOrder() {
        val snapshot = WorkflowEditorSnapshot(
            workspaceRevision = 4,
            snapshotRevision = 11,
            selectedMethodSignature = "com.example.Service.run():void",
            workspaceGraph = GraphDocument(
                nodes = listOf(GraphNode(id = "node-old", type = NodeType.METHOD, title = "old")),
            ),
            factGraphView = ApplicationGraphView(
                visibleGraph = GraphDocument(
                    nodes = listOf(GraphNode(id = "node-old", type = NodeType.METHOD, title = "old")),
                ),
            ),
        )
        val request = GraphEditRequest(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 4,
            operations = listOf(
                GraphEditOperation.UpsertNode(
                    GraphNode(
                        id = "node-new",
                        type = NodeType.METHOD,
                        title = "new",
                        location = "should-not-trust",
                        signature = "should-not-trust()",
                    ),
                ),
            ),
            source = GraphEditRequestSource.FRONTEND,
        )

        val result = useCase().applyGraphEditRequest(snapshot, request)

        val applied = assertIs<WorkspaceGraphUseCaseResult.EditApplied>(result)
        assertEquals(11, applied.expectedSnapshotRevision)
        assertEquals("com.example.Service.run():void", applied.selectedMethodSignature)
        assertEquals(listOf("node-old", "node-new"), applied.graph.nodes.map { it.id })
        assertEquals(null, applied.graph.nodes.first { it.id == "node-new" }.location)
        assertEquals(null, applied.graph.nodes.first { it.id == "node-new" }.signature)
        assertEquals(listOf("node-old"), snapshot.workspaceGraph.nodes.map { it.id })
        assertEquals(snapshot.workspaceGraph, applied.transaction.graphBeforeApply)
        assertEquals(applied.graph, applied.transaction.graphAfterApply)
        assertEquals(request, applied.transaction.request)
        assertEquals(4, applied.transaction.workspaceRevisionBefore)
        assertEquals(5, applied.transaction.workspaceRevisionAfter)
    }

    @Test
    fun rejectsEmptyOperationsWithStructuredValidatorIssue() {
        val snapshot = WorkflowEditorSnapshot(workspaceRevision = 4)
        val request = GraphEditRequest(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 4,
            operations = emptyList(),
            source = GraphEditRequestSource.FRONTEND,
        )

        val result = useCase().applyGraphEditRequest(snapshot, request)

        val rejected = assertIs<WorkspaceGraphUseCaseResult.EditRejected>(result)
        assertEquals(GraphEditIssueCode.EMPTY_OPERATIONS, rejected.rejection.issues.single().code)
        assertEquals(4, rejected.rejection.currentWorkspaceRevision)
    }

    @Test
    fun rejectsInvalidEdgeEndpointBeforeApplyingGraph() {
        val snapshot = WorkflowEditorSnapshot(
            workspaceRevision = 4,
            workspaceGraph = GraphDocument(
                nodes = listOf(GraphNode(id = "node-a", type = NodeType.METHOD, title = "A")),
            ),
        )
        val request = GraphEditRequest(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 4,
            operations = listOf(
                GraphEditOperation.UpsertEdge(
                    GraphEdge(
                        id = "edge-missing",
                        type = EdgeType.CALL,
                        fromNodeId = "node-a",
                        toNodeId = "node-missing",
                    ),
                ),
            ),
            source = GraphEditRequestSource.FRONTEND,
        )

        val result = useCase().applyGraphEditRequest(snapshot, request)

        val rejected = assertIs<WorkspaceGraphUseCaseResult.EditRejected>(result)
        val issue = rejected.rejection.issues.single()
        assertEquals(GraphEditIssueCode.INVALID_EDGE_ENDPOINT, issue.code)
        assertEquals(0, issue.operationIndex)
        assertEquals("edge-missing", issue.targetId)
        assertEquals(false, issue.retryable)
    }

    @Test
    fun appliesGraphEditRequestWithNodeDeleteCascadingIncidentEdges() {
        val nodeA = GraphNode(id = "node-a", type = NodeType.METHOD, title = "A")
        val nodeB = GraphNode(id = "node-b", type = NodeType.METHOD, title = "B")
        val nodeC = GraphNode(id = "node-c", type = NodeType.METHOD, title = "C")
        val edgeAB = GraphEdge(id = "edge-ab", type = EdgeType.CALL, fromNodeId = "node-a", toNodeId = "node-b")
        val edgeBC = GraphEdge(id = "edge-bc", type = EdgeType.CALL, fromNodeId = "node-b", toNodeId = "node-c")
        val snapshot = WorkflowEditorSnapshot(
            workspaceRevision = 4,
            workspaceGraph = GraphDocument(nodes = listOf(nodeA, nodeB, nodeC), edges = listOf(edgeAB, edgeBC)),
            factGraphView = ApplicationGraphView(
                projectionIndex = GraphProjectionIndex(
                    nodeMappings = listOf(nodeA, nodeB, nodeC).associate { node ->
                        node.id to GraphProjectionNodeMapping(
                            projectedNodeId = node.id,
                            mappingKind = GraphProjectionMappingKind.EXACT,
                            canonicalNodeIds = listOf(node.id),
                            editableCommandKinds = setOf(GraphEditCommandKind.DELETE_NODE),
                        )
                    },
                ),
            ),
        )
        val request = GraphEditRequest(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 4,
            operations = listOf(GraphEditOperation.RemoveNode("node-b")),
            source = GraphEditRequestSource.FRONTEND,
        )

        val result = useCase().applyGraphEditRequest(snapshot, request)

        val applied = assertIs<WorkspaceGraphUseCaseResult.EditApplied>(result)
        assertEquals(listOf("node-a", "node-c"), applied.graph.nodes.map { it.id })
        assertEquals(emptyList(), applied.graph.edges)
    }

    @Test
    fun appliesGraphEditRequestAfterResolvingProjectedNodeIds() {
        val nodeA = GraphNode(id = "node-a", type = NodeType.METHOD, title = "A")
        val nodeB = GraphNode(id = "node-b", type = NodeType.METHOD, title = "B")
        val snapshot = WorkflowEditorSnapshot(
            workspaceRevision = 4,
            workspaceGraph = GraphDocument(nodes = listOf(nodeA, nodeB)),
            factGraphView = ApplicationGraphView(
                projectionIndex = GraphProjectionIndex(
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
        )
        val request = GraphEditRequest(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 4,
            operations = listOf(
                GraphEditOperation.UpsertNode(
                    GraphNode(id = "projected-a", type = NodeType.METHOD, title = "A updated"),
                ),
            ),
            source = GraphEditRequestSource.FRONTEND,
        )

        val result = useCase().applyGraphEditRequest(snapshot, request)

        val applied = assertIs<WorkspaceGraphUseCaseResult.EditApplied>(result)
        assertEquals("A updated", applied.graph.nodes.single { it.id == "node-a" }.title)
        assertEquals(listOf("node-a", "node-b"), applied.graph.nodes.map { it.id })
    }

    @Test
    fun appliesProjectedNodeDeletionAfterResolvingCanonicalNodeIds() {
        val nodeA = GraphNode(id = "node-a", type = NodeType.METHOD, title = "A")
        val nodeB = GraphNode(id = "node-b", type = NodeType.METHOD, title = "B")
        val snapshot = WorkflowEditorSnapshot(
            workspaceRevision = 4,
            workspaceGraph = GraphDocument(nodes = listOf(nodeA, nodeB)),
            factGraphView = ApplicationGraphView(
                projectionIndex = GraphProjectionIndex(
                    nodeMappings = mapOf(
                        "projected-a" to GraphProjectionNodeMapping(
                            projectedNodeId = "projected-a",
                            mappingKind = GraphProjectionMappingKind.EXACT,
                            canonicalNodeIds = listOf("node-a"),
                            editableCommandKinds = setOf(GraphEditCommandKind.DELETE_NODE),
                        ),
                    ),
                ),
            ),
        )
        val request = GraphEditRequest(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 4,
            operations = listOf(GraphEditOperation.RemoveNode("projected-a")),
            source = GraphEditRequestSource.FRONTEND,
        )

        val result = useCase().applyGraphEditRequest(snapshot, request)

        val applied = assertIs<WorkspaceGraphUseCaseResult.EditApplied>(result)
        assertEquals(listOf("node-b"), applied.graph.nodes.map { it.id })
    }

    @Test
    fun appliesProjectedEdgeEndpointsAsCanonicalNodeIds() {
        val nodeA = GraphNode(id = "node-a", type = NodeType.METHOD, title = "A")
        val nodeB = GraphNode(id = "node-b", type = NodeType.METHOD, title = "B")
        val snapshot = WorkflowEditorSnapshot(
            workspaceRevision = 4,
            workspaceGraph = GraphDocument(nodes = listOf(nodeA, nodeB)),
            factGraphView = ApplicationGraphView(
                projectionIndex = GraphProjectionIndex(
                    nodeMappings = mapOf(
                        "projected-a" to GraphProjectionNodeMapping(
                            projectedNodeId = "projected-a",
                            mappingKind = GraphProjectionMappingKind.EXACT,
                            canonicalNodeIds = listOf("node-a"),
                            editableCommandKinds = setOf(GraphEditCommandKind.CONNECT_NODES),
                        ),
                        "projected-b" to GraphProjectionNodeMapping(
                            projectedNodeId = "projected-b",
                            mappingKind = GraphProjectionMappingKind.EXACT,
                            canonicalNodeIds = listOf("node-b"),
                            editableCommandKinds = setOf(GraphEditCommandKind.CONNECT_NODES),
                        ),
                    ),
                ),
            ),
        )
        val request = GraphEditRequest(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 4,
            operations = listOf(
                GraphEditOperation.UpsertEdge(
                    GraphEdge(
                        id = "edge-projected",
                        type = EdgeType.CALL,
                        fromNodeId = "projected-a",
                        toNodeId = "projected-b",
                    ),
                ),
            ),
            source = GraphEditRequestSource.FRONTEND,
        )

        val result = useCase().applyGraphEditRequest(snapshot, request)

        val applied = assertIs<WorkspaceGraphUseCaseResult.EditApplied>(result)
        assertEquals("node-a", applied.graph.edges.single().fromNodeId)
        assertEquals("node-b", applied.graph.edges.single().toNodeId)
    }

    private fun useCase(): WorkspaceGraphUseCase {
        return WorkspaceGraphUseCase(
            mermaidImporter = MermaidImporter(),
            mermaidValidator = MermaidValidator(),
            mermaidExporter = MermaidExporter(),
            graphDiffer = GraphDiffer(),
            syncPreviewPlanner = SyncPreviewPlanner(),
        )
    }
}
