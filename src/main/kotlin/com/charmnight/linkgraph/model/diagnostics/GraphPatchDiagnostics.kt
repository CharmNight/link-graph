package com.charmnight.linkgraph.model.diagnostics

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphPatch

internal object GraphPatchDiagnostics {
    fun summarizeGraphPatch(patch: GraphPatch?): String {
        if (patch == null) {
            return "null"
        }
        return buildString {
            append("summary=").append(trimmed(patch.summary))
            append(", operations=").append(patch.operations.size)
            append(", opTargets=").append(
                entryTitles(
                    patch.operations.map { operation ->
                        buildString {
                            append(operation.action.name)
                            append(':')
                            append(operation.elementId)
                            operation.node?.title?.takeIf(String::isNotBlank)?.let {
                                append(':').append(trimmed(it))
                            }
                        }
                    },
                ),
            )
            append(", addedNodes=").append(entryTitles(patch.addedNodeIds))
            append(", removedNodes=").append(entryTitles(patch.removedNodeIds))
            append(", addedEdges=").append(entryTitles(patch.addedEdgeIds))
            append(", removedEdges=").append(entryTitles(patch.removedEdgeIds))
        }
    }

    fun summarizeNodeStates(
        graph: GraphDocument,
        nodeIds: Collection<String>,
    ): String {
        if (nodeIds.isEmpty()) {
            return "[]"
        }
        val nodesById = graph.nodes.associateBy { it.id }
        val values = nodeIds
            .filter { it.isNotBlank() }
            .distinct()
            .map { nodeId ->
                val node = nodesById[nodeId]
                if (node == null) {
                    "$nodeId:<missing>"
                } else {
                    "$nodeId:${trimmed(node.title)}"
                }
            }
        return entryTitles(values)
    }

    fun entryTitles(values: List<String>): String {
        if (values.isEmpty()) {
            return "[]"
        }
        return values.take(3).joinToString(
            prefix = "[",
            postfix = if (values.size > 3) ", ...]" else "]",
        ) { trimmed(it) }
    }

    fun trimmed(value: String?): String {
        val normalized = value?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return "-"
        }
        return if (normalized.length <= 96) normalized else normalized.take(93) + "..."
    }
}
