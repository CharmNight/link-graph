package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.architecture.ArchitectureGraphResult
import com.charmnight.linkgraph.architecture.ClassDiagramResult
import com.charmnight.linkgraph.review.ReviewGraphResult
import com.charmnight.linkgraph.semantic.outcome.FactGraphViewDocument
import com.charmnight.linkgraph.semantic.outcome.FlowchartViewDocument
import com.charmnight.linkgraph.semantic.outcome.ResourceRelationViewDocument

/** 布局位置别名；透出 [com.charmnight.linkgraph.application.model.GraphLayoutPosition]。 */
typealias GraphLayoutPosition = com.charmnight.linkgraph.application.model.GraphLayoutPosition

/**
 * 整张图的布局状态：节点 ID 到位置的映射。
 */
data class GraphLayoutState(
    val positions: Map<String, GraphLayoutPosition> = emptyMap(),
)

/**
 * 图编辑器快照。
 *
 * 把所有视图（事实/流程/资源/架构/类图/审查）的状态与版本号打包为不可变快照，
 * 让 UI、桥接层等消费方一次性拿到完整状态。
 *
 * 与 [GraphEditorStateSnapshot] 区别：本类只包含渲染相关字段，不包含交互状态
 * （例如选中节点、操作反馈等）。
 */
data class GraphEditorSnapshot(
    /** 当前可见图（实际渲染的版本）。 */
    val visibleGraph: GraphDocument = GraphDocument(),
    /** 工作台图（基线 + 已应用草稿）。 */
    val workspaceGraph: GraphDocument = GraphDocument(),
    /** 工作台基线图（不含草稿）。 */
    val workspaceBaseGraph: GraphDocument = GraphDocument(),
    /** 语义事实图。 */
    val semanticFactGraph: GraphDocument = GraphDocument(),
    /** 设计基线图；未导入时为 null。 */
    val designBaselineGraph: GraphDocument? = null,
    /** 事实图视图。 */
    val factGraphView: FactGraphViewDocument = FactGraphViewDocument(),
    /** 流程图视图。 */
    val flowchartView: FlowchartViewDocument = FlowchartViewDocument(),
    /** 资源关系图视图。 */
    val resourceRelationView: ResourceRelationViewDocument = ResourceRelationViewDocument(),
    /** 架构图视图。 */
    val architectureGraphView: ArchitectureGraphResult = ArchitectureGraphResult(),
    /** 类图视图。 */
    val classDiagramView: ClassDiagramResult = ClassDiagramResult(),
    /** 审查图视图。 */
    val reviewGraphView: ReviewGraphResult = ReviewGraphResult(),
    /** 当前展示模式。 */
    val analysisDisplayMode: com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode =
        com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.FACT_GRAPH,
    /** 当前场景 ID。 */
    val currentSceneId: GraphSceneId = GraphSceneId.WORKSPACE_FACT,
    /** 各场景的本地状态（选中节点、布局等）。 */
    val sceneStates: Map<GraphSceneId, GraphSceneState> = defaultGraphSceneStates(),
    /** 语义分析版本号。 */
    val semanticRevision: Long = 0,
    /** 工作台图版本号。 */
    val workspaceRevision: Long = 0,
    /** 整体快照版本号。 */
    val snapshotRevision: Long = 0,
)

/**
 * 把完整状态快照投影为渲染用的编辑器快照。
 * 只取渲染相关字段，丢弃交互状态，减小前端桥接载荷规模。
 */
fun GraphEditorStateSnapshot.editorSnapshot(): GraphEditorSnapshot {
    return GraphEditorSnapshot(
        visibleGraph = currentVisibleGraph(this),
        workspaceGraph = currentWorkspaceGraph(this),
        workspaceBaseGraph = workspaceBaseGraph,
        semanticFactGraph = semanticFactGraph,
        designBaselineGraph = designBaselineGraph,
        factGraphView = factGraphView,
        flowchartView = flowchartView,
        resourceRelationView = resourceRelationView,
        architectureGraphView = architectureGraphView,
        classDiagramView = classDiagramView,
        reviewGraphView = reviewGraphView,
        analysisDisplayMode = analysisDisplayMode,
        currentSceneId = currentSceneId,
        sceneStates = sceneStates,
        semanticRevision = semanticRevision,
        workspaceRevision = workspaceRevision,
        snapshotRevision = snapshotRevision,
    )
}
