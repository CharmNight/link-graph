package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.testing.toToolGraphSnapshot
import com.charmnight.linkgraph.llm.artifact.InMemoryArtifactStore
import com.charmnight.linkgraph.llm.runtime.RunBudget
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphSceneId
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GetCurrentGraphToolTest : BasePlatformTestCase() {
    fun testReturnsInteractiveGraphAndSelectionSummary() {
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
                    workspaceRevision = 42,
                    selectedNodeId = "method:upload-file",
                ),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        assertEquals("interactiveGraph", result.payload["graphSource"])
        assertEquals(42L, result.payload["workspaceRevision"])
        assertEquals("WORKSPACE_FACT", result.payload["recommendedSceneId"])
        assertEquals(1, result.payload["nodeCount"])
        assertEquals(listOf("method:upload-file"), result.payload["selectedNodeIds"])
        assertNotNull(result.payload["graph"])
    }

    fun testMergesVisibleFlowchartProjectionWithWorkspaceSelectionNeighborhood() {
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
        val expandedNode = GraphNode(
            id = "action:expanded-save",
            type = NodeType.METHOD,
            title = "saveInfo()",
        )
        val workspaceGraph = GraphDocument(
            nodes = fullGraph.nodes + expandedNode,
            edges = listOf(
                com.charmnight.linkgraph.model.GraphEdge(
                    id = "edge:guard-to-expanded",
                    type = com.charmnight.linkgraph.model.EdgeType.CONTROL_FLOW,
                    fromNodeId = "scope:guard",
                    toNodeId = expandedNode.id,
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
                    workspaceGraph = workspaceGraph,
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

        assertEquals("interactiveGraph", result.payload["graphSource"])
        assertEquals(2, result.payload["nodeCount"])
        assertEquals(listOf("scope:guard"), result.payload["selectedNodeIds"])
        assertNotNull(result.payload["graph"])
    }

    fun testKeepsWholeInvocationExpansionBatchInCurrentInteractiveGraph() {
        val expansionId = "invocation:expansion-1"
        val caller = GraphNode(
            id = "method:submit-order",
            type = NodeType.METHOD,
            title = "OrderController.submit",
        )
        val invocation = GraphNode(
            id = "invoke:create-info",
            type = NodeType.FLOW_ACTION,
            title = "systemService.createInfo()",
        )
        val expandedMethod = GraphNode(
            id = "method:create-info",
            type = NodeType.METHOD,
            title = "SystemService.createInfo",
            metadata = mapOf("linkGraph.expansion.id" to expansionId),
        )
        val expandedAction = GraphNode(
            id = "action:save-info",
            type = NodeType.FLOW_ACTION,
            title = "saveInfo()",
            metadata = mapOf("linkGraph.expansion.id" to expansionId),
        )
        val visibleGraph = GraphDocument(
            nodes = listOf(caller, invocation, expandedMethod),
            edges = listOf(
                GraphEdge(
                    id = "control:submit-to-invoke",
                    type = EdgeType.CONTROL_FLOW,
                    fromNodeId = caller.id,
                    toNodeId = invocation.id,
                ),
                GraphEdge(
                    id = "call:invoke-to-create-info",
                    type = EdgeType.CALL,
                    fromNodeId = invocation.id,
                    toNodeId = expandedMethod.id,
                    metadata = mapOf("linkGraph.expansion.id" to expansionId),
                ),
            ),
        )
        val workspaceGraph = GraphDocument(
            nodes = listOf(caller, invocation, expandedMethod, expandedAction),
            edges = visibleGraph.edges + GraphEdge(
                id = "control:create-info-to-save",
                type = EdgeType.CONTROL_FLOW,
                fromNodeId = expandedMethod.id,
                toNodeId = expandedAction.id,
                metadata = mapOf("linkGraph.expansion.id" to expansionId),
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
                    workspaceGraph = workspaceGraph,
                    flowchartView = FlowchartViewDocument(
                        visibleGraph = visibleGraph,
                        fullGraph = workspaceGraph,
                        anchorNodeId = caller.id,
                    ),
                    selectedNodeId = expandedMethod.id,
                ),
                artifactStore = InMemoryArtifactStore(),
                runBudget = RunBudget(),
            ),
        )

        val graph = result.payload["graph"] as GraphDocument
        assertEquals(
            setOf(caller.id, invocation.id, expandedMethod.id, expandedAction.id),
            graph.nodes.map(GraphNode::id).toSet(),
        )
        assertEquals(
            setOf("control:submit-to-invoke", "call:invoke-to-create-info", "control:create-info-to-save"),
            graph.edges.map(GraphEdge::id).toSet(),
        )
    }

    fun testReturnsVisibleGraphWhenWorkspaceGraphIsEmpty() {
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

        assertEquals("interactiveGraph", result.payload["graphSource"])
        assertEquals(1, result.payload["nodeCount"])
        assertEquals(emptyList<String>(), result.payload["selectedNodeIds"])
        assertEquals(visibleGraph, result.payload["graph"])
    }
}

private fun snapshot(
    analysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
    currentSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    workspaceGraph: GraphDocument = GraphDocument(),
    workspaceRevision: Long = 0,
    factGraphView: FactGraphViewDocument = FactGraphViewDocument(),
    flowchartView: FlowchartViewDocument = FlowchartViewDocument(),
    resourceRelationView: ResourceRelationViewDocument = ResourceRelationViewDocument(),
    selectedNodeId: String? = null,
): ToolGraphSnapshot {
    val baseSceneStates = mapOf(
        GraphSceneId.WORKSPACE_FACT to com.charmnight.linkgraph.ui.GraphSceneState(),
        GraphSceneId.WORKSPACE_FLOWCHART to com.charmnight.linkgraph.ui.GraphSceneState(),
        GraphSceneId.WORKSPACE_RESOURCE_RELATION to com.charmnight.linkgraph.ui.GraphSceneState(),
        GraphSceneId.DIFF to com.charmnight.linkgraph.ui.GraphSceneState(),
    )
    val nextSceneState = baseSceneStates.getValue(currentSceneId).copy(selectedNodeId = selectedNodeId)
    return com.charmnight.linkgraph.ui.GraphEditorStateSnapshot(
        workspaceGraph = workspaceGraph,
        workspaceRevision = workspaceRevision,
        workspaceBaseGraph = workspaceGraph,
        semanticFactGraph = workspaceGraph,
        factGraphView = factGraphView,
        flowchartView = flowchartView,
        resourceRelationView = resourceRelationView,
        analysisDisplayMode = analysisDisplayMode,
        currentSceneId = currentSceneId,
        previousWorkspaceSceneId = GraphSceneId.WORKSPACE_FACT,
        sceneStates = baseSceneStates + (currentSceneId to nextSceneState),
    ).toToolGraphSnapshot()
}
