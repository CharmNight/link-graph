package com.charmnight.linkgraph.diff

import com.charmnight.linkgraph.mermaid.MermaidBindingService
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphUncertainty
import com.charmnight.linkgraph.model.NodeType

class GraphNormalizationService {
    fun normalize(document: GraphDocument): GraphDocument {
        val normalizedNodeIds = document.nodes.associate { it.id to normalizedNodeId(it) }
        val normalizedNodes = linkedMapOf<String, GraphNode>()
        document.nodes.forEach { node ->
            val normalized = normalizeNode(node, normalizedNodeIds.getValue(node.id))
            normalizedNodes[normalized.id] = normalized
        }

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

    fun normalizedNodeId(node: GraphNode): String {
        node.metadata[MermaidBindingService.BOUND_NODE_ID_KEY]
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.trim() }

        val trimmedId = node.id.trim()
        if (looksStableNodeId(node, trimmedId)) {
            return trimmedId
        }

        return GraphNode.stableId(
            type = node.type,
            rawKey = comparisonKey(node),
        )
    }

    fun comparisonKey(node: GraphNode): String {
        val key = when (node.type) {
            NodeType.METHOD -> node.signature ?: node.title
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

    private fun normalizeEdge(
        edge: GraphEdge,
        normalizedNodeIds: Map<String, String>,
    ): GraphEdge? {
        val fromNodeId = edge.metadata[MermaidBindingService.BOUND_FROM_NODE_ID_KEY]
            ?.takeIf { it.isNotBlank() }
            ?.trim()
            ?: normalizedNodeIds[edge.fromNodeId]
        val toNodeId = edge.metadata[MermaidBindingService.BOUND_TO_NODE_ID_KEY]
            ?.takeIf { it.isNotBlank() }
            ?.trim()
            ?: normalizedNodeIds[edge.toNodeId]
        if (fromNodeId == null || toNodeId == null) {
            return null
        }

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

    private fun normalizeMetadata(metadata: Map<String, String>): Map<String, String> {
        return metadata
            .filterKeys { it !in INTERNAL_METADATA_KEYS }
            .mapNotNull { (key, value) ->
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

    private fun normalizeUncertainty(uncertainty: GraphUncertainty?): GraphUncertainty? {
        if (uncertainty == null) {
            return null
        }
        return GraphUncertainty(
            reason = normalizeText(uncertainty.reason).orEmpty(),
            confidence = uncertainty.confidence,
        )
    }

    private fun normalizeStrings(values: List<String>): List<String> {
        return values
            .mapNotNull { normalizeText(it) }
            .filter { it.isNotBlank() }
            .sorted()
    }

    private fun lookupKey(value: String?): String? {
        return normalizeText(value)?.lowercase()?.takeIf { it.isNotBlank() }
    }

    private fun looksStableNodeId(
        node: GraphNode,
        id: String,
    ): Boolean {
        val prefix = node.type.name.lowercase().replace('_', '-') + ":"
        return id.lowercase().startsWith(prefix)
    }

    private fun normalizeText(value: String?): String? {
        if (value == null) {
            return null
        }
        return value.trim().replace(MULTI_WHITESPACE, " ").takeIf { it.isNotBlank() }
    }

    companion object {
        private val MULTI_WHITESPACE = Regex("\\s+")

        private val INTERNAL_METADATA_KEYS = setOf(
            MermaidBindingService.BINDING_KEY,
            MermaidBindingService.BOUND_NODE_ID_KEY,
            MermaidBindingService.BINDING_CANDIDATES_KEY,
            MermaidBindingService.BOUND_FROM_NODE_ID_KEY,
            MermaidBindingService.BOUND_TO_NODE_ID_KEY,
        )
    }
}
