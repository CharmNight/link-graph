package com.charmnight.linkgraph.diff

import com.charmnight.linkgraph.mermaid.MermaidBindingService
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphUncertainty
import com.charmnight.linkgraph.model.NodeType

/**
 * 对图文档做标准化处理，确保 diff 比较只关注稳定且可比较的信息。
 * 主要工作包括稳定化节点 ID、去除内部元数据、统一文本格式与排序顺序。
 */
class GraphNormalizationService {
    /** 标准化整个图文档，并返回适合做 diff 的快照。 */
    fun normalize(document: GraphDocument): GraphDocument {
        /** 原始节点 ID 到标准化节点 ID 的映射。 */
        val normalizedNodeIds = document.nodes.associate { it.id to normalizedNodeId(it) }
        /** 去重并重排后的节点集合。 */
        val normalizedNodes = linkedMapOf<String, GraphNode>()
        document.nodes.forEach { node ->
            /** 当前节点标准化后的结果。 */
            val normalized = normalizeNode(node, normalizedNodeIds.getValue(node.id))
            normalizedNodes[normalized.id] = normalized
        }

        /** 去重并重排后的边集合。 */
        val normalizedEdges = linkedMapOf<String, GraphEdge>()
        document.edges.forEach { edge ->
            normalizeEdge(edge, normalizedNodeIds)?.let { normalizedEdges[it.id] = it }
        }

        return GraphDocument(
            nodes = normalizedNodes.values.sortedBy { it.id },
            edges = normalizedEdges.values.sortedBy { it.id },
            patch = document.patch,
        )
    }

    /** 生成节点在比较场景下应使用的稳定 ID。 */
    fun normalizedNodeId(node: GraphNode): String {
        node.metadata[MermaidBindingService.BOUND_NODE_ID_KEY]
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.trim() }

        /** 去除前后空白后的原始节点 ID。 */
        val trimmedId = node.id.trim()
        if (looksStableNodeId(node, trimmedId)) {
            return trimmedId
        }

