package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType

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
            ToolGraphSceneId.WORKSPACE_ARCHITECTURE_GRAPH -> CurrentView(
                visibleGraph = snapshot.architectureGraphView.visibleGraph,
                fullGraph = snapshot.architectureGraphView.fullGraph,
                projectionIndex = snapshot.architectureGraphView.projectionIndex,
            )
            ToolGraphSceneId.WORKSPACE_CLASS_DIAGRAM -> CurrentView(
                visibleGraph = snapshot.classDiagramView.visibleGraph,
                fullGraph = snapshot.classDiagramView.fullGraph,
                projectionIndex = snapshot.classDiagramView.projectionIndex,
            )
            ToolGraphSceneId.WORKSPACE_REVIEW_GRAPH -> CurrentView(
                visibleGraph = snapshot.reviewGraphView.visibleGraph,
                fullGraph = snapshot.reviewGraphView.fullGraph,
                projectionIndex = snapshot.reviewGraphView.projectionIndex,
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
        val graphCandidates = documents.flatMapIndexed { documentIndex, (stage, graph) ->
            graph.nodes
                .filter { node -> node.signature == signature }
                .map { node -> SignatureAnchorCandidate(stage, documentIndex, node) }
        }
        graphCandidates
            .sortedWith(
                compareBy<SignatureAnchorCandidate>(
                    { candidate -> signatureAnchorRank(candidate.node) },
                    SignatureAnchorCandidate::documentIndex,
                    { candidate -> candidate.node.id },
                ),
            )
            .firstNotNullOfOrNull { candidate -> readable(candidate.node, candidate.stage) }
            ?.let { return it }
        return snapshot.trustedNavigationNodes.values
            .filter { node -> node.signature == signature }
            .map { node -> SignatureAnchorCandidate("trustedNavigationNodes", documents.size, node) }
            .sortedWith(compareBy({ candidate -> signatureAnchorRank(candidate.node) }, { candidate -> candidate.node.id }))
            .firstNotNullOfOrNull { candidate -> readable(candidate.node, candidate.stage) }
    }

    private fun GraphDocument.findNode(nodeId: String): GraphNode? = nodes.firstOrNull { node -> node.id == nodeId }

    private fun hasSourceMetadata(node: GraphNode): Boolean = !node.metadata["source.filePath"].isNullOrBlank()

    private fun signatureAnchorRank(node: GraphNode): Int {
        return when {
            node.type == NodeType.METHOD -> 0
            node.metadata["flow.kind"] == "INVOCATION" -> 2
            else -> 1
        }
    }

    private data class CurrentView(
        val visibleGraph: GraphDocument,
        val fullGraph: GraphDocument,
        val projectionIndex: ToolGraphProjectionIndex,
    )

    private data class SignatureAnchorCandidate(
        val stage: String,
        val documentIndex: Int,
        val node: GraphNode,
    )
}
