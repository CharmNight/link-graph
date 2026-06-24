package com.charmnight.linkgraph.semantic.outcome

import com.charmnight.linkgraph.application.model.GraphEditCommandKind
import com.charmnight.linkgraph.application.model.GraphProjectionEdgeMapping
import com.charmnight.linkgraph.application.model.GraphProjectionIndex
import com.charmnight.linkgraph.application.model.GraphProjectionMappingKind
import com.charmnight.linkgraph.application.model.GraphProjectionNodeMapping
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.projection.GraphProjectionMetadata

/** 节点元数据中记录"投影后被合并的原始节点 ID 列表"的字段名。 */
private const val FLOWCHART_ALIAS_IDS_KEY = "flowchart.projectedFromNodeIds"

/**
 * 构造与图自身完全一致的投影索引。
 *
 * 用于没有发生合并/裁剪的场景，每个节点的映射都是精确的、可编辑的。
 */
internal fun exactGraphProjectionIndex(graph: GraphDocument): GraphProjectionIndex =
    GraphProjectionIndex(
        nodeMappings = graph.nodes.associate { node ->
            node.id to GraphProjectionNodeMapping(
                projectedNodeId = node.id,
                mappingKind = nodeMappingKind(node, listOf(node.id)),
                canonicalNodeIds = listOf(node.id),
                editableCommandKinds = editableNodeCommands(node),
            )
        },
        edgeMappings = graph.edges.associate { edge ->
            edge.id to GraphProjectionEdgeMapping(
                projectedEdgeId = edge.id,
                mappingKind = GraphProjectionMappingKind.EXACT,
                canonicalEdgeIds = listOf(edge.id),
                editableCommandKinds = editableEdgeCommands(edge),
            )
        },
    )

/**
 * 为"经过裁剪/合并后的可见图"构造投影索引。
 *
 * 该索引描述每个可见节点/边如何映射回完整图中的原始节点/边，
 * 并标注每条映射是否允许编辑。被裁剪/合成/合并的映射通常被标记为只读。
 */
internal fun graphProjectionIndexForVisibleGraph(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
): GraphProjectionIndex {
    /** 完整图中所有边 ID 的集合，用于判断可见边是否真实存在。 */
    val fullEdgeIds = fullGraph.edges.mapTo(linkedSetOf()) { edge -> edge.id }
    /** 可见图中所有因溢出裁剪而保留的占位节点 ID 集合。 */
    val overflowNodeIds = visibleGraph.nodes
        .filter(::isOverflowNode)
        .mapTo(linkedSetOf()) { node -> node.id }
    return GraphProjectionIndex(
        nodeMappings = visibleGraph.nodes.associate { node ->
            val aliasIds = projectedAliasNodeIds(node)
            val canonicalIds = listOf(node.id) + aliasIds
            val mappingKind = nodeMappingKind(node, canonicalIds)
            node.id to GraphProjectionNodeMapping(
                projectedNodeId = node.id,
                mappingKind = mappingKind,
                canonicalNodeIds = if (mappingKind == GraphProjectionMappingKind.OVERFLOW_READONLY) {
                    emptyList()
                } else {
                    canonicalIds
                },
                editableCommandKinds = if (mappingKind == GraphProjectionMappingKind.EXACT) {
                    editableNodeCommands(node)
                } else {
                    emptySet()
                },
            )
        },
        edgeMappings = visibleGraph.edges.associate { edge ->
            val mappingKind = edgeMappingKind(edge, fullEdgeIds, overflowNodeIds)
            edge.id to GraphProjectionEdgeMapping(
                projectedEdgeId = edge.id,
                mappingKind = mappingKind,
                canonicalEdgeIds = if (mappingKind == GraphProjectionMappingKind.EXACT) listOf(edge.id) else emptyList(),
                editableCommandKinds = if (mappingKind == GraphProjectionMappingKind.EXACT) {
                    editableEdgeCommands(edge)
                } else {
                    emptySet()
                },
            )
        },
    )
}

/** 读取节点元数据中记录的"投影后合并自的原始节点 ID 列表"。 */
private fun projectedAliasNodeIds(node: GraphNode): List<String> =
    node.metadata[FLOWCHART_ALIAS_IDS_KEY]
        ?.split(',')
        ?.mapNotNull { nodeId -> nodeId.trim().takeIf(String::isNotBlank) }
        .orEmpty()

/** 根据节点是否溢出、是否合并了多个原始节点，决定其映射类型。 */
private fun nodeMappingKind(
    node: GraphNode,
    canonicalIds: List<String>,
): GraphProjectionMappingKind =
    when {
        isOverflowNode(node) -> GraphProjectionMappingKind.OVERFLOW_READONLY
        canonicalIds.distinct().size > 1 -> GraphProjectionMappingKind.MERGED_ALIAS
        else -> GraphProjectionMappingKind.EXACT
    }

/** 根据边的来源/目标是否被裁剪、是否为合成边、是否真实存在，决定其映射类型。 */
private fun edgeMappingKind(
    edge: GraphEdge,
    fullEdgeIds: Set<String>,
    overflowNodeIds: Set<String>,
): GraphProjectionMappingKind =
    when {
        edge.fromNodeId in overflowNodeIds || edge.toNodeId in overflowNodeIds -> GraphProjectionMappingKind.OVERFLOW_READONLY
        edge.metadata["flow.synthetic"] == "true" || edge.metadata["flowchart.synthetic"] != null ->
            GraphProjectionMappingKind.SYNTHETIC_READONLY
        edge.id in fullEdgeIds -> GraphProjectionMappingKind.EXACT
        else -> GraphProjectionMappingKind.PATH_ALIAS
    }

/** 判断节点是否为溢出占位节点（元数据包含溢出标记前缀）。 */
private fun isOverflowNode(node: GraphNode): Boolean =
    node.metadata.keys.any { key -> key.startsWith(GraphProjectionMetadata.Overflow.PREFIX) }

/** 返回节点允许执行的编辑命令集合，溢出占位节点不允许任何编辑。 */
private fun editableNodeCommands(node: GraphNode): Set<GraphEditCommandKind> {
    if (isOverflowNode(node)) {
        return emptySet()
    }
    return setOf(
        GraphEditCommandKind.UPDATE_NODE,
        GraphEditCommandKind.DELETE_NODE,
        GraphEditCommandKind.DELETE_NODE_SUBTREE,
        GraphEditCommandKind.CONNECT_NODES,
    )
}

/** 返回边允许执行的编辑命令集合，合成边不允许任何编辑。 */
private fun editableEdgeCommands(edge: GraphEdge): Set<GraphEditCommandKind> {
    if (edge.metadata["flow.synthetic"] == "true" || edge.metadata["flowchart.synthetic"] != null) {
        return emptySet()
    }
    return setOf(
        GraphEditCommandKind.DELETE_EDGE,
        GraphEditCommandKind.INSERT_NODE_INTO_EDGE,
    )
}
