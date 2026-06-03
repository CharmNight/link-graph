package com.charmnight.linkgraph.ui.view

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.projection.GraphProjectionMetadata

private const val FLOWCHART_ALIAS_IDS_KEY = "flowchart.projectedFromNodeIds"

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

internal fun graphProjectionIndexForVisibleGraph(
    visibleGraph: GraphDocument,
    fullGraph: GraphDocument,
): GraphProjectionIndex {
    val fullEdgeIds = fullGraph.edges.mapTo(linkedSetOf()) { edge -> edge.id }
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

private fun projectedAliasNodeIds(node: GraphNode): List<String> =
    node.metadata[FLOWCHART_ALIAS_IDS_KEY]
        ?.split(',')
        ?.mapNotNull { nodeId -> nodeId.trim().takeIf(String::isNotBlank) }
        .orEmpty()

private fun nodeMappingKind(
    node: GraphNode,
    canonicalIds: List<String>,
): GraphProjectionMappingKind =
    when {
        isOverflowNode(node) -> GraphProjectionMappingKind.OVERFLOW_READONLY
        canonicalIds.distinct().size > 1 -> GraphProjectionMappingKind.MERGED_ALIAS
        else -> GraphProjectionMappingKind.EXACT
    }

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

private fun isOverflowNode(node: GraphNode): Boolean =
    node.metadata.keys.any { key -> key.startsWith(GraphProjectionMetadata.Overflow.PREFIX) }

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

private fun editableEdgeCommands(edge: GraphEdge): Set<GraphEditCommandKind> {
    if (edge.metadata["flow.synthetic"] == "true" || edge.metadata["flowchart.synthetic"] != null) {
        return emptySet()
    }
    return setOf(
        GraphEditCommandKind.DELETE_EDGE,
        GraphEditCommandKind.INSERT_NODE_INTO_EDGE,
    )
}
