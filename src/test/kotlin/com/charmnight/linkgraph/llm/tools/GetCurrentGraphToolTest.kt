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

class GetCurrentGraphToolTest : BasePlatformTestCase() {
    fun testReturnsWorkingGraphAndSelectionSummary() {
        val graph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "method:upload-file",
                    type = NodeType.METHOD,
                    title = "CommonController.uploadFile",
                ),
            ),
        )
        val tool = GetCurrentGraphTool(GraphToolFacade())

        val result = tool.invoke(
            input = emptyMap(),
            context = ToolExecutionContext(
                project = project,
                snapshot = GraphEditorStateService.Snapshot(
                    workingGraph = graph,
                    selectedNodeId = "method:upload-file",
                ),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertEquals("workingGraph", result.payload["graphSource"])
        assertEquals(1, result.payload["nodeCount"])
        assertEquals(listOf("method:upload-file"), result.payload["selectedNodeIds"])
        assertNotNull(result.payload["graph"])
    }
}
