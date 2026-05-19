package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.workbench.DraftEntryKind
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry
import com.charmnight.linkgraph.workbench.DraftWorkbenchState
import java.util.EnumMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class GraphEditorStateStoreTest {
    @Test
    fun staleCommitDoesNotOverwriteNewerStoreState() {
        val store = GraphEditorStateStore(testSnapshot())
        val baseRevision = store.snapshot().snapshotRevision

        val firstCommit = store.tryCommit(baseRevision) { current ->
            current.withOperationFeedback(
                level = OperationFeedbackLevel.INFO,
                message = "较新的短事务",
            )
        }
        val staleCommit = store.tryCommit(baseRevision) { current ->
            current.withOperationFeedback(
                level = OperationFeedbackLevel.ERROR,
                message = "旧快照不允许整块覆盖",
            )
        }

        val snapshot = store.snapshot()
        assertTrue(firstCommit.committed)
        assertFalse(staleCommit.committed)
        assertEquals("较新的短事务", snapshot.operationFeedback?.message)
        assertNotEquals("旧快照不允许整块覆盖", snapshot.operationFeedback?.message)
    }

    @Test
    fun layoutAndSelectionAreIsolatedByScene() {
        val service = GraphEditorStateService()
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:place-order",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                ),
                GraphNode(
                    id = "sql:insert-order",
                    type = NodeType.SQL,
                    title = "insert into orders",
                ),
            ),
        )

        service.loadGraph(graph, "test")
        service.selectNode("method:place-order")
        service.markLayoutChanged(
            mapOf("method:place-order" to GraphLayoutPosition(x = 120.0, y = 80.0)),
        )
        service.switchAnalysisDisplayMode(com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.RESOURCE_RELATION_VIEW)
        service.selectNode("sql:insert-order")
        service.markLayoutChanged(
            mapOf("sql:insert-order" to GraphLayoutPosition(x = 480.0, y = 220.0)),
        )

        val snapshot = service.snapshot()
        assertEquals(GraphSceneId.WORKSPACE_RESOURCE_RELATION, snapshot.currentSceneId)
        assertEquals(
            "method:place-order",
            snapshot.sceneStates.getValue(GraphSceneId.WORKSPACE_FACT).selectedNodeId,
        )
        assertEquals(
            "sql:insert-order",
            snapshot.sceneStates.getValue(GraphSceneId.WORKSPACE_RESOURCE_RELATION).selectedNodeId,
        )
        assertEquals(
            120.0,
            snapshot.sceneStates.getValue(GraphSceneId.WORKSPACE_FACT).layoutState.positions["method:place-order"]?.x,
        )
        assertEquals(
            null,
            snapshot.sceneStates.getValue(GraphSceneId.WORKSPACE_FACT).layoutState.positions["sql:insert-order"],
        )
        assertEquals(
            480.0,
            snapshot.sceneStates.getValue(GraphSceneId.WORKSPACE_RESOURCE_RELATION).layoutState.positions["sql:insert-order"]?.x,
        )
    }

    @Test
    fun snapshotFreezesTopLevelCollections() {
        val sceneStates = EnumMap<GraphSceneId, GraphSceneState>(GraphSceneId::class.java)
        sceneStates[GraphSceneId.WORKSPACE_FACT] = GraphSceneState(
            selectedNodeId = "method:place-order",
            layoutState = GraphLayoutState(
                positions = linkedMapOf(
                    "method:place-order" to GraphLayoutPosition(120.0, 80.0),
                ),
            ),
            collapsedNodeIds = linkedSetOf("method:collapsed"),
        )
        val trustedNavigationNodes = linkedMapOf(
            "method:place-order" to GraphNode(
                id = "method:place-order",
                type = NodeType.METHOD,
                title = "OrderService.place",
            ),
        )
        val runtimeSummaries = linkedMapOf(
            "qa" to mutableListOf(
                RuntimeArtifactSummary(
                    artifactId = "artifact-1",
                    artifactType = "GRAPH_SUMMARY",
                    title = "图摘要",
                ),
            ),
        )
        val initial = testSnapshot(
            sceneStates = sceneStates,
            trustedNavigationNodes = trustedNavigationNodes,
            runtimeArtifactSummaries = runtimeSummaries,
            workbenchSectionPreferences = linkedMapOf("qa.request-status" to true),
        )
        val store = GraphEditorStateStore(initial)

        sceneStates[GraphSceneId.WORKSPACE_FACT] = GraphSceneState(selectedNodeId = "mutated")
        trustedNavigationNodes.clear()
        runtimeSummaries["qa"]?.clear()
        val snapshot = store.snapshot()

        assertEquals("method:place-order", snapshot.sceneStates.getValue(GraphSceneId.WORKSPACE_FACT).selectedNodeId)
        assertEquals(1, snapshot.trustedNavigationNodes.size)
        assertEquals(1, snapshot.runtimeArtifactSummaries.getValue("qa").size)
        assertNotSame(sceneStates, snapshot.sceneStates)
        assertNotSame(trustedNavigationNodes, snapshot.trustedNavigationNodes)
        assertNotSame(runtimeSummaries, snapshot.runtimeArtifactSummaries)
    }

    @Test
    fun snapshotFreezesNestedWorkbenchCollections() {
        val draftChanges = mutableListOf(
            DraftWorkbenchEntry(
                entryId = "draft-1",
                kind = DraftEntryKind.CHANGE,
                title = "修改上传链路",
            ),
        )
        val initial = testSnapshot(
            draftWorkbenchState = DraftWorkbenchState(
                draftChanges = draftChanges,
            ),
        )
        val store = GraphEditorStateStore(initial)

        draftChanges.clear()
        val snapshot = store.snapshot()

        assertEquals(1, snapshot.draftWorkbenchState.draftChanges.size)
        assertNotSame(draftChanges, snapshot.draftWorkbenchState.draftChanges)
    }

    @Test
    fun mutateReturnsSnapshotThatDoesNotShareStoredCollections() {
        val mutableMetadata = linkedMapOf("source" to "before")
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:place-order",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    metadata = mutableMetadata,
                ),
            ),
        )
        val store = GraphEditorStateStore()

        val returned = store.mutate { current ->
            current.copy(workspaceGraph = graph)
        }
        mutableMetadata["source"] = "after"

        val stored = store.snapshot()

        assertEquals("before", returned.workspaceGraph.nodes.single().metadata["source"])
        assertEquals("before", stored.workspaceGraph.nodes.single().metadata["source"])
        assertNotSame(returned.workspaceGraph.nodes, stored.workspaceGraph.nodes)
    }
}
