package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.application.GraphEditorApplicationService
import com.charmnight.linkgraph.application.indexed.requestReviewGraphRequest
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.application.runtime.LinkGraphProjectTestOverrides
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerServiceInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ReviewGraphIT : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        project.registerServiceInstance(GraphEditorStateService::class.java, GraphEditorStateService())
        project.registerServiceInstance(LinkGraphProjectTestOverrides::class.java, LinkGraphProjectTestOverrides())
        project.registerServiceInstance(GraphEditorApplicationService::class.java, GraphEditorApplicationService(project))
        project.registerServiceInstance(GraphEditorCommandRouter::class.java, GraphEditorCommandRouter(project))
    }

    fun testBridgeDispatchRequestsReviewGraphFromDiff() {
        val sourcePath = "src/main/java/com/example/reviewit/OrderService.java"
        myFixture.addFileToProject(
            sourcePath,
            """
                package com.example.reviewit;

                class OrderService {
                    void place() {
                        new OrderRepository().save();
                    }
                }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/main/java/com/example/reviewit/OrderRepository.java",
            """
                package com.example.reviewit;

                class OrderRepository {
                    void save() {}
                }
            """.trimIndent(),
        )
        val bridge = GraphEditorBridge(project)
        val codeGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "class:/$sourcePath",
                    type = NodeType.CLASS,
                    title = "OrderService",
                    location = "$sourcePath:3:1",
                    signature = "com.example.reviewit.OrderService",
                ),
            ),
        )
        val mermaid = """
            graph TD
            %% LG_NODE SERVICE|nodeId=class:/$sourcePath|nodeType=CLASS|title=OrderService|signature=com.example.reviewit.OrderService|location=$sourcePath%3A4%3A1
            SERVICE["OrderService"]
        """.trimIndent()

        bridge.dispatch(GraphEditorMessage.LoadGraph(codeGraph, "review-graph-it-code"))
        bridge.dispatch(GraphEditorMessage.ImportMermaid(mermaid))
        bridge.dispatch(GraphEditorMessage.ShowDiffMode)
        bridge.dispatch(GraphEditorMessage.RequestIndexedGraph(requestReviewGraphRequest()))
        waitForReviewGraph()

        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        val graph = snapshot.reviewGraphView.visibleGraph
        assertEquals(GraphSceneId.WORKSPACE_REVIEW_GRAPH, snapshot.currentSceneId)
        assertReviewGraphViewDataContract(snapshot.reviewGraphView, "bridge.reviewGraph")
        assertNotNull(graph.nodes.singleOrNull { node -> node.signature == "com.example.reviewit.OrderService" })
        assertTrue(snapshot.reviewGraphView.summary.changedSymbolCount > 0)
        assertEquals(1, snapshot.reviewGraphView.summary.affectedPackageCount)
        assertTrue(
            graph.edges.any { edge ->
                edge.metadata["review.edgeRole"] in setOf("UPSTREAM", "DOWNSTREAM", "RELATION", "RELATED_TEST")
            },
            "ReviewGraphIT must prove bridge/workflow/state projection uses indexed blast-radius relations.",
        )
        assertEquals("SUCCESS", snapshot.operationFeedback?.level?.name)
    }

    private fun waitForReviewGraph() {
        repeat(100) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
            if (
                snapshot.currentSceneId == GraphSceneId.WORKSPACE_REVIEW_GRAPH &&
                snapshot.reviewGraphView.visibleGraph.nodes.isNotEmpty()
            ) {
                return
            }
            Thread.sleep(100)
        }
        val snapshot = project.getService(GraphEditorStateService::class.java).snapshot()
        fail("Expected Review Graph to be loaded, last feedback=${snapshot.operationFeedback}")
    }
}
