package com.charmnight.linkgraph.projection.business

import com.charmnight.linkgraph.projection.GraphWindowPolicy
import com.charmnight.linkgraph.projection.GraphWindowProjector
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

/**
 * 视口投影策略：限制可见图规模的参数集合。
 *
 * 与图窗口投影器（[GraphWindowProjector]）的 [GraphWindowPolicy] 类似，
 * 但面向业务层暴露更简洁的字段。
 */
data class GraphViewportPolicy(
    /** 最多保留的可见节点数。 */
    val maxVisibleNodes: Int = 96,
    /** 最多保留的可见边数。 */
    val maxVisibleEdges: Int = 144,
    /** 是否启用溢出摘要节点。 */
    val enableOverflowSummary: Boolean = false,
)

/**
 * 视口投影结果。
 *
 * - [graph] 是裁剪后的可见图；
 * - 后三个字段描述裁掉的规模，让 UI 可以给出"还有 N 项被隐藏"提示。
 */
internal data class VisibleGraphWindow(
    val graph: GraphDocument,
    val truncated: Boolean,
    val hiddenNodeCount: Int,
    val hiddenEdgeCount: Int,
)

/**
 * 对图应用视口投影：按节点优先级与锚点保留一个合理规模的子图。
 *
 * 这是业务层调用投影的便捷入口，内部委托给通用 [GraphWindowProjector]。
 * overflow owner 固定为 "indexed-window"，便于 UI 区分这是索引视图的裁剪。
 */
internal fun GraphDocument.visibleWindow(
    policy: GraphViewportPolicy,
    anchorNodeId: String? = null,
    seedNodeTypes: Set<NodeType> = emptySet(),
    nodePriority: (GraphNode) -> Int = { 0 },
    edgePriority: (GraphEdge) -> Int = { 0 },
): VisibleGraphWindow {
    val projection = GraphWindowProjector().project(
        graph = this,
        policy = GraphWindowPolicy(
            maxVisibleNodes = policy.maxVisibleNodes,
            maxVisibleEdges = policy.maxVisibleEdges,
            enableOverflowSummary = policy.enableOverflowSummary,
        ),
        anchorNodeId = anchorNodeId,
        seedNodeTypes = seedNodeTypes,
        nodePriority = nodePriority,
        edgePriority = edgePriority,
        overflowOwnerContext = "indexed-window",
    )
    return VisibleGraphWindow(
        graph = projection.graph,
        truncated = projection.truncated,
        hiddenNodeCount = projection.hiddenNodeCount,
        hiddenEdgeCount = projection.hiddenEdgeCount,
    )
}