        return GraphNode.stableId(
            type = node.type,
            rawKey = comparisonKey(node),
        )
    }

    /** 计算节点在 diff 中用于归一化比较的核心键。 */
    fun comparisonKey(node: GraphNode): String {
        /** 按节点类型选取的主比较字段。 */
        val key = when (node.type) {
            NodeType.METHOD -> node.signature ?: node.title
            NodeType.FLOW_SCOPE -> node.signature ?: node.title
            NodeType.HTTP_ENDPOINT -> node.metadata["path"] ?: node.title
            NodeType.MQ_TOPIC -> node.metadata["topic"] ?: node.title
            NodeType.CONFIG_ITEM -> node.metadata["key"] ?: node.signature ?: node.title
            NodeType.SQL -> node.signature ?: node.metadata["statementId"] ?: node.title
            NodeType.DOC_PAGE, NodeType.XML_RESOURCE -> node.location ?: node.signature ?: node.title
            NodeType.UNCERTAIN_LINK -> node.signature ?: node.title
            else -> node.signature ?: node.location ?: node.title
        }
        return normalizeText(key).orEmpty()
    }

    /** 提供一组可用于宽松匹配节点的查找键。 */
    fun lookupKeys(node: GraphNode): List<String> {
        return listOfNotNull(
            lookupKey(node.signature),
            lookupKey(node.metadata["path"]),
            lookupKey(node.metadata["topic"]),
            lookupKey(node.metadata["key"]),
            lookupKey(node.metadata["statementId"]),
            lookupKey(node.location),
            lookupKey(node.title),
            lookupKey(comparisonKey(node)),
        ).distinct()
    }

    /** 标准化节点字段，并替换成稳定 ID。 */
    private fun normalizeNode(node: GraphNode, normalizedId: String): GraphNode {
        return node.copy(
            id = normalizedId,
            title = normalizeText(node.title).orEmpty(),
            location = normalizeText(node.location),
            signature = normalizeText(node.signature),
            inputs = normalizeStrings(node.inputs),
            outputs = normalizeStrings(node.outputs),
            doc = normalizeText(node.doc),
            uncertainty = normalizeUncertainty(node.uncertainty),
            metadata = normalizeMetadata(node.metadata),
        )
    }

    /** 标准化边字段，并把端点 ID 映射到标准化节点。 */
    private fun normalizeEdge(
        edge: GraphEdge,
        normalizedNodeIds: Map<String, String>,
    ): GraphEdge? {
        /** 标准化后的起点节点 ID。 */
        val fromNodeId = edge.metadata[MermaidBindingService.BOUND_FROM_NODE_ID_KEY]
            ?.takeIf { it.isNotBlank() }
            ?.trim()
            ?: normalizedNodeIds[edge.fromNodeId]
        /** 标准化后的终点节点 ID。 */
        val toNodeId = edge.metadata[MermaidBindingService.BOUND_TO_NODE_ID_KEY]
            ?.takeIf { it.isNotBlank() }
            ?.trim()
            ?: normalizedNodeIds[edge.toNodeId]
        if (fromNodeId == null || toNodeId == null) {
            return null
        }

        /** 标准化后的边标签，会参与稳定 ID 计算。 */
        val label = normalizeText(edge.label)
        return edge.copy(
            id = GraphEdge.stableId(
                type = edge.type,
                fromNodeId = fromNodeId,
                toNodeId = toNodeId,
                ownerContext = label,
            ),
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            label = label,
            uncertainty = normalizeUncertainty(edge.uncertainty),
            metadata = normalizeMetadata(edge.metadata),
        )
    }

    /** 过滤内部元数据并统一键值顺序。 */
    private fun normalizeMetadata(metadata: Map<String, String>): Map<String, String> {
        return metadata
            .filterKeys { it !in INTERNAL_METADATA_KEYS }
            .mapNotNull { (key, value) ->
                /** 元数据值的标准化结果。 */
                val normalizedValue = normalizeText(value)
                if (normalizedValue.isNullOrBlank()) {
                    null
                } else {
                    key to normalizedValue
                }
            }
            .sortedBy { it.first }
            .toMap(linkedMapOf())
    }

    /** 标准化不确定性信息中的文本字段。 */
    private fun normalizeUncertainty(uncertainty: GraphUncertainty?): GraphUncertainty? {
        if (uncertainty == null) {
            return null
        }
        return GraphUncertainty(
            reason = normalizeText(uncertainty.reason).orEmpty(),
            confidence = uncertainty.confidence,
        )
    }

    /** 标准化字符串列表并做去空排序。 */
    private fun normalizeStrings(values: List<String>): List<String> {
        return values
            .mapNotNull { normalizeText(it) }
            .filter { it.isNotBlank() }
            .sorted()
    }

    /** 生成参与宽松查找的归一化小写键。 */
    private fun lookupKey(value: String?): String? {
        return normalizeText(value)?.lowercase()?.takeIf { it.isNotBlank() }
    }

    /** 判断节点 ID 是否已经具备稳定前缀。 */
    private fun looksStableNodeId(
        node: GraphNode,
        id: String,
    ): Boolean {
        /** 节点类型对应的稳定 ID 前缀。 */
        val prefix = node.type.name.lowercase().replace('_', '-') + ":"
        return id.lowercase().startsWith(prefix)
    }

    /** 统一文本空白并去除纯空字符串。 */
    private fun normalizeText(value: String?): String? {
        if (value == null) {
            return null
        }
        return value.trim().replace(MULTI_WHITESPACE, " ").takeIf { it.isNotBlank() }
    }

    companion object {
        /** 用于压缩连续空白的正则表达式。 */
        private val MULTI_WHITESPACE = Regex("\\s+")

        /** 归一化时需要剔除的内部绑定元数据键。 */
        private val INTERNAL_METADATA_KEYS = setOf(
            MermaidBindingService.BINDING_KEY,
            MermaidBindingService.BOUND_NODE_ID_KEY,
            MermaidBindingService.BINDING_CANDIDATES_KEY,
            MermaidBindingService.BOUND_FROM_NODE_ID_KEY,
            MermaidBindingService.BOUND_TO_NODE_ID_KEY,
        )
    }
}
