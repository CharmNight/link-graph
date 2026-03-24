package com.charmnight.linkgraph.diff

import com.charmnight.linkgraph.mermaid.MermaidBindingService
import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.DiffStatus
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch

data class GraphDifferResult(
    val graph: GraphDocument,
    val diff: GraphDiff,
)

class GraphDiffer(
    private val normalizationService: GraphNormalizationService = GraphNormalizationService(),
    private val bindingService: MermaidBindingService = MermaidBindingService(normalizationService),
) {
    fun diff(
        codeGraph: GraphDocument,
        mermaidGraph: GraphDocument,
    ): GraphDifferResult {
        val boundMermaid = bindingService.bind(mermaidGraph, codeGraph)
        val normalizedCode = normalizationService.normalize(codeGraph)
        val normalizedMermaid = normalizationService.normalize(boundMermaid)

        val nodeOutcomes = diffNodes(normalizedCode, normalizedMermaid)
        val edgeOutcomes = diffEdges(normalizedCode, normalizedMermaid)
        val changedEntries = (nodeOutcomes.mapNotNull { it.entry } + edgeOutcomes.mapNotNull { it.entry })
            .sortedWith(compareBy({ it.elementKind.name }, { it.elementId }))
        val summary = buildSummary(changedEntries)

        val graph = GraphDocument(
            nodes = nodeOutcomes.map { it.node }.sortedBy { it.id },
            edges = edgeOutcomes.map { it.edge }.sortedBy { it.id },
            patch = GraphPatch(
                addedNodeIds = changedEntries.filter { it.elementKind == GraphDiffElementKind.NODE && it.status == DiffStatus.ONLY_IN_MERMAID }.map { it.elementId },
                removedNodeIds = changedEntries.filter { it.elementKind == GraphDiffElementKind.NODE && it.status == DiffStatus.ONLY_IN_CODE }.map { it.elementId },
                addedEdgeIds = changedEntries.filter { it.elementKind == GraphDiffElementKind.EDGE && it.status == DiffStatus.ONLY_IN_MERMAID }.map { it.elementId },
                removedEdgeIds = changedEntries.filter { it.elementKind == GraphDiffElementKind.EDGE && it.status == DiffStatus.ONLY_IN_CODE }.map { it.elementId },
            ),
        )

        return GraphDifferResult(
            graph = graph,
            diff = GraphDiff(
                status = if (changedEntries.isEmpty()) DiffStatus.MATCHED else DiffStatus.MODIFIED,
                summary = summary,
                entries = changedEntries,
            ),
        )
    }

    private fun diffNodes(
        codeGraph: GraphDocument,
        mermaidGraph: GraphDocument,
    ): List<NodeOutcome> {
        val codeNodesById = codeGraph.nodes.associateBy { it.id }
        val mermaidNodesById = mermaidGraph.nodes.associateBy { it.id }
        val allIds = (codeNodesById.keys + mermaidNodesById.keys).toSortedSet()

        return allIds.map { id ->
            val codeNode = codeNodesById[id]
            val mermaidNode = mermaidNodesById[id]
            when {
                codeNode != null && mermaidNode == null -> {
                    val diff = GraphDiff(
                        status = DiffStatus.ONLY_IN_CODE,
                        message = "Present in code but missing from Mermaid.",
                    )
                    NodeOutcome(
                        node = codeNode.copy(diff = diff),
                        entry = GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = codeNode.id,
                            status = DiffStatus.ONLY_IN_CODE,
                            message = diff.message,
                        ),
                    )
                }

                codeNode == null && mermaidNode != null -> {
                    val diff = GraphDiff(
                        status = DiffStatus.ONLY_IN_MERMAID,
                        message = "Present in Mermaid but missing from code.",
                    )
                    NodeOutcome(
                        node = mermaidNode.copy(diff = diff),
                        entry = GraphDiffEntry(
                            elementKind = GraphDiffElementKind.NODE,
                            elementId = mermaidNode.id,
                            status = DiffStatus.ONLY_IN_MERMAID,
                            message = diff.message,
                        ),
                    )
                }

                codeNode != null && mermaidNode != null -> {
                    val fields = compareNodes(codeNode, mermaidNode)
                    val status = if (fields.isEmpty()) DiffStatus.MATCHED else DiffStatus.MODIFIED
                    val diff = GraphDiff(
                        status = status,
                        fields = fields,
                        counterpartId = mermaidNode.id,
                        message = fields.takeIf { it.isNotEmpty() }?.joinToString(
                            prefix = "Modified fields: ",
                            separator = ", ",
                        ),
                    )
                    NodeOutcome(
                        node = codeNode.copy(
                            diff = diff,
                            bindingStatus = if (status == DiffStatus.MODIFIED) BindingStatus.PARTIALLY_SYNCED else codeNode.bindingStatus,
                        ),
                        entry = diff.takeIf { it.status != DiffStatus.MATCHED }?.let {
                            GraphDiffEntry(
                                elementKind = GraphDiffElementKind.NODE,
                                elementId = codeNode.id,
                                counterpartId = mermaidNode.id,
                                status = it.status,
                                fields = it.fields,
                                message = it.message,
                            )
                        },
                    )
                }

                else -> error("Unreachable node diff branch.")
            }
        }
    }

    private fun diffEdges(
        codeGraph: GraphDocument,
        mermaidGraph: GraphDocument,
    ): List<EdgeOutcome> {
        val codeEdgesById = codeGraph.edges.associateBy { it.id }
        val mermaidEdgesById = mermaidGraph.edges.associateBy { it.id }
        val remainingCode = codeEdgesById.toMutableMap()
        val remainingMermaid = mermaidEdgesById.toMutableMap()
        val outcomes = mutableListOf<EdgeOutcome>()

        val matchedIds = (codeEdgesById.keys intersect mermaidEdgesById.keys).sorted()
        matchedIds.forEach { id ->
            val codeEdge = remainingCode.remove(id) ?: return@forEach
            val mermaidEdge = remainingMermaid.remove(id) ?: return@forEach
            outcomes += buildMatchedEdgeOutcome(codeEdge, mermaidEdge)
        }

        remainingCode.values.sortedBy { it.id }.forEach { codeEdge ->
            val candidate = findModifiedEdgeCandidate(codeEdge, remainingMermaid.values.toList())
            if (candidate != null) {
                remainingCode.remove(codeEdge.id)
                remainingMermaid.remove(candidate.id)
                outcomes += buildMatchedEdgeOutcome(codeEdge, candidate)
            }
        }

        remainingCode.values.sortedBy { it.id }.forEach { codeEdge ->
            val diff = GraphDiff(
                status = DiffStatus.ONLY_IN_CODE,
                message = "Edge exists in code but missing from Mermaid.",
            )
            outcomes += EdgeOutcome(
                edge = codeEdge.copy(diff = diff),
                entry = GraphDiffEntry(
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = codeEdge.id,
                    status = DiffStatus.ONLY_IN_CODE,
                    message = diff.message,
                ),
            )
        }

        remainingMermaid.values.sortedBy { it.id }.forEach { mermaidEdge ->
            val diff = GraphDiff(
                status = DiffStatus.ONLY_IN_MERMAID,
                message = "Edge exists in Mermaid but missing from code.",
            )
            outcomes += EdgeOutcome(
                edge = mermaidEdge.copy(diff = diff),
                entry = GraphDiffEntry(
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = mermaidEdge.id,
                    status = DiffStatus.ONLY_IN_MERMAID,
                    message = diff.message,
                ),
            )
        }

        return outcomes.sortedBy { it.edge.id }
    }

    private fun buildMatchedEdgeOutcome(
        codeEdge: GraphEdge,
        mermaidEdge: GraphEdge,
    ): EdgeOutcome {
        val fields = compareEdges(codeEdge, mermaidEdge)
        val status = if (fields.isEmpty()) DiffStatus.MATCHED else DiffStatus.MODIFIED
        val diff = GraphDiff(
            status = status,
            fields = fields,
            counterpartId = mermaidEdge.id,
            message = fields.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "Modified fields: ",
                separator = ", ",
            ),
        )
        return EdgeOutcome(
            edge = codeEdge.copy(
                diff = diff,
                bindingStatus = if (status == DiffStatus.MODIFIED) BindingStatus.PARTIALLY_SYNCED else codeEdge.bindingStatus,
            ),
            entry = diff.takeIf { it.status != DiffStatus.MATCHED }?.let {
                GraphDiffEntry(
                    elementKind = GraphDiffElementKind.EDGE,
                    elementId = codeEdge.id,
                    counterpartId = mermaidEdge.id,
                    status = it.status,
                    fields = it.fields,
                    message = it.message,
                )
            },
        )
    }

    private fun findModifiedEdgeCandidate(
        codeEdge: GraphEdge,
        mermaidEdges: List<GraphEdge>,
    ): GraphEdge? {
        val scored = mermaidEdges
            .asSequence()
            .filter { it.type == codeEdge.type }
            .map { candidate -> candidate to edgeSimilarityScore(codeEdge, candidate) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<GraphEdge, Int>> { it.second }.thenBy { it.first.id })
            .toList()
        val best = scored.firstOrNull() ?: return null
        val secondScore = scored.getOrNull(1)?.second
        return if (secondScore == null || best.second > secondScore) {
            best.first
        } else {
            null
        }
    }

    private fun edgeSimilarityScore(
        left: GraphEdge,
        right: GraphEdge,
    ): Int {
        var score = 0
        if (left.fromNodeId == right.fromNodeId) {
            score += 4
        }
        if (left.toNodeId == right.toNodeId) {
            score += 4
        }
        if (!left.label.isNullOrBlank() && left.label == right.label) {
            score += 2
        }
        return score
    }

    private fun compareNodes(
        codeNode: GraphNode,
        mermaidNode: GraphNode,
    ): List<String> {
        val fields = mutableListOf<String>()
        if (codeNode.type != mermaidNode.type) {
            fields += "type"
        }
        if (codeNode.title != mermaidNode.title) {
            fields += "title"
        }
        if (codeNode.location != mermaidNode.location) {
            fields += "location"
        }
        if (codeNode.signature != mermaidNode.signature) {
            fields += "signature"
        }
        if (codeNode.inputs != mermaidNode.inputs) {
            fields += "inputs"
        }
        if (codeNode.outputs != mermaidNode.outputs) {
            fields += "outputs"
        }
        if (codeNode.doc != mermaidNode.doc) {
            fields += "doc"
        }
        if (codeNode.uncertainty?.reason != mermaidNode.uncertainty?.reason) {
            fields += "uncertainty.reason"
        }
        if (codeNode.uncertainty?.confidence != mermaidNode.uncertainty?.confidence) {
            fields += "uncertainty.confidence"
        }
        fields += diffMetadata(codeNode.metadata, mermaidNode.metadata)
        return fields
    }

    private fun compareEdges(
        codeEdge: GraphEdge,
        mermaidEdge: GraphEdge,
    ): List<String> {
        val fields = mutableListOf<String>()
        if (codeEdge.type != mermaidEdge.type) {
            fields += "type"
        }
        if (codeEdge.fromNodeId != mermaidEdge.fromNodeId) {
            fields += "fromNodeId"
        }
        if (codeEdge.toNodeId != mermaidEdge.toNodeId) {
            fields += "toNodeId"
        }
        if (codeEdge.label != mermaidEdge.label) {
            fields += "label"
        }
        if (codeEdge.uncertainty?.reason != mermaidEdge.uncertainty?.reason) {
            fields += "uncertainty.reason"
        }
        if (codeEdge.uncertainty?.confidence != mermaidEdge.uncertainty?.confidence) {
            fields += "uncertainty.confidence"
        }
        fields += diffMetadata(codeEdge.metadata, mermaidEdge.metadata)
        return fields
    }

    private fun diffMetadata(
        left: Map<String, String>,
        right: Map<String, String>,
    ): List<String> {
        return (left.keys + right.keys)
            .toSortedSet()
            .mapNotNull { key ->
                if (left[key] != right[key]) {
                    "metadata.$key"
                } else {
                    null
                }
            }
    }

    private fun buildSummary(entries: List<GraphDiffEntry>): String {
        if (entries.isEmpty()) {
            return "No graph differences."
        }
        val parts = mutableListOf<String>()
        summarize(entries, GraphDiffElementKind.NODE)?.let { parts += "nodes: $it" }
        summarize(entries, GraphDiffElementKind.EDGE)?.let { parts += "edges: $it" }
        return parts.joinToString("; ")
    }

    private fun summarize(
        entries: List<GraphDiffEntry>,
        kind: GraphDiffElementKind,
    ): String? {
        val counts = entries
            .filter { it.elementKind == kind }
            .groupingBy { it.status }
            .eachCount()
        if (counts.isEmpty()) {
            return null
        }
        return listOfNotNull(
            counts[DiffStatus.ONLY_IN_CODE]?.let { "$it only in code" },
            counts[DiffStatus.ONLY_IN_MERMAID]?.let { "$it only in Mermaid" },
            counts[DiffStatus.MODIFIED]?.let { "$it modified" },
        ).joinToString(", ")
    }

    private data class NodeOutcome(
        val node: GraphNode,
        val entry: GraphDiffEntry?,
    )

    private data class EdgeOutcome(
        val edge: GraphEdge,
        val entry: GraphDiffEntry?,
    )
}
