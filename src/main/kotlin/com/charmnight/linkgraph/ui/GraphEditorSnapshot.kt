package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.services.currentVisibleGraph
import com.charmnight.linkgraph.services.currentWorkingGraph
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode
import com.charmnight.linkgraph.ui.view.FactGraphViewDocument
import com.charmnight.linkgraph.ui.view.FlowchartViewDocument
import com.charmnight.linkgraph.ui.view.ResourceRelationViewDocument

/**
 * 表示图布局中单个节点的位置。
 */
data class GraphLayoutPosition(
    /** 保存节点的横坐标。 */
    val x: Double,
    /** 保存节点的纵坐标。 */
    val y: Double,
)

/**
 * 表示整张图的布局状态。
 */
data class GraphLayoutState(
    /** 保存节点标识到布局位置的映射。 */
    val positions: Map<String, GraphLayoutPosition> = emptyMap(),
)

/**
 * 表示发送给前端编辑器的完整快照。
 */
data class GraphEditorSnapshot(
    /** 保存当前需要展示给用户的图。 */
    val visibleGraph: GraphDocument = GraphDocument(),
    /** 保存当前工作中的可编辑图。 */
    val workingGraph: GraphDocument = GraphDocument(),
    /** 保存参考事实图。 */
    val referenceFactGraph: GraphDocument? = null,
    /** 保存设计基线图。 */
    val designBaselineGraph: GraphDocument? = null,
    /** 保存事实链路视图专用文档。 */
    val factGraphView: FactGraphViewDocument? = null,
    /** 保存流程图视图专用文档。 */
    val flowchartView: FlowchartViewDocument? = null,
    /** 保存资源关系视图专用文档。 */
    val resourceRelationView: ResourceRelationViewDocument? = null,
    /** 保存当前分析展示模式。 */
    val analysisDisplayMode: AnalysisDisplayMode = AnalysisDisplayMode.FACT_GRAPH,
    /** 保存当前布局状态。 */
    val layoutState: GraphLayoutState = GraphLayoutState(),
    /** 保存语义分析修订号。 */
    val semanticRevision: Long = 0,
    /** 保存布局修订号。 */
    val layoutRevision: Long = 0,
    /** 保存整体快照修订号。 */
    val snapshotRevision: Long = 0,
)

/**
 * 将状态服务内部快照转换为前端桥接层使用的快照模型。
 */
fun GraphEditorStateService.Snapshot.editorSnapshot(): GraphEditorSnapshot {
    return GraphEditorSnapshot(
        visibleGraph = currentVisibleGraph(this),
        workingGraph = currentWorkingGraph(this),
        referenceFactGraph = factGraphView?.fullGraph ?: referenceFactGraph,
        designBaselineGraph = designBaselineGraph,
        factGraphView = factGraphView,
        flowchartView = flowchartView,
        resourceRelationView = resourceRelationView,
        analysisDisplayMode = analysisDisplayMode,
        layoutState = layoutState,
        semanticRevision = semanticRevision,
        layoutRevision = layoutRevision,
        snapshotRevision = snapshotRevision,
    )
}
