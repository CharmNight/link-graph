package com.charmnight.linkgraph.ui

import com.charmnight.linkgraph.model.GraphDocument

/**
 * 判断图文档是否包含实质内容（至少有节点/边/补丁之一）。
 * 用于决定是否标记 "workspaceGraph" 来源，避免把空图当作有效来源。
 */
private fun GraphDocument.hasGraphContent(): Boolean = nodes.isNotEmpty() || edges.isNotEmpty() || patch != null

/**
 * 取当前场景下应渲染的"可见图"。
 *
 * - DIFF 场景：取差异图；
 * - 其他场景：按当前展示模式从对应视图（事实/流程/资源/架构/类图/审查）取出可见图；
 * - 未识别模式：返回空图。
 *
 * 这种集中分发让 UI 调用方不必关心"哪种场景该读哪个字段"。
 */
internal fun currentVisibleGraph(snapshot: GraphEditorStateSnapshot): GraphDocument {
    return if (snapshot.currentSceneId == GraphSceneId.DIFF) {
        snapshot.diffGraph ?: GraphDocument()
    } else {
        when (snapshot.currentSceneId.toAnalysisDisplayMode()) {
            com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.FACT_GRAPH -> snapshot.factGraphView.visibleGraph
            com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.FLOWCHART -> snapshot.flowchartView.visibleGraph
            com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.RESOURCE_RELATION_VIEW -> snapshot.resourceRelationView.visibleGraph
            com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.ARCHITECTURE_GRAPH -> snapshot.architectureGraphView.visibleGraph
            com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.CLASS_DIAGRAM -> snapshot.classDiagramView.visibleGraph
            com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode.REVIEW_GRAPH -> snapshot.reviewGraphView.visibleGraph
            // 展示模式缺失时返回空图，避免渲染层处理 null
            null -> GraphDocument()
        }
    }
}

/** 取工作台图（基线 + 草稿）。语义同 [currentWorkingGraph]，保留两个名字以兼容历史调用方。 */
internal fun currentWorkspaceGraph(snapshot: GraphEditorStateSnapshot): GraphDocument = snapshot.workspaceGraph

/** 取当前工作图（包含已应用草稿）。 */
internal fun currentWorkingGraph(snapshot: GraphEditorStateSnapshot): GraphDocument = snapshot.workspaceGraph

/**
 * 取工作图的来源标记字符串。
 * 有内容时返回 "workspaceGraph"，空图返回 "emptyGraph"，
 * 用于埋点与诊断时区分"图确实为空"与"图未加载"。
 */
internal fun currentWorkingGraphSource(snapshot: GraphEditorStateSnapshot): String {
    return if (snapshot.workspaceGraph.hasGraphContent()) "workspaceGraph" else "emptyGraph"
}
