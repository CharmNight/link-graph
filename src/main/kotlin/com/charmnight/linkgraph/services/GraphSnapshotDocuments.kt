package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.GraphEditorStateService

/**
 * 从编辑器快照中解析当前可见图，统一回退优先级，避免不同流程各自维护副本。
 */
internal fun currentVisibleGraph(snapshot: GraphEditorStateService.Snapshot): GraphDocument {
    val modeVisibleGraph = when (snapshot.analysisDisplayMode) {
        AnalysisDisplayMode.FACT_GRAPH -> snapshot.factGraphView?.visibleGraph
        AnalysisDisplayMode.FLOWCHART -> snapshot.flowchartView?.visibleGraph
        AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> snapshot.resourceRelationView?.visibleGraph
    }
    return (
        modeVisibleGraph
            ?: snapshot.visibleGraph
            ?: snapshot.workingGraph
            ?: snapshot.referenceFactGraph
            ?: snapshot.designBaselineGraph
            ?: GraphDocument()
        )
}

/**
 * 从编辑器快照中解析当前可编辑工作图。
 */
internal fun currentWorkingGraph(snapshot: GraphEditorStateService.Snapshot): GraphDocument {
    return (
        snapshot.workingGraph
            ?: if (snapshot.analysisDisplayMode == AnalysisDisplayMode.FLOWCHART) {
            snapshot.flowchartView?.fullGraph
        } else {
            null
        }
            ?: snapshot.visibleGraph
            ?: snapshot.referenceFactGraph
            ?: snapshot.designBaselineGraph
            ?: GraphDocument()
        )
}

internal fun currentWorkingGraphSource(snapshot: GraphEditorStateService.Snapshot): String {
    if (snapshot.workingGraph != null) {
        return "workingGraph"
    }
    if (snapshot.analysisDisplayMode == AnalysisDisplayMode.FLOWCHART) {
        val flowchartFullGraph = snapshot.flowchartView?.fullGraph
        if (flowchartFullGraph != null && (flowchartFullGraph.nodes.isNotEmpty() || flowchartFullGraph.edges.isNotEmpty())) {
            return "flowchartView.fullGraph"
        }
    }
    return when {
        snapshot.visibleGraph != null -> "visibleGraph"
        snapshot.referenceFactGraph != null -> "referenceFactGraph"
        snapshot.designBaselineGraph != null -> "designBaselineGraph"
        else -> "emptyGraph"
    }
}
