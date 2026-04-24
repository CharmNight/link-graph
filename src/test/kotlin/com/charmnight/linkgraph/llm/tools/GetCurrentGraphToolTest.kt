package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphEditorStateSnapshot
import com.charmnight.linkgraph.ui.GraphSceneId
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GetCurrentGraphToolTest : BasePlatformTestCase() {
    fun testReturnsWorkspaceGraphAndSelectionSummary() {
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
                snapshot = snapshot(
                    workspaceGraph = graph,
                    selectedNodeId = "method:upload-file",
                ),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertEquals("workspaceGraph", result.payload["graphSource"])
        assertEquals(1, result.payload["nodeCount"])
        assertEquals(listOf("method:upload-file"), result.payload["selectedNodeIds"])
        assertNotNull(result.payload["graph"])
    }

    fun testPrefersWorkspaceGraphBeforeFlowchartProjectionWhenFlowchartModeUsesReadableProjection() {
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
                snapshot = snapshot(
                    analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
                    currentSceneId = GraphSceneId.WORKSPACE_FLOWCHART,
                    workspaceGraph = visibleGraph,
                    factGraphView = FactGraphViewDocument(),
                    flowchartView = FlowchartViewDocument(
                        visibleGraph = visibleGraph,
                        fullGraph = fullGraph,
                        anchorNodeId = "scope:guard",
                    ),
                    resourceRelationView = ResourceRelationViewDocument(),
                    selectedNodeId = "scope:guard",
                ),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertEquals("workspaceGraph", result.payload["graphSource"])
        assertEquals(1, result.payload["nodeCount"])
        assertEquals(listOf("scope:guard"), result.payload["selectedNodeIds"])
        assertNotNull(result.payload["graph"])
    }

    fun testReturnsEmptyWorkspaceSourceInsteadOfFallingBackToProjectionFullGraph() {
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
            ),
        )
        val tool = GetCurrentGraphTool(GraphToolFacade())

        val result = tool.invoke(
            input = emptyMap(),
            context = ToolExecutionContext(
                project = project,
                snapshot = snapshot(
                    analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
                    currentSceneId = GraphSceneId.WORKSPACE_FLOWCHART,
                    workspaceGraph = GraphDocument(),
                    flowchartView = FlowchartViewDocument(
                        visibleGraph = visibleGraph,
                        fullGraph = fullGraph,
                        anchorNodeId = "scope:guard",
                    ),
                ),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertEquals("emptyGraph", result.payload["graphSource"])
        assertEquals(0, result.payload["nodeCount"])
        assertEquals(emptyList<String>(), result.payload["selectedNodeIds"])
        assertEquals(GraphDocument(), result.payload["graph"])
    }
}

private fun snapshot(
    analysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
    currentSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    workspaceGraph: GraphDocument = GraphDocument(),
    factGraphView: FactGraphViewDocument = FactGraphViewDocument(),
    flowchartView: FlowchartViewDocument = FlowchartViewDocument(),
    resourceRelationView: ResourceRelationViewDocument = ResourceRelationViewDocument(),
    selectedNodeId: String? = null,
): GraphEditorStateSnapshot {
    val baseSceneStates = mapOf(
        GraphSceneId.WORKSPACE_FACT to com.charmnight.linkgraph.ui.GraphSceneState(),
        GraphSceneId.WORKSPACE_FLOWCHART to com.charmnight.linkgraph.ui.GraphSceneState(),
        GraphSceneId.WORKSPACE_RESOURCE_RELATION to com.charmnight.linkgraph.ui.GraphSceneState(),
        GraphSceneId.DIFF to com.charmnight.linkgraph.ui.GraphSceneState(),
    )
    val nextSceneState = baseSceneStates.getValue(currentSceneId).copy(selectedNodeId = selectedNodeId)
    return GraphEditorStateSnapshot(
        workspaceGraph = workspaceGraph,
        workspaceBaseGraph = workspaceGraph,
        semanticFactGraph = workspaceGraph,
        factGraphView = factGraphView,
        flowchartView = flowchartView,
        resourceRelationView = resourceRelationView,
        analysisDisplayMode = analysisDisplayMode,
        currentSceneId = currentSceneId,
        previousWorkspaceSceneId = GraphSceneId.WORKSPACE_FACT,
        sceneStates = baseSceneStates + (currentSceneId to nextSceneState),
    )
}
