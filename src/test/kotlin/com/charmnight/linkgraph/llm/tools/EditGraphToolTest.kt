package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.application.model.GraphEditIssueCode
import com.charmnight.linkgraph.application.model.GraphEditRejected
import com.charmnight.linkgraph.application.model.GraphEditResult
import com.charmnight.linkgraph.application.model.GraphEditTransaction
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.port.GraphEditRequestExecutor
import com.charmnight.linkgraph.application.usecase.WorkspaceGraphUseCase
import com.charmnight.linkgraph.application.usecase.WorkspaceGraphUseCaseResult
import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditGraphToolTest : BasePlatformTestCase() {
    fun testAppliesGraphEditThroughSharedUseCaseExecutor() {
        val graph = GraphDocument(nodes = listOf(GraphNode(id = "node-old", type = NodeType.METHOD, title = "Old")))
        val result = EditGraphTool().invoke(
            input = requestPayload(
                baseWorkspaceRevision = 4,
                operations = listOf(
                    mapOf(
                        "type" to "UPSERT_NODE",
                        "node" to mapOf("id" to "node-new", "type" to "METHOD", "title" to "New"),
                    ),
                ),
            ),
            context = context(
                snapshot = WorkflowEditorSnapshot(workspaceRevision = 4, workspaceGraph = graph),
            ),
        )

        assertTrue(result.success)
        assertEquals("APPLIED", result.payload["status"])
        val transaction = result.payload["transaction"] as GraphEditTransaction
        assertEquals(listOf("node-old", "node-new"), transaction.graphAfterApply.nodes.map { it.id })
        assertEquals(4L, transaction.workspaceRevisionBefore)
        assertEquals(5L, transaction.workspaceRevisionAfter)
    }

    fun testReturnsStructuredStaleRevisionRejection() {
        val result = EditGraphTool().invoke(
            input = requestPayload(baseWorkspaceRevision = 3),
            context = context(snapshot = WorkflowEditorSnapshot(workspaceRevision = 4)),
        )

        assertFalse(result.success)
        assertEquals("REJECTED", result.payload["status"])
        val rejection = result.payload["rejection"] as GraphEditRejected
        assertEquals(4L, rejection.currentWorkspaceRevision)
        assertEquals(GraphEditIssueCode.STALE_BASE_REVISION, rejection.issues.single().code)
    }

    fun testReturnsStructuredInvalidEdgeEndpointRejection() {
        val graph = GraphDocument(nodes = listOf(GraphNode(id = "node-a", type = NodeType.METHOD, title = "A")))
        val result = EditGraphTool().invoke(
            input = requestPayload(
                baseWorkspaceRevision = 4,
                operations = listOf(
                    mapOf(
                        "type" to "UPSERT_EDGE",
                        "edge" to mapOf(
                            "id" to "edge-missing",
                            "type" to "CALL",
                            "fromNodeId" to "node-a",
                            "toNodeId" to "node-missing",
                        ),
                    ),
                ),
            ),
            context = context(
                snapshot = WorkflowEditorSnapshot(workspaceRevision = 4, workspaceGraph = graph),
            ),
        )

        assertFalse(result.success)
        val rejection = result.payload["rejection"] as GraphEditRejected
        assertEquals(GraphEditIssueCode.INVALID_EDGE_ENDPOINT, rejection.issues.single().code)
        assertEquals("edge-missing", rejection.issues.single().targetId)
    }

    fun testReturnsStructuredFailureForMalformedInput() {
        val result = EditGraphTool().invoke(
            input = mapOf(
                "sceneId" to GraphSceneId.WORKSPACE_FACT.name,
                "source" to "AI_TOOL",
                "operations" to emptyList<Map<String, Any?>>(),
            ),
            context = context(snapshot = WorkflowEditorSnapshot(workspaceRevision = 4)),
        )

        assertFalse(result.success)
        assertEquals("REJECTED", result.payload["status"])
        assertEquals("INVALID_REQUEST", result.payload["reason"])
        assertTrue(result.errorMessage?.contains("baseWorkspaceRevision") == true)
    }

    private fun requestPayload(
        baseWorkspaceRevision: Long,
        operations: List<Map<String, Any?>> = listOf(
            mapOf(
                "type" to "UPSERT_NODE",
                "node" to mapOf("id" to "node-new", "type" to "METHOD", "title" to "New"),
            ),
        ),
    ): Map<String, Any?> =
        mapOf(
            "sceneId" to GraphSceneId.WORKSPACE_FACT.name,
            "baseWorkspaceRevision" to baseWorkspaceRevision,
            "source" to "AI_TOOL",
            "operations" to operations,
        )

    private fun context(snapshot: WorkflowEditorSnapshot): ToolExecutionContext =
        ToolExecutionContext(
            project = project,
            snapshot = ToolGraphSnapshot(workspaceGraph = snapshot.workspaceGraph, workspaceRevision = snapshot.workspaceRevision),
            artifactStore = InMemoryArtifactStore(),
            runBudget = RunBudget(),
            graphEditRequestExecutor = executor(snapshot),
        )

    private fun executor(snapshot: WorkflowEditorSnapshot): GraphEditRequestExecutor =
        GraphEditRequestExecutor { request ->
            when (val result = useCase().applyGraphEditRequest(snapshot, request)) {
                is WorkspaceGraphUseCaseResult.EditApplied -> GraphEditResult.Applied(
                    graph = result.graph,
                    transaction = result.transaction,
                )
                is WorkspaceGraphUseCaseResult.EditRejected -> GraphEditResult.Rejected(result.rejection)
                else -> error("unexpected graph edit result: $result")
            }
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
