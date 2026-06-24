package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.presentation.GraphHiddenBucket

/**
 * 把"被投影隐藏的节点"按 bucket 分组，构造 UI 上的隐藏桶列表。
 *
 * 投影过程会把不可见的节点裁掉，但用户可能想"按分类查看被隐藏了哪些"，
 * 本投影器把被隐藏的节点按 bucket 分组并附带相关边 ID，
 * 让 UI 可以展示"还有 N 个 SQL 节点被隐藏"这类提示。
 */
class GraphHiddenBucketProjector {
    /**
     * @param visibleGraph 实际渲染的图
     * @param fullGraph 完整图
     * @param bucketForNode 给节点分配 bucket ID 的函数（例如按类型分桶）
     * @param labelForBucket 把 bucket ID 转为人类可读标签的函数
     * @return 隐藏桶列表，按 bucket ID 排序保证多次调用顺序稳定
     */
    fun project(
        visibleGraph: GraphDocument,
        fullGraph: GraphDocument,
        bucketForNode: (GraphNode) -> String,
        labelForBucket: (String) -> String,
    ): List<GraphHiddenBucket> {
        // 收集可见节点/边的 ID，便于后续过滤
        val visibleNodeIds = visibleGraph.nodes.mapTo(linkedSetOf(), GraphNode::id)
        val visibleEdgeIds = visibleGraph.edges.mapTo(linkedSetOf()) { edge -> edge.id }
        // 隐藏边 = 完整图边 - 可见边
        val hiddenEdges = fullGraph.edges.filter { edge -> edge.id !in visibleEdgeIds }
        return fullGraph.nodes
            // 隐藏节点 = 完整图节点 - 可见节点
            .filter { node -> node.id !in visibleNodeIds }
            // 按 bucket 分组
            .groupBy(bucketForNode)
            .map { (bucketId, nodes) ->
                val nodeIds = nodes.map(GraphNode::id)
                val nodeIdSet = nodeIds.toSet()
                GraphHiddenBucket(
                    id = bucketId,
                    label = labelForBucket(bucketId),
                    count = nodes.size,
                    nodeIds = nodeIds,
                    // 该 bucket 关联的边：任一端点落在 bucket 内即算
                    edgeIds = hiddenEdges
                        .filter { edge -> edge.fromNodeId in nodeIdSet || edge.toNodeId in nodeIdSet }
                        .map { edge -> edge.id },
                )
            }
            // 按 ID 排序，避免不同刷新顺序导致 UI 跳动
            .sortedWith(compareBy(GraphHiddenBucket::id))
    }
}
