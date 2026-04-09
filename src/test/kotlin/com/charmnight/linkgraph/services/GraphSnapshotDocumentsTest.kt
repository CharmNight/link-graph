package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.ui.GraphEditorStateService
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
}
