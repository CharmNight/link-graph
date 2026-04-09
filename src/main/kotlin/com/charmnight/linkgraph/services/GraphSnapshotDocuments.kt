package com.charmnight.linkgraph.services

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.ui.GraphEditorStateService

/**
 * 从编辑器快照中解析当前可见图，统一回退优先级，避免不同流程各自维护副本。
 */
internal fun currentVisibleGraph(snapshot: GraphEditorStateService.Snapshot): GraphDocument {
    return snapshot.visibleGraph
        ?: snapshot.workingGraph
        ?: snapshot.referenceFactGraph
        ?: snapshot.designBaselineGraph
        ?: GraphDocument()
}

/**
 * 从编辑器快照中解析当前可编辑工作图。
 */
internal fun currentWorkingGraph(snapshot: GraphEditorStateService.Snapshot): GraphDocument {
    return snapshot.workingGraph
        ?: snapshot.visibleGraph
        ?: snapshot.referenceFactGraph
        ?: snapshot.designBaselineGraph
        ?: GraphDocument()
}
