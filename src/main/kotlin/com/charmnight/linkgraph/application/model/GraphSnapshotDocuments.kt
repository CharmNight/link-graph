package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.application.model.WorkflowEditorSnapshot
import com.charmnight.linkgraph.application.model.GraphSceneId
import com.charmnight.linkgraph.application.model.toAnalysisDisplayMode
import com.charmnight.linkgraph.model.GraphDocument

/**
 * 判断图文档是否有实质内容（节点、边或补丁）。
 * 用于区分"图确实为空"和"图未加载"。
 */
private fun GraphDocument.hasGraphContent(): Boolean = nodes.isNotEmpty() || edges.isNotEmpty() || patch != null

/**
 * 取当前场景下应渲染的"可见图"。
 *
 * - DIFF 场景：取差异图；
 * - 其他场景：按当前展示模式从对应视图取出可见图；
 * - 未识别模式：返回空图。
 *
 * 这种集中分发让上层调用方不必关心"哪种场景读哪个字段"。
 */
internal fun currentVisibleGraph(snapshot: WorkflowEditorSnapshot): GraphDocument {
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

/** 取工作台图（基线 + 草稿）。 */
internal fun currentWorkspaceGraph(snapshot: WorkflowEditorSnapshot): GraphDocument = snapshot.workspaceGraph

/** 取当前工作图（基线 + 已应用草稿）。 */
internal fun currentWorkingGraph(snapshot: WorkflowEditorSnapshot): GraphDocument = snapshot.workspaceGraph

/**
 * 取工作图的来源标记字符串。
 * 有内容时返回 "workspaceGraph"，空图返回 "emptyGraph"，
 * 用于埋点与诊断时区分"图确实为空"与"图未加载"。
 */
internal fun currentWorkingGraphSource(snapshot: WorkflowEditorSnapshot): String {
    return if (snapshot.workspaceGraph.hasGraphContent()) "workspaceGraph" else "emptyGraph"
}
