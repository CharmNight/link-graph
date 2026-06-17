package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.application.model.AsyncRequestPhase
import com.charmnight.linkgraph.application.model.AsyncRequestState
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphEditorIndexedGraphStateSupportTest {
    @Test
    fun `ignores stale indexed graph load results from older request ids`() {
        val stateService = GraphEditorStateService()
        val newerRequest = AsyncRequestState.running(
            requestId = 2L,
            scene = IndexedGraphView.ARCHITECTURE.name,
            statusMessage = "newer",
        )
        val olderResult = AsyncRequestState.succeeded(
            requestId = 1L,
            scene = IndexedGraphView.ARCHITECTURE.name,
            statusMessage = "older",
        )

        stateService.indexedGraphs.beginIndexedGraphRequest(
            view = IndexedGraphView.ARCHITECTURE,
            requestState = newerRequest,
            statusMessage = "newer",
        )
        stateService.indexedGraphs.loadArchitectureGraphView(
            view = architectureViewWithNode("stale"),
            requestState = olderResult,
            statusMessage = "older",
        )

        val snapshot = stateService.snapshot()
        assertEquals(AsyncRequestPhase.RUNNING, snapshot.indexedGraphRequestStates[IndexedGraphView.ARCHITECTURE]?.phase)
        assertEquals(2L, snapshot.indexedGraphRequestStates[IndexedGraphView.ARCHITECTURE]?.requestId)
        assertTrue(snapshot.architectureGraphView.visibleGraph.nodes.isEmpty())
        assertEquals("indexedGraphRequestStarted", snapshot.lastMessageType)
    }

    @Test
    fun `class diagram load uses the view anchor instead of a stale scene anchor`() {
        val stateService = GraphEditorStateService()
        val staleAnchor = classNode("jvm:class:kafka-server-kafkaconfig", "KafkaConfig")
        val requestedAnchor = classNode("jvm:class:kafka-server-metadataversionconfigvalidator", "MetadataVersionConfigValidator")
        val initialGraph = GraphDocument(nodes = listOf(staleAnchor))

        stateService.loadClassDiagramView(
            ClassDiagramResult(
                visibleGraph = initialGraph,
                fullGraph = initialGraph,
                anchorNodeId = staleAnchor.id,
            ),
        )
        stateService.indexedGraphs.loadClassDiagramView(
            view = ClassDiagramResult(
                visibleGraph = GraphDocument(
                    nodes = listOf(staleAnchor, requestedAnchor),
                    edges = listOf(
                        GraphEdge(
                            id = "uses-type:validator->config",
                            type = EdgeType.USES_TYPE,
                            fromNodeId = requestedAnchor.id,
                            toNodeId = staleAnchor.id,
                        ),
                    ),
                ),
                fullGraph = GraphDocument(
                    nodes = listOf(staleAnchor, requestedAnchor),
                    edges = listOf(
                        GraphEdge(
                            id = "uses-type:validator->config",
                            type = EdgeType.USES_TYPE,
                            fromNodeId = requestedAnchor.id,
                            toNodeId = staleAnchor.id,
                        ),
                    ),
                ),
                anchorNodeId = requestedAnchor.id,
            ),
            requestState = AsyncRequestState.running(
                requestId = 1L,
                scene = IndexedGraphView.CLASS_DIAGRAM.name,
            ),
            statusMessage = "已加载类图结构，正在补齐完整关系。",
        )

        val snapshot = stateService.snapshot()
        assertEquals(requestedAnchor.id, snapshot.classDiagramView.anchorNodeId)
        assertEquals(requestedAnchor.id, snapshot.sceneState(GraphSceneId.WORKSPACE_CLASS_DIAGRAM).anchorNodeId)
        assertEquals(requestedAnchor.id, snapshot.sceneState(GraphSceneId.WORKSPACE_CLASS_DIAGRAM).selectedNodeId)
    }

    @Test
    fun `starting class diagram request clears stale diagram instead of rendering it as current result`() {
        val stateService = GraphEditorStateService()
        val staleAnchor = classNode("jvm:class:kafka-server-kafkaconfig", "KafkaConfig")
        val initialGraph = GraphDocument(nodes = listOf(staleAnchor))

        stateService.loadClassDiagramView(
            ClassDiagramResult(
                visibleGraph = initialGraph,
                fullGraph = initialGraph,
                anchorNodeId = staleAnchor.id,
            ),
        )

        stateService.indexedGraphs.beginIndexedGraphRequest(
            view = IndexedGraphView.CLASS_DIAGRAM,
            requestState = AsyncRequestState.running(
                requestId = 7L,
                scene = IndexedGraphView.CLASS_DIAGRAM.name,
                statusMessage = "正在构建项目类图。",
            ),
            statusMessage = "正在构建项目类图。",
        )

        val snapshot = stateService.snapshot()
        assertEquals(GraphSceneId.WORKSPACE_CLASS_DIAGRAM, snapshot.currentSceneId)
        assertTrue(snapshot.classDiagramView.visibleGraph.nodes.isEmpty())
        assertTrue(snapshot.classDiagramView.visibleGraph.edges.isEmpty())
        assertEquals(null, snapshot.classDiagramView.anchorNodeId)
        assertEquals(null, snapshot.sceneState(GraphSceneId.WORKSPACE_CLASS_DIAGRAM).anchorNodeId)
        assertEquals(null, snapshot.sceneState(GraphSceneId.WORKSPACE_CLASS_DIAGRAM).selectedNodeId)
        assertEquals(AsyncRequestPhase.RUNNING, snapshot.indexedGraphRequestStates[IndexedGraphView.CLASS_DIAGRAM]?.phase)
    }

    private fun architectureViewWithNode(nodeId: String): ArchitectureGraphResult {
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = nodeId,
                    type = NodeType.COMPONENT,
                    title = nodeId,
                ),
            ),
        )
        return ArchitectureGraphResult(
            visibleGraph = graph,
            fullGraph = graph,
        )
    }

    private fun classNode(
        nodeId: String,
        title: String,
    ): GraphNode =
        GraphNode(
            id = nodeId,
            type = NodeType.CLASS,
            title = title,
        )
}
