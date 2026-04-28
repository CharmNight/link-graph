package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
}
