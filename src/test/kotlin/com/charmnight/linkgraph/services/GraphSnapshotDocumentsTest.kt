package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphEditorStateSnapshot
import com.charmnight.linkgraph.ui.GraphSceneId
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphSnapshotDocumentsTest {
    @Test
    fun currentVisibleGraphUsesCurrentSceneProjectionAndDiffGraph() {
        val factVisible = GraphDocument(
            nodes = listOf(GraphNode(id = "fact-visible", type = NodeType.METHOD, title = "fact")),
        )
        val flowchartVisible = GraphDocument(
            nodes = listOf(GraphNode(id = "flow-visible", type = NodeType.METHOD, title = "flow")),
        )
        val resourceVisible = GraphDocument(
            nodes = listOf(GraphNode(id = "resource-visible", type = NodeType.METHOD, title = "resource")),
        )
        val diffGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "diff-visible", type = NodeType.CLASS, title = "diff")),
        )

        val baseSnapshot = snapshot(
            workspaceGraph = GraphDocument(
                nodes = listOf(GraphNode(id = "workspace", type = NodeType.METHOD, title = "workspace")),
            ),
            factGraphView = FactGraphViewDocument(visibleGraph = factVisible, fullGraph = factVisible),
            flowchartView = FlowchartViewDocument(visibleGraph = flowchartVisible, fullGraph = flowchartVisible),
            resourceRelationView = ResourceRelationViewDocument(
                visibleGraph = resourceVisible,
                fullGraph = resourceVisible,
            ),
            diffGraph = diffGraph,
        )

        assertEquals(factVisible, currentVisibleGraph(baseSnapshot.copy(currentSceneId = GraphSceneId.WORKSPACE_FACT)))
        assertEquals(
            flowchartVisible,
            currentVisibleGraph(baseSnapshot.copy(currentSceneId = GraphSceneId.WORKSPACE_FLOWCHART)),
        )
        assertEquals(
            resourceVisible,
            currentVisibleGraph(baseSnapshot.copy(currentSceneId = GraphSceneId.WORKSPACE_RESOURCE_RELATION)),
        )
        assertEquals(diffGraph, currentVisibleGraph(baseSnapshot.copy(currentSceneId = GraphSceneId.DIFF)))
    }

    @Test
    fun currentVisibleGraphDoesNotFallbackToWorkspaceOrReferenceGraphsWhenProjectionIsEmpty() {
        val workspaceGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "workspace", type = NodeType.METHOD, title = "workspace")),
        )
        val semanticFactGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "semantic", type = NodeType.METHOD, title = "semantic")),
        )
        val designBaselineGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "baseline", type = NodeType.CLASS, title = "baseline")),
        )
        val emptyGraph = GraphDocument()

        val factSnapshot = snapshot(
            currentSceneId = GraphSceneId.WORKSPACE_FACT,
            workspaceGraph = workspaceGraph,
            semanticFactGraph = semanticFactGraph,
            designBaselineGraph = designBaselineGraph,
            factGraphView = FactGraphViewDocument(visibleGraph = emptyGraph, fullGraph = semanticFactGraph),
            flowchartView = FlowchartViewDocument(visibleGraph = workspaceGraph, fullGraph = workspaceGraph),
            resourceRelationView = ResourceRelationViewDocument(visibleGraph = workspaceGraph, fullGraph = workspaceGraph),
        )
        val flowchartSnapshot = snapshot(
            currentSceneId = GraphSceneId.WORKSPACE_FLOWCHART,
            workspaceGraph = workspaceGraph,
            semanticFactGraph = semanticFactGraph,
            designBaselineGraph = designBaselineGraph,
            factGraphView = FactGraphViewDocument(visibleGraph = semanticFactGraph, fullGraph = semanticFactGraph),
            flowchartView = FlowchartViewDocument(visibleGraph = emptyGraph, fullGraph = workspaceGraph),
            resourceRelationView = ResourceRelationViewDocument(visibleGraph = workspaceGraph, fullGraph = workspaceGraph),
        )
        val resourceSnapshot = snapshot(
            currentSceneId = GraphSceneId.WORKSPACE_RESOURCE_RELATION,
            workspaceGraph = workspaceGraph,
            semanticFactGraph = semanticFactGraph,
            designBaselineGraph = designBaselineGraph,
            factGraphView = FactGraphViewDocument(visibleGraph = semanticFactGraph, fullGraph = semanticFactGraph),
            flowchartView = FlowchartViewDocument(visibleGraph = workspaceGraph, fullGraph = workspaceGraph),
            resourceRelationView = ResourceRelationViewDocument(visibleGraph = emptyGraph, fullGraph = workspaceGraph),
        )

        assertEquals(emptyGraph, currentVisibleGraph(factSnapshot))
        assertEquals(emptyGraph, currentVisibleGraph(flowchartSnapshot))
        assertEquals(emptyGraph, currentVisibleGraph(resourceSnapshot))
    }

    @Test
    fun currentGraphSelectorsPreserveExistingGraphContent() {
        val visibleGraph = GraphDocument(
            nodes = listOf(
                GraphNode(id = "method:submit-order", type = NodeType.METHOD, title = "submit"),
                GraphNode(
                    id = "draft-entry:change-submit-order",
                    type = NodeType.DOC_PAGE,
                    title = "draft projection",
                    sourceTag = GraphSourceTag.DRAFT_MANUAL,
                    metadata = mapOf("draft.entryId" to "change-submit-order"),
                ),
            ),
            edges = listOf(
                GraphEdge(
                    id = "links:submit-order->draft-projection",
                    type = com.charmnight.linkgraph.model.EdgeType.LINKS_DOC,
                    fromNodeId = "method:submit-order",
                    toNodeId = "draft-entry:change-submit-order",
                    sourceTag = GraphSourceTag.DRAFT_MANUAL,
                    metadata = mapOf("draft.entryId" to "change-submit-order"),
                ),
            ),
        )

        val snapshot = snapshot(
            workspaceGraph = visibleGraph,
            factGraphView = FactGraphViewDocument(visibleGraph = visibleGraph, fullGraph = visibleGraph),
        )
        val sanitizedVisible = currentVisibleGraph(snapshot)
        val sanitizedWorking = currentWorkingGraph(snapshot)

        assertEquals(visibleGraph.nodes.map(GraphNode::id), sanitizedVisible.nodes.map(GraphNode::id))
        assertEquals(visibleGraph.edges.map(GraphEdge::id), sanitizedVisible.edges.map(GraphEdge::id))
        assertEquals(visibleGraph.nodes.map(GraphNode::id), sanitizedWorking.nodes.map(GraphNode::id))
        assertEquals(visibleGraph.edges.map(GraphEdge::id), sanitizedWorking.edges.map(GraphEdge::id))
    }

    @Test
    fun currentWorkingGraphUsesWorkspaceGraphAcrossScenes() {
        val workspaceGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "workspace", type = NodeType.METHOD, title = "workspace")),
        )
        val semanticFactGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "semantic", type = NodeType.METHOD, title = "semantic")),
        )
        val designBaselineGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "baseline", type = NodeType.CLASS, title = "baseline")),
        )

        listOf(
            GraphSceneId.WORKSPACE_FACT,
            GraphSceneId.WORKSPACE_FLOWCHART,
            GraphSceneId.WORKSPACE_RESOURCE_RELATION,
        ).forEach { sceneId ->
            val snapshot = snapshot(
                currentSceneId = sceneId,
                workspaceGraph = workspaceGraph,
                semanticFactGraph = semanticFactGraph,
                designBaselineGraph = designBaselineGraph,
                factGraphView = FactGraphViewDocument(visibleGraph = semanticFactGraph, fullGraph = semanticFactGraph),
                flowchartView = FlowchartViewDocument(visibleGraph = semanticFactGraph, fullGraph = semanticFactGraph),
                resourceRelationView = ResourceRelationViewDocument(
                    visibleGraph = semanticFactGraph,
                    fullGraph = semanticFactGraph,
                ),
            )

            assertEquals(workspaceGraph, currentWorkingGraph(snapshot))
            assertEquals("workspaceGraph", currentWorkingGraphSource(snapshot))
        }
    }

    @Test
    fun currentWorkingGraphDoesNotFallbackToProjectionOrReferenceGraphsWhenWorkspaceGraphIsEmpty() {
        val flowchartFullGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "flow-full", type = NodeType.METHOD, title = "flow full")),
        )
        val resourceFullGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "resource-full", type = NodeType.METHOD, title = "resource full")),
        )
        val semanticFactGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "semantic", type = NodeType.METHOD, title = "semantic")),
        )
        val designBaselineGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "baseline", type = NodeType.CLASS, title = "baseline")),
        )
        val emptyGraph = GraphDocument()

        val flowchartSnapshot = snapshot(
            currentSceneId = GraphSceneId.WORKSPACE_FLOWCHART,
            workspaceGraph = emptyGraph,
            semanticFactGraph = semanticFactGraph,
            designBaselineGraph = designBaselineGraph,
            factGraphView = FactGraphViewDocument(visibleGraph = semanticFactGraph, fullGraph = semanticFactGraph),
            flowchartView = FlowchartViewDocument(visibleGraph = emptyGraph, fullGraph = flowchartFullGraph),
            resourceRelationView = ResourceRelationViewDocument(visibleGraph = emptyGraph, fullGraph = resourceFullGraph),
        )
        val resourceSnapshot = snapshot(
            currentSceneId = GraphSceneId.WORKSPACE_RESOURCE_RELATION,
            workspaceGraph = emptyGraph,
            semanticFactGraph = semanticFactGraph,
            designBaselineGraph = designBaselineGraph,
            factGraphView = FactGraphViewDocument(visibleGraph = semanticFactGraph, fullGraph = semanticFactGraph),
            flowchartView = FlowchartViewDocument(visibleGraph = emptyGraph, fullGraph = flowchartFullGraph),
            resourceRelationView = ResourceRelationViewDocument(visibleGraph = emptyGraph, fullGraph = resourceFullGraph),
        )

        assertEquals(emptyGraph, currentWorkingGraph(flowchartSnapshot))
        assertEquals("emptyGraph", currentWorkingGraphSource(flowchartSnapshot))
        assertEquals(emptyGraph, currentWorkingGraph(resourceSnapshot))
        assertEquals("emptyGraph", currentWorkingGraphSource(resourceSnapshot))
    }
}

