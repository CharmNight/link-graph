package com.charmnight.linkgraph.projection

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode

/**
 * 投影后被隐藏的节点/边数量统计。
 *
 * @property hiddenNodeCount 被隐藏的节点数
 * @property hiddenEdgeCount 被隐藏的边数
 */
data class GraphProjectionHiddenCounts(
    val hiddenNodeCount: Int,
    val hiddenEdgeCount: Int,
) {
    /** 是否有任何内容被隐藏；任一计数大于 0 即为 true。 */
    val truncated: Boolean get() = hiddenNodeCount > 0 || hiddenEdgeCount > 0
}

/**
 * 计算两份图之间的隐藏规模。
 *
 * @param visibleGraph 实际渲染的图（裁剪后）
 * @param fullGraph 完整图
 * @return 隐藏节点数与边数
 */
fun graphProjectionHiddenCounts(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
): GraphProjectionHiddenCounts {
    val hiddenNodes = graphProjectionHiddenNodes(visibleGraph = visibleGraph, fullGraph = fullGraph)
    val hiddenEdges = graphProjectionHiddenEdges(visibleGraph = visibleGraph, fullGraph = fullGraph)
    return GraphProjectionHiddenCounts(
        hiddenNodeCount = hiddenNodes.size,
        hiddenEdgeCount = hiddenEdges.size,
    )
}

/**
 * 取被隐藏的节点列表。
 * 只考虑"原本在完整图里、但没出现在可见图里"的节点，
 * 可见图中的合成节点（例如溢出摘要）不会算作原始节点。
 */
fun graphProjectionHiddenNodes(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
): List<GraphNode> {
    // 可见图中的原始节点 ID = 可见图节点中、ID 在完整图节点集合内的那部分
    val visibleOriginalNodeIds = visibleGraph.nodes
        .asSequence()
        .map(GraphNode::id)
        .filter(fullGraph.nodes.mapTo(linkedSetOf(), GraphNode::id)::contains)
        .toSet()
    // 隐藏节点 = 完整图节点中、ID 不在可见原始集合内的那部分
    return fullGraph.nodes.filter { node -> node.id !in visibleOriginalNodeIds }
}

/**
 * 取被隐藏的边列表。
 * 与节点类似，但额外考虑"投影边可能代表多条原始边"（通过 UML_AGGREGATE_EDGE_IDS 元数据声明）。
 */
fun graphProjectionHiddenEdges(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
): List<GraphEdge> {
    val fullEdgeIds = fullGraph.edges.mapTo(linkedSetOf(), GraphEdge::id)
    // 可见图中的原始边 ID = 投影边展开后、ID 在完整图内的那部分
    val visibleOriginalEdgeIds = visibleGraph.edges
        .asSequence()
        .flatMap { edge -> edge.projectedSourceEdgeIds().asSequence() }
        .filter(fullEdgeIds::contains)
        .toSet()
    return fullGraph.edges.filter { edge -> edge.id !in visibleOriginalEdgeIds }
}

/**
 * 取一条边对应的原始边 ID 列表。
 *
 * 投影过程可能把多条同类原始边合并为一条（例如 UML 聚合关系），
 * 通过元数据中的 UML_AGGREGATE_EDGE_IDS 字段声明。
 * 没有该字段时，边就只代表它自己。
 */
fun GraphEdge.projectedSourceEdgeIds(): List<String> =
    metadata[GraphProjectionMetadata.SourceEdges.UML_AGGREGATE_EDGE_IDS]
        ?.split(',')
        ?.mapNotNull { edgeId -> edgeId.trim().takeIf(String::isNotBlank) }
        ?.takeIf(List<String>::isNotEmpty)
        ?: listOf(id)
