package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.testing.*

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.ui.GraphEditorStateService
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectEditorSessionTest {
    @Test
    fun mutateSyncsBrowserWhenStateChanges() {
        val stateService = GraphEditorStateService()
        var syncCount = 0
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = { syncCount += 1 },
        )

        session.mutate {
            asyncRequests.beginGenerationPlanRequest()
        }

        assertEquals(1, syncCount)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.RUNNING, stateService.snapshot().generationPlanRequestState.phase)
    }

    @Test
    fun mutateCanSkipBrowserSync() {
        val stateService = GraphEditorStateService()
        var syncCount = 0
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = { syncCount += 1 },
        )

        session.mutate(syncBrowser = false) {
            asyncRequests.beginGenerationPlanRequest()
        }

        assertEquals(0, syncCount)
        assertEquals(com.charmnight.linkgraph.ui.AsyncRequestPhase.RUNNING, stateService.snapshot().generationPlanRequestState.phase)
    }

    @Test
    fun markGraphChangedUpdatesStateAndSyncsBrowser() {
        val stateService = GraphEditorStateService()
        var syncCount = 0
        val session = ProjectEditorSession(
            stateService = stateService,
            onBrowserSyncRequested = { syncCount += 1 },
        )
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:place-order",
                    type = NodeType.METHOD,
                    title = "OrderService.place",
                    signature = "com.example.OrderService.place():void",
                ),
            ),
        )

        session.markGraphChanged(
            graph = graph,
            selectedMethodSignature = "com.example.OrderService.place():void",
        )

        assertEquals(1, syncCount)
        assertEquals(graph, stateService.snapshot().visibleGraph)
        assertEquals("com.example.OrderService.place():void", stateService.snapshot().selectedMethodSignature)
    }
}
