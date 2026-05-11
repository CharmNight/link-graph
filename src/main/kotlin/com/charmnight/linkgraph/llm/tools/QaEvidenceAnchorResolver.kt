package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode

data class QaEvidenceAnchorResolution(
    val requestedNodeId: String? = null,
    val requestedSymbolSignature: String? = null,
    val node: GraphNode? = null,
    val resolvedNodeId: String? = node?.id,
    val mappingTrace: List<String> = emptyList(),
)

class QaEvidenceAnchorResolver {
    fun resolve(
        snapshot: ToolGraphSnapshot,
        nodeId: String? = null,
        symbolSignature: String? = null,
    ): QaEvidenceAnchorResolution {
        val normalizedNodeId = nodeId?.trim()?.takeIf(String::isNotEmpty)
        val normalizedSymbol = symbolSignature?.trim()?.takeIf(String::isNotEmpty)
        val trace = mutableListOf<String>()
        var fallbackNode: GraphNode? = null

        fun rememberFallback(node: GraphNode?, stage: String) {
            if (node != null && fallbackNode == null) {
                fallbackNode = node
                trace += "$stage:${node.id}:no-source"
            }
        }

        fun readable(node: GraphNode?, stage: String): GraphNode? {
            if (node == null) {
                return null
            }
            if (hasSourceMetadata(node)) {
                trace += "$stage:${node.id}:${node.metadata["source.filePath"].orEmpty()}"
                return node
            }
            rememberFallback(node, stage)
            return null
        }

        val currentView = currentView(snapshot)
        if (normalizedNodeId != null) {
            readable(currentView.visibleGraph.findNode(normalizedNodeId), "currentGraph")?.let { node ->
                return resolution(normalizedNodeId, normalizedSymbol, node, trace)
            }

            val mappedCanonicalIds = currentView.projectionIndex.nodeMapping(normalizedNodeId)
                ?.canonicalNodeIds
                .orEmpty()
                .filter { canonicalNodeId -> canonicalNodeId.isNotBlank() }
                .distinct()
            if (mappedCanonicalIds.isNotEmpty()) {
                trace += mappedCanonicalIds.joinToString(
                    prefix = "projectionIndex:$normalizedNodeId->",
                    separator = ",",
                )
                mappedCanonicalIds.firstReadableFrom(
                    documents = listOf(
                        "canonicalGraph" to currentView.fullGraph,
                        "workspaceGraph" to snapshot.workspaceGraph,
                        "semanticFactGraph" to snapshot.semanticFactGraph,
                    ),
                    readable = ::readable,
                )?.let { node ->
                    return resolution(normalizedNodeId, normalizedSymbol, node, trace)
                }
                mappedCanonicalIds.firstReadableTrustedNode(snapshot, ::readable)?.let { node ->
                    return resolution(normalizedNodeId, normalizedSymbol, node, trace)
                }
            }

            listOf(normalizedNodeId).firstReadableFrom(
                documents = listOf(
                    "workspaceGraph" to snapshot.workspaceGraph,
                    "semanticFactGraph" to snapshot.semanticFactGraph,
                ),
                readable = ::readable,
            )?.let { node ->
                return resolution(normalizedNodeId, normalizedSymbol, node, trace)
            }
            listOf(normalizedNodeId).firstReadableTrustedNode(snapshot, ::readable)?.let { node ->
                return resolution(normalizedNodeId, normalizedSymbol, node, trace)
            }
        }

        val effectiveSignature = normalizedSymbol ?: snapshot.selectedMethodSignature?.trim()?.takeIf(String::isNotEmpty)
        if (effectiveSignature != null) {
            trace += "selectedMethodSignature:$effectiveSignature"
            findBySignature(snapshot, currentView, effectiveSignature, ::readable)?.let { node ->
                return resolution(normalizedNodeId, normalizedSymbol, node, trace)
            }
        }

        return QaEvidenceAnchorResolution(
            requestedNodeId = normalizedNodeId,
            requestedSymbolSignature = normalizedSymbol,
            node = fallbackNode,
            resolvedNodeId = fallbackNode?.id,
            mappingTrace = trace.distinct(),
        )
    }

