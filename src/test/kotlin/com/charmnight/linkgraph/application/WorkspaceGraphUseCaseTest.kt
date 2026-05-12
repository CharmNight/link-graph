package com.charmnight.linkgraph.application

import com.charmnight.linkgraph.application.model.ApplicationGraphView
import com.charmnight.linkgraph.application.model.GraphEditOperation
import com.charmnight.linkgraph.application.model.GraphEditScript
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.usecase.WorkspaceGraphUseCase
import com.charmnight.linkgraph.application.usecase.WorkspaceGraphUseCaseResult
import com.charmnight.linkgraph.diff.GraphDiffer
import com.charmnight.linkgraph.mermaid.MermaidExporter
import com.charmnight.linkgraph.mermaid.MermaidImporter
import com.charmnight.linkgraph.mermaid.MermaidValidator
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.sync.SyncPreviewPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkspaceGraphUseCaseTest {
    @Test
    fun ignoresFrontendEditWhenWorkspaceRevisionDoesNotMatch() {
        val snapshot = WorkflowEditorSnapshot(workspaceRevision = 7)
        val script = GraphEditScript(
            sceneId = GraphSceneId.WORKSPACE_FACT,
            baseWorkspaceRevision = 6,
            operations = listOf(
                GraphEditOperation.UpsertNode(GraphNode(id = "node-new", type = NodeType.METHOD, title = "new")),
            ),
        )

        val result = useCase().applyFrontendEditScript(snapshot, script)

        val ignored = assertIs<WorkspaceGraphUseCaseResult.EditIgnored>(result)
        assertEquals("workspace revision mismatch", ignored.reason)
    }

    @Test
    fun appliesFrontendNodeEditAsPureWorkspaceGraphResult() {
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
        val script = GraphEditScript(
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
        )

        val result = useCase().applyFrontendEditScript(snapshot, script)

        val applied = assertIs<WorkspaceGraphUseCaseResult.EditApplied>(result)
        assertEquals(11, applied.expectedSnapshotRevision)
        assertEquals("com.example.Service.run():void", applied.selectedMethodSignature)
        assertEquals(listOf("node-new", "node-old"), applied.graph.nodes.map { it.id })
        assertEquals(null, applied.graph.nodes.first { it.id == "node-new" }.location)
        assertEquals(null, applied.graph.nodes.first { it.id == "node-new" }.signature)
        assertEquals(listOf("node-old"), snapshot.workspaceGraph.nodes.map { it.id })
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
