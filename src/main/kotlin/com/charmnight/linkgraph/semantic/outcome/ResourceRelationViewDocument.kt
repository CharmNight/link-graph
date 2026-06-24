package com.charmnight.linkgraph.semantic.outcome

import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.model.GraphDocument

/**
 * 资源关系视图的摘要信息。
 *
 * 摘要记录节点数、关系数、资源数等面向 UI 的统计，
 * 以及当视图无法生成时的兜底原因（fallbackReason），便于给用户解释。
 */
data class ResourceRelationSummary(
    /** 可见节点数。 */
    val visibleNodeCount: Int = 0,
    /** 关系（边）数。 */
    val relationCount: Int = 0,
    /** 资源节点数。 */
    val resourceCount: Int = 0,
    /** 当视图无法生成时的兜底原因；默认 NONE 表示一切正常。 */
    val fallbackReason: String = "NONE",
    /** 各泳道节点数量；让 UI 展示泳道规模。 */
    val laneCounts: Map<String, Int> = emptyMap(),
)

/**
 * 资源关系视图文档：资源关系视图的完整数据载体。
 *
 * 把可见图、完整图、锚点、摘要、投影索引打包在一起，
 * 让 UI 一次性拿到渲染资源关系视图所需的全部上下文。
 */
data class ResourceRelationViewDocument(
    /** 实际渲染到画布上的图。 */
    val visibleGraph: GraphDocument = GraphDocument(),
    /** 完整的源图；用于"显示全部"等场景。 */
    val fullGraph: GraphDocument = GraphDocument(),
    /** 视图锚点节点 ID；为 null 表示无锚点。 */
    val anchorNodeId: String? = null,
    /** 面向 UI 的统计摘要。 */
    val summary: ResourceRelationSummary = ResourceRelationSummary(),
    /** 投影索引：声明每个节点/边的可编辑命令集合。 */
    val projectionIndex: GraphProjectionIndex = GraphProjectionIndex.EMPTY,
)
