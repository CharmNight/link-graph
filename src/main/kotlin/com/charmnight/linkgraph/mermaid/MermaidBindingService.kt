package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.diff.GraphNormalizationService
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode

class MermaidBindingService(
    private val normalizationService: GraphNormalizationService = GraphNormalizationService(),
) {
    fun apply(document: GraphDocument): GraphDocument {
        return document.copy(
            nodes = document.nodes.map { apply(it) },
        )
    }

    fun bind(
        mermaidGraph: GraphDocument,
        codeGraph: GraphDocument,
    ): GraphDocument {
        val codeNodesById = codeGraph.nodes.associateBy { it.id.trim() }
        val lookupIndex = buildLookupIndex(codeGraph.nodes)

        val boundNodes = mermaidGraph.nodes.map { node ->
            val resolution = resolveBinding(node, codeNodesById, lookupIndex)
            apply(node, resolution)
        }
        val boundNodeIds = boundNodes.associate { node ->
            node.id to (node.metadata[BOUND_NODE_ID_KEY] ?: node.id)
        }

        val boundEdges = mermaidGraph.edges.map { edge ->
            val boundFromNodeId = boundNodeIds[edge.fromNodeId] ?: edge.fromNodeId
            val boundToNodeId = boundNodeIds[edge.toNodeId] ?: edge.toNodeId
            val metadata = linkedMapOf<String, String>()
            metadata.putAll(edge.metadata)
            if (boundFromNodeId != edge.fromNodeId) {
                metadata[BOUND_FROM_NODE_ID_KEY] = boundFromNodeId
            }
            if (boundToNodeId != edge.toNodeId) {
                metadata[BOUND_TO_NODE_ID_KEY] = boundToNodeId
            }

            edge.copy(
                bindingStatus = when {
                    boundFromNodeId != edge.fromNodeId && boundToNodeId != edge.toNodeId -> BindingStatus.BOUND
                    boundFromNodeId != edge.fromNodeId || boundToNodeId != edge.toNodeId -> BindingStatus.PARTIALLY_SYNCED
                    else -> edge.bindingStatus
                },
                metadata = metadata,
            )
        }

        return GraphDocument(
            nodes = boundNodes,
            edges = boundEdges,
            patch = mermaidGraph.patch,
        )
    }

    fun apply(node: GraphNode): GraphNode {
        val marker = node.metadata[BINDING_KEY]?.trim()?.uppercase()
        val nextStatus = when (marker) {
            "MATCHED" -> BindingStatus.BOUND
            "UNMATCHED" -> BindingStatus.DESIGN_ONLY
            "MULTI_CANDIDATE", "CONFLICTED" -> BindingStatus.CONFLICTED
            null -> if (node.metadata[BOUND_NODE_ID_KEY].isNullOrBlank()) node.bindingStatus else BindingStatus.BOUND
            else -> node.bindingStatus
        }
        return node.copy(bindingStatus = nextStatus)
    }

    private fun apply(
        node: GraphNode,
        resolution: BindingResolution,
    ): GraphNode {
        val metadata = linkedMapOf<String, String>()
        metadata.putAll(node.metadata)
        metadata[BINDING_KEY] = resolution.marker
        resolution.boundNodeId?.let { metadata[BOUND_NODE_ID_KEY] = it }
        if (resolution.candidateIds.isNotEmpty()) {
            metadata[BINDING_CANDIDATES_KEY] = resolution.candidateIds.joinToString(",")
        }
        return node.copy(
            bindingStatus = resolution.status,
            metadata = metadata,
        )
    }

    private fun resolveBinding(
        node: GraphNode,
        codeNodesById: Map<String, GraphNode>,
        lookupIndex: Map<String, List<GraphNode>>,
    ): BindingResolution {
        node.metadata[BOUND_NODE_ID_KEY]
            ?.takeIf { it.isNotBlank() }
            ?.trim()
            ?.let { boundId ->
                codeNodesById[boundId]?.let {
                    return BindingResolution(
                        status = BindingStatus.BOUND,
                        marker = "MATCHED",
                        boundNodeId = it.id,
                    )
                }
            }

        codeNodesById[node.id.trim()]?.let {
            return BindingResolution(
                status = BindingStatus.BOUND,
                marker = "MATCHED",
                boundNodeId = it.id,
            )
        }

        val candidates = firstNonEmpty(
            lookup(node.signature, lookupIndex),
            lookup(node.metadata["path"], lookupIndex),
            lookup(node.metadata["topic"], lookupIndex),
            lookup(node.metadata["key"], lookupIndex),
            lookup(node.metadata["statementId"], lookupIndex),
            lookup(node.location, lookupIndex),
            lookup(node.title, lookupIndex),
            normalizationService.lookupKeys(node).mapNotNull { lookupIndex[it] }.flatten().distinctBy { it.id },
        )

        return when {
            candidates.size == 1 -> BindingResolution(
                status = BindingStatus.BOUND,
                marker = "MATCHED",
                boundNodeId = candidates.single().id,
            )

            candidates.size > 1 -> BindingResolution(
                status = BindingStatus.CONFLICTED,
                marker = "MULTI_CANDIDATE",
                candidateIds = candidates.map { it.id }.sorted(),
            )

            else -> BindingResolution(
                status = BindingStatus.DESIGN_ONLY,
                marker = "UNMATCHED",
            )
        }
    }

    private fun buildLookupIndex(nodes: List<GraphNode>): Map<String, List<GraphNode>> {
        val index = linkedMapOf<String, MutableList<GraphNode>>()
        nodes.forEach { node ->
            normalizationService.lookupKeys(node).forEach { key ->
                index.getOrPut(key) { mutableListOf() } += node
            }
        }
        return index
    }

    private fun lookup(
        rawKey: String?,
        lookupIndex: Map<String, List<GraphNode>>,
    ): List<GraphNode> {
        val key = rawKey?.trim()?.replace(MULTI_WHITESPACE, " ")?.lowercase()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return lookupIndex[key].orEmpty()
    }

    private fun firstNonEmpty(vararg candidates: List<GraphNode>): List<GraphNode> {
        return candidates.firstOrNull { it.isNotEmpty() }?.distinctBy { it.id }.orEmpty()
    }

    private data class BindingResolution(
        val status: BindingStatus,
        val marker: String,
        val boundNodeId: String? = null,
        val candidateIds: List<String> = emptyList(),
    )

    companion object {
        private val MULTI_WHITESPACE = Regex("\\s+")

        const val BINDING_KEY: String = "binding"
        const val BOUND_NODE_ID_KEY: String = "boundNodeId"
        const val BINDING_CANDIDATES_KEY: String = "bindingCandidates"
        const val BOUND_FROM_NODE_ID_KEY: String = "boundFromNodeId"
        const val BOUND_TO_NODE_ID_KEY: String = "boundToNodeId"
    }
}
