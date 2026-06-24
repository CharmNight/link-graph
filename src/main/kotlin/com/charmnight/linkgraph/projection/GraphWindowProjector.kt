package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

/**
 * 图窗口投影策略。
 *
 * 与 [GraphProjectionPolicy] 类似但字段更少，面向窗口投影场景。
 * 后续可以与 GraphProjectionPolicy 合并。
 */
data class GraphWindowPolicy(
    /** 最多保留的可见节点数。 */
    val maxVisibleNodes: Int,
    /** 最多保留的可见边数。 */
    val maxVisibleEdges: Int,
    /** 是否启用溢出摘要节点。 */
    val enableOverflowSummary: Boolean = false,
    /** 是否填充无连接节点。 */
    val fillDisconnectedNodes: Boolean = true,
)

/**
 * 角色配额：限制某角色节点在窗口中的最大数量。
 *
 * @property role 角色名（与节点 metadata 中的某个 key 对应）
 * @property maxNodes 该角色最多保留的节点数
 */
data class GraphWindowRoleQuota(
    val role: String,
    val maxNodes: Int,
)

/**
 * 图窗口投影结果。
 *
 * @property graph 投影后的可见图
 * @property truncated 是否被截断
 * @property hiddenNodeCount 被隐藏的节点数
 * @property hiddenEdgeCount 被隐藏的边数
 */
data class GraphWindowProjection(
    val graph: GraphDocument,
    val truncated: Boolean,
    val hiddenNodeCount: Int,
    val hiddenEdgeCount: Int,
)

/**
 * 图窗口投影器。
 *
 * 把完整图按"以锚点为中心的窗口"投影为可见子图。
 * 内部委托给 [GraphProjectionKernel]，本类只做参数透传与结果转换。
 *
 * @param kernel 真正执行投影算法的内核
 */
class GraphWindowProjector(
    private val kernel: GraphProjectionKernel = GraphProjectionKernel(),
) {
    /**
     * 执行窗口投影。
     *
     * @param graph 源图
     * @param policy 投影策略
     * @param anchorNodeId 锚点节点 ID
     * @param seedNodeTypes 种子节点类型集合
     * @param roleMetadataKey 角色元数据 key
     * @param roleQuotas 角色配额列表
     * @param nodePriority 节点优先级函数
     * @param edgePriority 边优先级函数
     * @param overflowOwnerContext 溢出节点的 owner 上下文
     * @param overflowEdgeType 溢出节点与原节点之间的边类型
     */
    fun project(
        graph: GraphDocument,
        policy: GraphWindowPolicy,
        anchorNodeId: String? = null,
        seedNodeTypes: Set<NodeType> = emptySet(),
        roleMetadataKey: String? = null,
        roleQuotas: List<GraphWindowRoleQuota> = emptyList(),
        nodePriority: (GraphNode) -> Int = { 0 },
        edgePriority: (GraphEdge) -> Int = { 0 },
        overflowOwnerContext: String = "graph-window",
        overflowEdgeType: EdgeType = EdgeType.CALL,
    ): GraphWindowProjection {
        // 委托给内核，把本类的参数转换为 GraphProjectionPolicy
        val result = kernel.projectWindow(
            graph = graph,
            policy = GraphProjectionPolicy(
                maxVisibleNodes = policy.maxVisibleNodes,
                maxVisibleEdges = policy.maxVisibleEdges,
                anchorNodeId = anchorNodeId,
                seedNodeTypes = seedNodeTypes,
                roleMetadataKey = roleMetadataKey,
                roleQuotas = roleQuotas,
                enableOverflowSummary = policy.enableOverflowSummary,
                fillDisconnectedNodes = policy.fillDisconnectedNodes,
                overflowOwnerContext = overflowOwnerContext,
                overflowEdgeType = overflowEdgeType,
                nodePriority = nodePriority,
                edgePriority = edgePriority,
            ),
        )
        return GraphWindowProjection(
            graph = result.visibleGraph,
            truncated = result.truncated,
            hiddenNodeCount = result.hiddenNodeCount,
            hiddenEdgeCount = result.hiddenEdgeCount,
        )
    }
}
