package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphEditorStateService
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphSnapshotDocumentsTest {
    @Test
    fun currentVisibleGraphFallsBackFromVisibleToWorkingFactAndBaseline() {
        val visibleGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "visible", type = NodeType.METHOD, title = "visible")),
        )
        val workingGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "working", type = NodeType.METHOD, title = "working")),
        )
        val factGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "fact", type = NodeType.METHOD, title = "fact")),
        )
        val baselineGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "baseline", type = NodeType.CLASS, title = "baseline")),
        )

        assertEquals(
            visibleGraph,
            currentVisibleGraph(
                GraphEditorStateService.Snapshot(
                    visibleGraph = visibleGraph,
                    workingGraph = workingGraph,
                    referenceFactGraph = factGraph,
                    designBaselineGraph = baselineGraph,
                ),
            ),
        )
        assertEquals(
            workingGraph,
            currentVisibleGraph(
                GraphEditorStateService.Snapshot(
                    visibleGraph = null,
                    workingGraph = workingGraph,
                    referenceFactGraph = factGraph,
                    designBaselineGraph = baselineGraph,
                ),
            ),
        )
        assertEquals(
            factGraph,
            currentVisibleGraph(
                GraphEditorStateService.Snapshot(
                    visibleGraph = null,
                    workingGraph = null,
                    referenceFactGraph = factGraph,
                    designBaselineGraph = baselineGraph,
                ),
            ),
        )
        assertEquals(
            baselineGraph,
            currentVisibleGraph(
                GraphEditorStateService.Snapshot(
                    visibleGraph = null,
                    workingGraph = null,
                    referenceFactGraph = null,
                    designBaselineGraph = baselineGraph,
                ),
            ),
        )
    }

    @Test
    fun currentWorkingGraphFallsBackFromWorkingToVisibleFactAndBaseline() {
        val visibleGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "visible", type = NodeType.METHOD, title = "visible")),
        )
        val workingGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "working", type = NodeType.METHOD, title = "working")),
        )
        val factGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "fact", type = NodeType.METHOD, title = "fact")),
        )
        val baselineGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "baseline", type = NodeType.CLASS, title = "baseline")),
        )

        assertEquals(
            workingGraph,
            currentWorkingGraph(
                GraphEditorStateService.Snapshot(
                    visibleGraph = visibleGraph,
                    workingGraph = workingGraph,
                    referenceFactGraph = factGraph,
                    designBaselineGraph = baselineGraph,
                ),
            ),
        )
        assertEquals(
            visibleGraph,
            currentWorkingGraph(
                GraphEditorStateService.Snapshot(
                    visibleGraph = visibleGraph,
                    workingGraph = null,
                    referenceFactGraph = factGraph,
                    designBaselineGraph = baselineGraph,
                ),
            ),
        )
        assertEquals(
            factGraph,
            currentWorkingGraph(
                GraphEditorStateService.Snapshot(
                    visibleGraph = null,
                    workingGraph = null,
                    referenceFactGraph = factGraph,
                    designBaselineGraph = baselineGraph,
                ),
            ),
        )
        assertEquals(
            baselineGraph,
            currentWorkingGraph(
                GraphEditorStateService.Snapshot(
                    visibleGraph = null,
                    workingGraph = null,
                    referenceFactGraph = null,
                    designBaselineGraph = baselineGraph,
                ),
            ),
        )
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

        val sanitizedVisible = currentVisibleGraph(
            GraphEditorStateService.Snapshot(
                visibleGraph = visibleGraph,
                workingGraph = visibleGraph,
            ),
        )
        val sanitizedWorking = currentWorkingGraph(
            GraphEditorStateService.Snapshot(
                visibleGraph = visibleGraph,
                workingGraph = visibleGraph,
            ),
        )

        assertEquals(visibleGraph.nodes.map(GraphNode::id), sanitizedVisible.nodes.map(GraphNode::id))
        assertEquals(visibleGraph.edges.map(GraphEdge::id), sanitizedVisible.edges.map(GraphEdge::id))
        assertEquals(visibleGraph.nodes.map(GraphNode::id), sanitizedWorking.nodes.map(GraphNode::id))
        assertEquals(visibleGraph.edges.map(GraphEdge::id), sanitizedWorking.edges.map(GraphEdge::id))
    }

    @Test
    fun currentWorkingGraphInFlowchartModePrefersWorkingGraphBeforeFlowchartFullGraph() {
        val visibleGraph = GraphDocument(
            nodes = listOf(GraphNode(id = "scope:guard", type = NodeType.METHOD, title = "if")),
        )
        val workingGraph = GraphDocument(
            nodes = listOf(
                GraphNode(id = "scope:guard", type = NodeType.METHOD, title = "if (delete == true)"),
                GraphNode(id = "action:delete-file", type = NodeType.METHOD, title = "delete file"),
            ),
        )
        val fullGraph = GraphDocument(
            nodes = listOf(
                GraphNode(id = "action:guard-condition", type = NodeType.METHOD, title = "condition"),
                GraphNode(id = "scope:guard", type = NodeType.METHOD, title = "if"),
            ),
        )

        assertEquals(
            workingGraph,
            currentWorkingGraph(
                GraphEditorStateService.Snapshot(
                    analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
                    visibleGraph = visibleGraph,
                    workingGraph = workingGraph,
                    flowchartView = FlowchartViewDocument(
                        visibleGraph = visibleGraph,
                        fullGraph = fullGraph,
                        anchorNodeId = "scope:guard",
                    ),
                ),
            ),
        )
        assertEquals(
            "workingGraph",
            currentWorkingGraphSource(
                GraphEditorStateService.Snapshot(
                    analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
                    visibleGraph = visibleGraph,
                    workingGraph = workingGraph,
                    flowchartView = FlowchartViewDocument(
                        visibleGraph = visibleGraph,
                        fullGraph = fullGraph,
                        anchorNodeId = "scope:guard",
                    ),
                ),
            ),
        )
        assertEquals(
            fullGraph,
            currentWorkingGraph(
                GraphEditorStateService.Snapshot(
                    analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
                    visibleGraph = visibleGraph,
                    workingGraph = null,
                    flowchartView = FlowchartViewDocument(
                        visibleGraph = visibleGraph,
                        fullGraph = fullGraph,
                        anchorNodeId = "scope:guard",
                    ),
                ),
            ),
        )
        assertEquals(
            "flowchartView.fullGraph",
            currentWorkingGraphSource(
                GraphEditorStateService.Snapshot(
                    analysisDisplayMode = AnalysisDisplayMode.FLOWCHART,
                    visibleGraph = visibleGraph,
                    workingGraph = null,
                    flowchartView = FlowchartViewDocument(
                        visibleGraph = visibleGraph,
                        fullGraph = fullGraph,
                        anchorNodeId = "scope:guard",
                    ),
                ),
            ),
        )
    }
}
