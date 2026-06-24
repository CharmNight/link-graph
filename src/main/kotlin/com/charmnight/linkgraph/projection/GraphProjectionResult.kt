package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.GraphDocument

/**
 * 图投影的结果。
 *
 * 把投影产出的可见图、完整图以及各类统计打包返回。
 * 上层根据这些字段决定是否提示"已截断"、是否提供"显示更多"按钮等。
 */
data class GraphProjectionResult(
    /** 实际渲染的图（裁剪后）。 */
    val visibleGraph: GraphDocument,
    /** 完整的源图（未裁剪）。 */
    val fullGraph: GraphDocument,
    /** 被隐藏的节点数。 */
    val hiddenNodeCount: Int,
    /** 被隐藏的边数。 */
    val hiddenEdgeCount: Int,
    /** 按层（layer）统计的隐藏数量。 */
    val hiddenLayerCounts: Map<String, Int> = emptyMap(),
    /** 按层统计的折叠数量。 */
    val collapsedLayerCounts: Map<String, Int> = emptyMap(),
    /** 是否因预算被截断。只要节点或边被隐藏就视为截断。 */
    val truncated: Boolean = hiddenNodeCount > 0 || hiddenEdgeCount > 0,
    /** 投影后的边 ID 到原始来源边 ID 列表的映射（多条原始边可能合并为一条）。 */
    val projectedSourceEdgeIds: Map<String, List<String>> = emptyMap(),
)
