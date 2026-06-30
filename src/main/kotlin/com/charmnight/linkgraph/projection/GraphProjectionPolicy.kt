package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

/**
 * 图投影的策略参数。
 *
 * 把投影器运行所需的全部可调参数集中在一个 data class 里，
 * 让不同视图（事实图、类图、流程图等）可以各自定义稳定策略，
 * 切换策略时无需修改投影器代码。
 */
data class GraphProjectionPolicy(
    /** 投影后允许的最多可见节点数。 */
    val maxVisibleNodes: Int,
    /** 投影后允许的最多可见边数。 */
    val maxVisibleEdges: Int,
    /** 视图锚点节点 ID；为 null 表示无锚点。 */
    val anchorNodeId: String? = null,
    /** 种子节点类型集合：投影时优先保留这些类型的节点。 */
    val seedNodeTypes: Set<NodeType> = emptySet(),
    /** 投影方向策略：双向、仅上游、仅下游。 */
    val directionPolicy: GraphProjectionDirectionPolicy = GraphProjectionDirectionPolicy.BIDIRECTIONAL,
    /** 角色元数据键名；用于按角色分配配额。 */
    val roleMetadataKey: String? = null,
    /** 各角色的配额列表。 */
    val roleQuotas: List<GraphWindowRoleQuota> = emptyList(),
    /** 是否启用"溢出摘要"节点（被裁掉的节点合并为一个虚拟节点）。 */
    val enableOverflowSummary: Boolean = false,
    /** 是否填充无连接节点（让孤岛节点也展示出来）。 */
    val fillDisconnectedNodes: Boolean = true,
    /** 溢出节点的所属上下文标记。 */
    val overflowOwnerContext: String = "graph-window",
    /** 溢出节点与原节点之间使用的边类型。 */
    val overflowEdgeType: EdgeType = EdgeType.CALL,
    /** 节点优先级计算函数；值越大越优先保留。 */
    val nodePriority: (GraphNode) -> Int = { 0 },
    /** 边优先级计算函数。 */
    val edgePriority: (GraphEdge) -> Int = { 0 },
)

/**
 * 投影方向策略。
 *
 * - [BIDIRECTIONAL]：双向遍历（默认）；
 * - [UPSTREAM]：仅向上游回溯；
 * - [DOWNSTREAM]：仅向下游遍历。
 */
enum class GraphProjectionDirectionPolicy {
    BIDIRECTIONAL,
    UPSTREAM,
    DOWNSTREAM,
}