private fun snapshot(
    currentSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    workspaceGraph: GraphDocument = GraphDocument(),
    semanticFactGraph: GraphDocument = GraphDocument(),
    designBaselineGraph: GraphDocument? = null,
    factGraphView: FactGraphViewDocument = FactGraphViewDocument(),
    flowchartView: FlowchartViewDocument = FlowchartViewDocument(),
    resourceRelationView: ResourceRelationViewDocument = ResourceRelationViewDocument(),
    diffGraph: GraphDocument? = null,
): GraphEditorStateSnapshot {
    val analysisDisplayMode = when (currentSceneId) {
        GraphSceneId.WORKSPACE_FACT -> AnalysisDisplayMode.FACT_GRAPH
        GraphSceneId.WORKSPACE_FLOWCHART -> AnalysisDisplayMode.FLOWCHART
        GraphSceneId.WORKSPACE_RESOURCE_RELATION -> AnalysisDisplayMode.RESOURCE_RELATION_VIEW
        GraphSceneId.DIFF -> AnalysisDisplayMode.FACT_GRAPH
    }
    return GraphEditorStateSnapshot(
        semanticFactGraph = semanticFactGraph,
        workspaceBaseGraph = semanticFactGraph,
        workspaceGraph = workspaceGraph,
        designBaselineGraph = designBaselineGraph,
        factGraphView = factGraphView,
        flowchartView = flowchartView,
        resourceRelationView = resourceRelationView,
        analysisDisplayMode = analysisDisplayMode,
        currentSceneId = currentSceneId,
        previousWorkspaceSceneId = GraphSceneId.WORKSPACE_FACT,
        diffGraph = diffGraph,
    )
}
