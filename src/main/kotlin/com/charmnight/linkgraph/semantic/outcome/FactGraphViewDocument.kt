package com.charmnight.linkgraph.semantic.outcome

import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.presentation.GraphViewPresentation
import com.charmnight.linkgraph.model.GraphDocument

/**
 * 事实图视图的摘要信息。
 *
 * 摘要记录可见节点数、被隐藏数量、是否截断等面向用户的统计，
 * 让 UI 在不重新计算图的情况下就能给出提示。
 */
data class FactGraphSummary(
    /** 锚点节点标题（通常是当前主题节点的名字）；无锚点时为 null。 */
    val anchorTitle: String? = null,
    /** 实际渲染到画布上的节点数。 */
    val visibleNodeCount: Int = 0,
    /** 完整图中的节点总数。 */
    val fullNodeCount: Int = 0,
    /** 因预算被隐藏的节点数。 */
    val hiddenNodeCount: Int = 0,
    /** 因预算被隐藏的边数。 */
    val hiddenEdgeCount: Int = 0,
    /** 是否因为预算限制而截断。true 表示还有更多内容未展示。 */
    val truncated: Boolean = false,
)

/**
 * 事实图视图文档：事实图视图的完整数据载体。
 *
 * 把可见图、完整图、锚点、摘要、投影索引、呈现规范打包在一起，
 * 让 UI 一次性拿到渲染事实图视图所需的全部上下文。
 */
data class FactGraphViewDocument(
    /** 实际渲染到画布上的图（可能被投影裁剪）。 */
    val visibleGraph: GraphDocument = GraphDocument(),
    /** 完整的源图，未做裁剪；用于"显示全部"等场景。 */
    val fullGraph: GraphDocument = GraphDocument(),
    /** 视图锚点节点 ID；无锚点时为 null。 */
    val anchorNodeId: String? = null,
    /** 面向 UI 的统计摘要。 */
    val summary: FactGraphSummary = FactGraphSummary(),
    /** 投影索引：声明每个节点/边的可编辑命令集合。 */
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
    /** 视图呈现规范（泳道、隐藏桶等）。 */
    val presentation: GraphViewPresentation = GraphViewPresentation(),
)
