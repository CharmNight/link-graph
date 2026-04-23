package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ResolveAnchorToolTest : BasePlatformTestCase() {
    fun testResolvesAnchorByNodeId() {
        val tool = ResolveAnchorTool(CodeReadToolFacade())
        val snapshot = com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
            workingGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit",
                        type = NodeType.METHOD,
                        title = "OrderService.submit",
                        signature = "com.example.OrderService.submit():void",
                    ),
                ),
            ),
        )

        val result = tool.invoke(
            input = mapOf("nodeId" to "method:submit"),
            context = ToolExecutionContext(
                project = project,
                snapshot = snapshot,
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val node = requireNotNull(result.payload["node"] as? GraphNode)
        assertEquals("method:submit", node.id)
    }

    fun testResolvesAnchorBySymbolSignature() {
        val tool = ResolveAnchorTool(CodeReadToolFacade())
        val snapshot = com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
            workingGraph = GraphDocument(
                nodes = listOf(
                    GraphNode(
                        id = "method:submit",
                        type = NodeType.METHOD,
                        title = "OrderService.submit",
                        signature = "com.example.OrderService.submit():void",
                    ),
                ),
            ),
        )

        val result = tool.invoke(
            input = mapOf("symbolSignature" to "com.example.OrderService.submit():void"),
            context = ToolExecutionContext(
                project = project,
                snapshot = snapshot,
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val node = requireNotNull(result.payload["node"] as? GraphNode)
        assertEquals("method:submit", node.id)
    }
}
