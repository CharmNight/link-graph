package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.GraphDocument

/**
 * 交互式图投影器：把完整图投影为用户可控规模的交互视图。
 *
 * 投影依据锚点节点决定"以哪个节点为中心"，按上下游深度与每方向邻居数限制做裁剪，
 * 最终结果中只包含锚点周围的可见子图。本类是对 [GraphProjectionKernel] 的薄封装，
 * 让常用配置（节点/边数量、深度、邻居数）可以通过构造参数注入。
 *
 * @param maxVisibleNodes 投影后最多保留的节点数
 * @param maxVisibleEdges 投影后最多保留的边数
 * @param upstreamDepth 向上游回溯的最大深度
 * @param downstreamDepth 向下游遍历的最大深度
 * @param maxNeighborsPerDirection 每个方向上每个节点最多展开多少邻居
 */
class InteractiveGraphProjector(
    maxVisibleNodes: Int = 26,
    maxVisibleEdges: Int = 40,
    upstreamDepth: Int = 2,
    downstreamDepth: Int = 2,
    maxNeighborsPerDirection: Int = 5,
) {
    /** 真正执行投影算法的内核；本类只负责参数透传。 */
    private val kernel = GraphProjectionKernel(
        maxVisibleNodes = maxVisibleNodes,
        maxVisibleEdges = maxVisibleEdges,
        upstreamDepth = upstreamDepth,
        downstreamDepth = downstreamDepth,
        maxNeighborsPerDirection = maxNeighborsPerDirection,
    )

    /**
     * 执行一次投影。
     *
     * @param graph 源图
     * @param anchorNodeId 锚点节点 ID；为 null 时表示无锚点（投影整图摘要）
     * @return 投影结果，包含可见图与统计信息
     */
    fun project(
        graph: GraphDocument,
        anchorNodeId: String? = null,
    ): InteractiveGraphProjection =
        kernel.projectInteractive(graph = graph, anchorNodeId = anchorNodeId)
}