    private fun resolution(
        requestedNodeId: String?,
        requestedSymbolSignature: String?,
        node: GraphNode,
        trace: List<String>,
    ): QaEvidenceAnchorResolution = QaEvidenceAnchorResolution(
        requestedNodeId = requestedNodeId,
        requestedSymbolSignature = requestedSymbolSignature,
        node = node,
        resolvedNodeId = node.id,
        mappingTrace = trace.distinct(),
    )

    private fun currentView(snapshot: ToolGraphSnapshot): CurrentView {
        return when (snapshot.currentSceneId) {
            ToolGraphSceneId.WORKSPACE_FLOWCHART -> CurrentView(
                visibleGraph = snapshot.flowchartView.visibleGraph,
                fullGraph = snapshot.flowchartView.fullGraph,
                projectionIndex = snapshot.flowchartView.projectionIndex,
            )
            ToolGraphSceneId.WORKSPACE_RESOURCE_RELATION -> CurrentView(
                visibleGraph = snapshot.resourceRelationView.visibleGraph,
                fullGraph = snapshot.resourceRelationView.fullGraph,
                projectionIndex = snapshot.resourceRelationView.projectionIndex,
            )
            ToolGraphSceneId.WORKSPACE_FACT -> CurrentView(
                visibleGraph = snapshot.factGraphView.visibleGraph,
                fullGraph = snapshot.factGraphView.fullGraph,
                projectionIndex = snapshot.factGraphView.projectionIndex,
            )
            ToolGraphSceneId.DIFF -> CurrentView(
                visibleGraph = snapshot.diffGraph ?: GraphDocument(),
                fullGraph = snapshot.diffGraph ?: GraphDocument(),
                projectionIndex = ToolGraphProjectionIndex.EMPTY,
            )
        }
    }

    private fun List<String>.firstReadableFrom(
        documents: List<Pair<String, GraphDocument>>,
        readable: (GraphNode?, String) -> GraphNode?,
    ): GraphNode? {
        for (nodeId in this) {
            for ((stage, graph) in documents) {
                readable(graph.findNode(nodeId), stage)?.let { return it }
            }
        }
        return null
    }

    private fun List<String>.firstReadableTrustedNode(
        snapshot: ToolGraphSnapshot,
        readable: (GraphNode?, String) -> GraphNode?,
    ): GraphNode? {
        for (nodeId in this) {
            readable(snapshot.trustedNavigationNodes[nodeId], "trustedNavigationNodes")?.let { return it }
        }
        return null
    }

    private fun findBySignature(
        snapshot: ToolGraphSnapshot,
        currentView: CurrentView,
        signature: String,
        readable: (GraphNode?, String) -> GraphNode?,
    ): GraphNode? {
        val documents = listOf(
            "currentGraph" to currentView.visibleGraph,
            "canonicalGraph" to currentView.fullGraph,
            "workspaceGraph" to snapshot.workspaceGraph,
            "semanticFactGraph" to snapshot.semanticFactGraph,
        )
        for ((stage, graph) in documents) {
            readable(graph.nodes.firstOrNull { node -> node.signature == signature }, stage)?.let { return it }
        }
        return readable(
            snapshot.trustedNavigationNodes.values.firstOrNull { node -> node.signature == signature },
            "trustedNavigationNodes",
        )
    }

    private fun GraphDocument.findNode(nodeId: String): GraphNode? = nodes.firstOrNull { node -> node.id == nodeId }

    private fun hasSourceMetadata(node: GraphNode): Boolean = !node.metadata["source.filePath"].isNullOrBlank()

    private data class CurrentView(
        val visibleGraph: GraphDocument,
        val fullGraph: GraphDocument,
        val projectionIndex: ToolGraphProjectionIndex,
    )
}
