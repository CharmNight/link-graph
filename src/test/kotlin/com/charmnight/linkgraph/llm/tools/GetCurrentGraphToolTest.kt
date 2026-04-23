package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
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
                snapshot = com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
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

    fun testPrefersWorkingGraphBeforeFlowchartFullGraphWhenFlowchartModeUsesReadableProjection() {
        val visibleGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "scope:guard",
                    type = NodeType.METHOD,
                    title = "if (!allowed)",
                ),
            ),
        )
        val fullGraph = GraphDocument(
            nodes = listOf(
                GraphNode(
                    id = "action:guard-condition",
                    type = NodeType.METHOD,
                    title = "!checkAllowDownload(fileName)",
                ),
                GraphNode(
                    id = "scope:guard",
                    type = NodeType.METHOD,
                    title = "if (!allowed)",
                ),
            ),
        )
        val tool = GetCurrentGraphTool(GraphToolFacade())

        val result = tool.invoke(
            input = emptyMap(),
            context = ToolExecutionContext(
                project = project,
                snapshot = com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
                    analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
                    visibleGraph = visibleGraph,
                    workingGraph = visibleGraph,
                    flowchartView = FlowchartViewDocument(
                        visibleGraph = visibleGraph,
                        fullGraph = fullGraph,
                        anchorNodeId = "scope:guard",
                    ),
                    selectedNodeId = "scope:guard",
                ),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertEquals("workingGraph", result.payload["graphSource"])
        assertEquals(1, result.payload["nodeCount"])
        assertEquals(listOf("scope:guard"), result.payload["selectedNodeIds"])
        assertNotNull(result.payload["graph"])
    }
}
